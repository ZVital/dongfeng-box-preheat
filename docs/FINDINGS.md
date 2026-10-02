# FINDINGS — как устроено управление климаном на Dongfeng Box

Всё ниже измерено на машине и в её софте. Ничего не пересказано по слухам.
Даты измерений — 2026-10-02.

Вендорские бинарники, дампы и переписка в этот репозиторий **намеренно не
включены** — см. `.gitignore`. Здесь только выводы и наш код.

---

## 1. Главное: климат управляется из обычного приложения

Приложение **без прав, без root и без нативного кода** подключается к
CAN-сервису головы и включает/выключает кондиционер.

```java
Intent i = new Intent("com.qinggan.canbus.CanBusService")
           .setPackage("com.qinggan.canbus.service");
bindService(i, conn, BIND_AUTO_CREATE);   // -> true
```

| Параметр | Значение | Источник |
|---|---|---|
| пакет | `com.qinggan.canbus.service` | манифест APK |
| компонент | `com.qinggan.canbus.service/.CanBusService` | Service Resolver Table |
| action | `com.qinggan.canbus.CanBusService` | Service Resolver Table |
| токен интерфейса | `com.qinggan.canbus.ICanBusService` | сгенерированный AIDL-Proxy |
| `exported` | **true** | AndroidManifest.xml |
| `android:permission` | **отсутствует** | там же |

Проверено на машине, владелец не трогал автомобиль:

```
transact(89) -> 1   closeAirConditionSwitch
   состояние 1 -> 0, isAirConditionClose false -> true, владелец: выключился
transact(88) -> 1   openAirConditionSwitch
   состояние 0 -> 1 (672 замера), владелец: включился
```

Сервис при этом **реально отправляет кадр в шину**, в ту же миллисекунду:

```
43 10 00 80 7e 00 00 00 00 00 ff ff ff 1f 00 3c ff fc
```

Это совпадает с кадром `0x43` длиной 16, который был предсказан заранее при
разборе метода отправки климатического цикла в dex-дампе сервиса.

## 2. Коды транзакций AIDL

Взяты из сгенерированного Proxy: каждое `const/16 vN, #int <code>` стоит
непосредственно перед `IBinder.transact`.

| Код | Метод |
|---|---|
| 88 / 89 | `openAirConditionSwitch` / `closeAirConditionSwitch` |
| 90 / 91 | `openAirAutoMode` / `closeAirAutoMode` |
| 92 / 93 | `openAirFrontWindowDefogger` / `closeAirFrontWindowDefogger` |
| 94 / 95 | `setAirLeftTemperatureUp` / `Down` |
| 96 / 97 | `setAirWindSpeedUp` / `Down` |
| 98 | `switchAirCirculationMode` |
| 29 | `getAirCondition` |
| 30 | `setAirConditionState` |
| 51 / 52 | `getVehicleState` / `setVehicleState` |
| 86 / 87 | `setSmartCustomModeStatus` / `getSmartCustomModeStatus` |

Форма `Parcel` для метода с объектом-аргументом:

```
writeInterfaceToken("com.qinggan.canbus.ICanBusService")
writeInt(1)                      // флаг присутствия
<объект>.writeToParcel(data, 0)  // VehicleState пишет ordinal(), затем свой int
writeInt(значение)               // параметр метода
```

**Почему сырой `transact`, а не сгенерированный AIDL.** Код транзакции —
константа времени компиляции в сгенерированном Proxy, и она зависит от порядка
объявления методов в исходном `.aidl`. Порядок объявления из дампа не
восстанавливается: `dexdump` печатает поля **по алфавиту**, а не в порядке
объявления. Порядковые номера констант восстановлены отдельно — по порядку
заполнения массива `$VALUES`, см. п. 4.

## 3. Прогрев салона через эти входы не работает

```
setVehicleState(AC_ONE_BUTTON_WARMTH_MODE ord 48, 1)   transact=true
setVehicleState(AC_ONE_BUTTON_WARMTH_MODE ord 48, 2)   transact=true
setAirConditionState(AirConditionState ord 34, 1)      transact=true
```

Все три приняты без исключения и все три кладут кадр в шину:

```
setAirConditionState(34,1) -> 43 10 00 00 7e ...
                              5b 10 08 00 ...     <- CAN ID, ранее не встречавшийся
setVehicleState(48,2)    -> 43 10 00 00 11 ...
setVehicleState(48,1)    -> 43 10 00 00 10 ...
```

Меняется только третий байт: `0x10` / `0x11` / `0x7e`.

**Ни один из них не греет салон.** Поле состояния прогрева так и не появлялось
из шины ни в одном замере. Кнопка прогрева есть в штатном интерфейсе, но на
этой платформе компонент её не исполняет.

Порядковый номер **ordinal 48** проверен независимо с двух сторон по порядку
заполнения `$VALUES` — и в копии AIDL приложения, и в самом сервисе (480
констант, одинаковые соседи 47 и 49). Номер верный.

Вывод: реализация метода на этой платформе не разбирает имя состояния, а просто
переотправляет текущую климат-информацию из кэша настроек, задевая заодно
посторонний бит.

## 4. Порядковые номера — как их читать правильно

`dexdump` печатает поля класса по алфавиту. Энум, объявленный в исходнике
как `AC_BABY_CARE_MODE, AC_HAZE_MODE, AC_ONE_BUTTON_WARMTH_MODE, …`, будет
напечатан именно в таком порядке — это сортировка, а не исходный порядок.
По такому чтению прогрев якобы имеет номер 2.

Правильный источник — массив `$VALUES`, который заполняется инструкциями
`aput-object` с явными индексами:

```
const/16 v1, #int 47
sget-object  AC_HAZE_MODE
aput-object  v2, v0, v1          <- AC_HAZE_MODE = 47
const/16 v1, #int 48
sget-object  AC_ONE_BUTTON_WARMTH_MODE
aput-object  v2, v0, v1          <- AC_ONE_BUTTON_WARMTH_MODE = 48
const/16 v1, #int 49
sget-object  AC_RAPID_COOLING_MODE
```

## 5. Опасность: два разных узла CAN

| Узел | Кто держит | Формат буфера | Код запроса |
|---|---|---|---|
| `/dev/cis_can_mpu` | CAN-сервис | `[canId, len, data...]`, len+2 | литеральная `1` |
| `/dev/cis_mcu_mpu` | carsignal-сервис | `[тип, payload...]`, 2–5 байт | `0x6D00 \| (low & 0x7F)` |

**MCU-узел не умеет греть.** Его протокол декодирован по пяти точкам вызова:
`setLogisticsInfo`, `setNetDiagnosisState`, подсветка, синхронизация времени,
распаковка. Поиск по словарям прогрева даёт только `onHotTestStateChanged` и
`OnACCStateChanged`, а оба — это **приём** состояния, не запрос.

> **Не повторять:** вызов `qg_carsignal_control(0x6D68, <CAN-кадр>)` перезагружает
> мультимедийную систему. Воспроизведено трижды; последняя строка лога перед
> смертью процесса — `INFO about to call cmd=0x6D68`. Причина не установлена:
> ядро не паниковало, crash-логов нет. Формат кадра у MCU-узла другой, и если
> отправить туда CAN-кадр, драйвер читает первый байт как тип сообщения,
> которого не существует.

Отсюда роутинг устроен так: `cis_can_mpu` — приём и отправка CAN-кадров,
`cis_mcu_mpu` — обмен с микроконтроллером. Климат идёт через первый.

## 6. Абсолютного сеттера температуры нет

В интерфейсе есть только `setAirLeftTemperatureUp/Down` (94/95) — относительные
шаги. Чтобы выставить уставку, её приходится «нашагивать», поэтому в
приложении есть снимок состояния до прогрева и возврат по завершении:
без этого голова осталась бы с уставкой, в которую прогрев её сдвинул.

Не исследовано и может содержать абсолютный сеттер: Bundle-варианты
`setVehicleBundleState` (53) и `setAirConditionBundleState` (31).

## 7. Пробуждение

Голова уходит в **suspend-to-RAM**, а не выключается: `/sys/power/state`
содержит `freeze mem`, `uptime` продолжает расти, а при запирании грузится
мгновенно, а не с нуля. Значит будильник RTC будит систему с сохранённой
памятью.

Поэтому используется `setAlarmClock` — самый сильный тип будильника в Android,
и тот, который OEM-прошивки реже всего вырезают. `setExactAndAllowWhileIdle`
на Android 8.1 гарантий в deep sleep не даёт.

**Не проверено:** срабатывает ли будильник, когда голова реально спит.
Это главный открытый вопрос проекта.

## 8. Что осталось непрочитанным

- `setSmartCustomModeStatus(SmartCustomData, int)` код 86 — цикл отправки на
  платформе S31 пишет в лог `setSmartCustomModeStatus air info fail`, то есть
  семейство там живое, а аргумент структурный.
- `getCanRawData(int)` код 33 — сырое чтение шины.
- Смещение float внутри `getAirCondition` не проверено: приложение читает
  температуру по предположению и на всякий случай отбрасывает значения вне
  диапазона −40…+70 °C.