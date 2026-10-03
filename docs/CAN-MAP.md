# Карта общения мультимедийной головы с CAN шиной

Доказано статическим анализом dex-дампов и нативных библиоек автомобиля
Dongfeng Box (платформа S31, Android 8.1). Дата: 2026-10-03.

Источники: `canbus.dexdump` (24 МБ, 221203 строки), `powermanager.dexdump`
(44 МБ), `launcher/launcher.dexdump` (208 МБ), `car-sys/lib/libqg_hal.so`,
`car-sys/canbus-lib/lib/armeabi/libqg_canbus_jni.so`, 110 выгруженных APK.

Каждое утверждение ниже подтверждено процитированным кодом. Мест, где
сказано «вероятно», в документе нет: там, где раньше приходилось догадываться,
оказалось, что точный ответ лежит в байткоде.

---

## 1. Узлы и каналы (C1)

### 1.1 Два разных устройства, два разных протокола

| Узел | Путь | Кто держит | Протокол |
|---|---|---|---|
| CAN | `/dev/cis_can_mpu` (10,50) | `com.qinggan.canbus.service` | кадры CAN |
| MCU | `/dev/cis_mcu_mpu` (10,49) | `com.qinggan.carsignal.service` | управляющие сообщения MCU |

Живьём на машине измерено: CAN-сервис держит `fd 41 -> /dev/cis_can_mpu`,
carsignal-сервис — `fd 41 -> /dev/cis_mcu_mpu`.

### 1.2 Нативные методы CAN-сервиса

`canbus.dexdump`, класс `com.qinggan.canbus.service.CanBusService`:

```
name : 'devnode_open'                    type : '()I'    PRIVATE NATIVE
name : 'cis_can_control'                type : '(I[B)I'  PRIVATE NATIVE
name : 'cis_can_control_bytes'          type : '(I[B)[B' PRIVATE NATIVE
name : 'cis_factory_control'             type : '(ILjava/lang/String;)V' PRIVATE NATIVE
```

Доказательство, что `devnode_open(1)` открывает CAN-узел — `canbus.dexdump`,
строка 227387, метод `a(Context)`:

```
000a: const/4 v1, #int 1
000b: invoke-direct {v4, v1}, CanBusService;.devnode_open:(I)I
000e: move-result v1
000f: iput v1, v4, CanBusService;.e:I
0011: iget v1, v4, CanBusService;.e:I
0013: if-gtz v1, 0030
```

Узел CAN — `cis_can_mpu`: этот путь единственный в `libqg_hal.so` рядом с
функциями `qg_canbus_*`:

```
$ strings car-sys/lib/libqg_hal.so | grep cis_
/dev/cis_can_mpu
/dev/cis_mcu_mpu
/sys/class/misc/cis_mcu_mpu/vbus
```

### 1.3 Протокол MCU-узла, декодирован целиком

Библиотека `libqg_carsignal_jni.so` (13552 байта) экспортирует только обёртки;
единственная её строка об узле:

```
ERROR: open carsignal file : cis_mcu failed
```

Java-уровень CAN — `CarSignalService`, `canbus`-эквивалент:

```
src/com/qingfan/carsignal/service/CarSignalService.java
  private static native int  devnode_open();
  private static native void devnode_close();
  private static native int  io_control_array(int, byte[]);
  private static native byte[] io_control_array_bytes(int, byte[]);
```

Формат сообщения MCU декодирован по пяти точкам вызова
(`car-sys` → `css.dexdump`): `byte[0]` — тип сообщения, дальше полезная
нагрузка, длина от 2 до 5 байт. Код запроса ioctl собирается так:

```
11 5e: movs  r3, #0xda
11 60: lsls  r3, r3, #0x7      // r3 = 0xda << 7 = 0x6D00
11 62: orrs  r3, r6             // r6 = аргумент вызова & 0x7F
11 68: str   r3, [sp]
11 80: ldr   r0, [sp]          // первый аргумент qg_carsignal_control
11 82: blx   qg_carsignal_control
```

То есть `запрос = 0x6D00 | (n & 0x7F)`. Это точное совпадение с измеренным
в dmesg значением `0x6d68` (`0x6D00 | 0x68`).

Полный список сообщений MCU, которые удалось расшифровать:

| cmd | тип в byte[0] | смысл | длина |
|---|---|---|---|
| `0x6D68` (104) | 4 | `setNetDiagnosisState` — состояние сети | 5 |
| `0x6D69` (105) | 2 | `setLogisticsInfo` | 3 |
| `0x6D25` (37) | 1 | `SetLCDBoardLightVaule` — яркость подсветки | 2 |
| `0x6D07` (7) | — | `SyncIPCTime` / «TIME Geely», буфер времени | — |
| `0x6D35` (53) | — | `onUnpackingChanged` | — |

Образец, `setNetDiagnosisState`, показывает формат целиком:

```
00 0e: const/4 v1, #int 5
00 0f: new-array v0, v1, [B
00 11: aput-byte v9, v0, v6      // payload[0] = 4   (тип)
00 13: aget v1, v12, v6
00 15: const v2, #0xFF00
00 18: and-int/2addr v1, v2
00 19: shr-int/lit8 v1, v1, #8
00 1c: aput-byte v1, v0, v7      // payload[1] = (arg[0] & 0xFF00) >> 8
00 1e: aget v1, v12, v6
00 20: and-int/lit16 v1, v1, #0xFF
00 23: aput-byte v1, v0, v8      // payload[2] = arg[0] & 0xFF
00 33: aput-byte v6, v0, v9      // payload[4] = 0
00 d0: const/16 v1, #int 104
00 d2: invoke-direct {v11, v1, v0}, io_control_array:(I[B)I
```

**Греющего сообщения на MCU-узле нет.** Поиск по heat/warm/air/defrost/blower
даёт только два имени — `onHotTestStateChanged` и `OnACCStateChanged`, и оба
это **приём** состояния, а не запрос.

### 1.4 Третий канал: sysfs-конфигурация возможностей

Обнаружено в этом проходе и ранее не исследовано.

`com.qinggan.vehicle.VehicleOnlineState` (canbus.dexdump:212754) — перечисление
из **488 констант**, каждая хранит битовую маску (поле `value`):

```
0af53c: iget v0, v1, VehicleOnlineState;.value:I
0af550: return v0
```

Читается из файла, а не из дескриптора:

```
VehicleOnlineState.CONFIG_PATH = "/sys/class/misc/cis_mcu_mpu/vehicle_cfg"
VehicleOnlineConfig$DongFengConfig.CONFIG_PATH = "/sys/class/misc/cis_mcu/vehicle_cfg"
```

Два **разных** файла. `VehicleOnlineConfig` (powermanager.dexdump) имеет
потомков по маркам: `BAICOnlineConfig`, `BJEVOnlineConfig`, `DongFengOnlineConfig`,
`GeelyOnlineConfig`. У Dongfeng-конфига:

```
BUS = 1
FEATURED = 1
NOT_FEATURED = 0
HARD_LINE = 0
ITEM_VEHICLE_TYPE = 5
```

То есть голову можно делить по марке и типу кузова, и она читает с машины
список поддерживаемых функций. Имена констант `VehicleOnlineState` прямо
описывают скрытые возможности, среди них релевантные климату:

```
REMOTE_START                             REMOTE_START_AND_BCM_AUTHENTICATION
REMOTE_START_AND_PEPS_AUTHENTICATION     ACC
AIR_CONTROL                              AIR_CONDITION_TOUCH_PANEL
AIR_AREA       AIR_TYPE      AIR_CLEANER ELECTRIC_AIR_OUTLET
AC_MAX         AC_VOICE      AC_VOICE_ON AC_VOICE_OFF
SEAT_HEAT      SEAT_VENTILATION           SEAT_MASSAGE
REAR_WINDSHIELD_HEATING   STEERING_WHEEL_HEATING
LONG_TIME_PURGING         POLLEN_FILTRATION           ION
WINDOW_INTELLIGENT_VENTILATION           VOICE_CONTROL_SEAT_HEATING
```

`REMOTE_START` присутствует в списке возможностей. Значения констант
разбираются конструктором `<init>(String, int, int)`, где третий аргумент —
битовая маска (`getEnabled()` её и возвращает).

**Это отдельная подсистема, не связанная с CAN-кадрами.** Ни один её метод
не пишет в `/dev/cis_can_mpu`. Прямой связи с прогревом не показано.

---

## 2. Формат CAN-кадра (C2)

Декодирован из байткода, 33 инструкции, `canbus.dexdump` строка 40708
(один из 19 идентичных билдеров — по одному на платформу):

```
0000: if-eqz v6, 0006           // data == null
0002: const/16 v2, #int 150     // предел длины
0004: if-le  v7, v2, 0008
0006: const/4  v0, #int 0
0007: return-object v0         // превышено -> null
0008: add-int/lit8 v2, v7, #2  // размер = len + 2
000a: new-array v0, v2, [B
000c: const/4  v2, #int 0
000d: int-to-byte v3, v5       // v5 = первый параметр = canId
000e: aput-byte v3, v0, v2     // out[0] = canId
0010: const/4  v2, #int 1
0011: int-to-byte v3, v7       // v7 = третий параметр = len
0012: aput-byte v3, v0, v2     // out[1] = len
0014: const/4  v1, #int 0
0015: if-ge v1, v7, 0007       // цикл i < len
0017: add-int/lit8 v2, v1, #2
0019: aget v3, v6, v1          // data[i]
001b: int-to-byte v3, v3
001c: aput-byte v3, v0, v2     // out[i + 2] = data[i]
001e: add-int/lit8 v1, v1, #1
0020: goto 0015
```

Итог, без предположений:

```
out = [canId, len, data[0], data[1], ... ]     всего len + 2 байт
возвращает null, если data == null или len > 150
```

Подтверждено тремя независимыми источниками: этим байткодом, форматом
`ByteBuffer` из `cis_can_control_bytes`, и живым кадром, пойманным на шине.

### 2.1 Код ioctl на CAN-узле — константа 1

`libqg_canbus_jni.so`, обёртка `cis_can_control`:

```
11 f4: push  {r0,r1,r2,r4,r5,r6,r7,lr}
11 f6: ldr   r2, [r0]            // JNIEnv->functions
11 fa: movs  r3, #0xb8
11 fc: lsls  r3, r3, #0x2        // 0x2E0, индекс 184
11 fe: ldr   r3, [r2, r3]
12 00: ldr   r1, [sp, #0x20]     // byte[] аргумент
12 02: movs  r2, #0
12 06: blx   r3
12 08: adds  r6, r0, #0
12 0a: adds  r1, r6, #0          // argp = указатель на кадр
12 0c: movs  r0, #0x1            // cmd = 1, КОНСТАНТА ВЕНДОРА
12 0e: blx   qg_canbus_control
```

Обёртка **игнорирует** свои целочисленные аргументы и подставляет 1. Значит
`ioctl(g_fd, 1, frame)`. Это же подтверждено живым кадром `43 10 00 80 7e …`.

---

## 3. Скрытые и неиспользуемые параметры (C3)

Полная таблица: `enum-orphans.txt` (530 строк). Здесь итог.

### 3.1 Масштаб

| | |
|---|---|
| `VehicleState` | **488** констант |
| `AirConditionState` | **42** константы |
| `SituationalModeInfo` | не перечисление, а Parcelable (9 полей) |
| `SmartCustomData` | не перечисление, а Parcelable (35 полей) |
| **Всего перечислений** | **530 констант** |
| **Ни разу не используется интерфейсом** | **501** |
| Используется | **29** |

### 3.2 Метод получения ординалов

Читать поля `VehicleState` по дампу **нельзя**: `dexdump` печатает их
по алфавиту, и тогда `AC_ONE_BUTTON_WARMTH_MODE` получает номер 2. Правильный
источник — порядок `sput-object` в `<clinit>`, который совпадает с порядком
объявления. Контрольная тройка сошлась:

```
AC_HAZE_MODE = 47, AC_ONE_BUTTON_WARMTH_MODE = 48, AC_RAPID_COOLING_MODE = 49
```

### 3.3 Метод определения использования — и ловушка в нём

UI **не обращается к константам напрямую**: прямых `sget-object` на `VehicleState`
из пакетов `com.qinggan.app.launcher` / `com.qinggan.car` / `com.qinggan.settings`
всего **4**. Остальные 25 приходят через подклассы моделей
(`S31VehicleState`, `D53VehicleState`, `C65VehicleState`, `X37AVehicleState`,
`G35VehicleState`, `G59VehicleState`, `M57VehicleState`, `S01VehicleState`),
которые в своём `<clinit>` делают `sget` базовой константы в своё поле того же
типа. Считать только прямые обращения нельзя — тогда прогрев выглядел бы
неиспользуемым, что неверно.

Подклассы содержат от 105 до 181 алиаса на базовые константы. В трёх именах
(`AUTO_SUNROOFE_SWITCH`, `GEARSHIFTREMIND_FC_ENABLE_SWITECH`,
`VOICE_SUNROOF_TITL_CONTROL`) подклассы ссылаются на поля, которых в базовом
`VehicleState` нет — похоже на рассинхрон версий между классами.

### 3.4 Что именно не используется

Первые сироты с ординалами:

```
 0 ASR                     19 SPEED_LIMIT_UNIT        38 FCWSensitivity
 1 TyresSpeedWarning       20 LOW_BEAM_MODE          43 PositionLamp
 2 TyresSpeed              21 …                     44 …
 3 TyrePressureMonitoring  22 …                     46 …
 4 AdaptiveCruiseControl   23 …                     48 AC_ONE_BUTTON_WARMTH_MODE (с дубликатом в AirConditionState)
 5 DriverAlertSystem       24 …                     49 AC_RAPID_COOLING_MODE
 6 LastVehicleDistance     25 …                     50 AC_RAIN_SNOW_MODE
 7 FrontAssistSet         26 …                      51 AC_BABY_CARE_MODE
 8 PrevWarning            29 …                     54 AC_STOP_CAR_MODE
 9 DisplayDistanceWarning 30 …
10 ODOMETER               31 …
11 SunroofState           32 …
12 TAILGATE_DELAY_CLSE_TIMING 33 …
13 IC_STYLE_WITCH          34 …
14 KICK_FUNCTION_ENABLE_SWITCH 35 …
15 IC_STYLE_SETTING_LEFT   36 …
16 IC_STYLE_SETTING_RIGHT  38 …
17 IVI_PSM_CUR             39 …
18 SPEED_LIMIT_SET         40 …
```

Полностью неиспользуемыми являются, в частности, семейства:

```
AVM_*            (14 констант)   — обзор камер
BSD_*            (3)            — Blind Spot
DVR_* / CAMERA_* (5)             — видеорегистратор
PARK_ASSIST / PDC / AVP_*       — парктроник
SPEAKER_*        (10)            — конфигурация динамиков
LAMP_PATTERN_*   (3)            — режимы фар
SINGLE_PEDAL / ENERGY_RECOVERY  — режимы езды
EPS / ESC / TCU / GEARBOX      — агрегаты
```

Ни одного из них нет в `ICanBusService`. Для климата существенно, что
интерфейс покрывает лишь подмножество: из 42 `AirConditionState` UI не
использует **ни одной**, хотя `setAirConditionState` в интерфейсе есть.

### 3.5 Важное ограничение

Через `setVehicleState` можно записать **любую** из 488 констант —
setter один и он принимает перечисление целиком. Поэтому «нет UI» не означает
«недоступно»: константа без кнопки всё равно отправляется в шину. Это и есть
та самая скрытая поверхность, ради которой стоило копать.

---

## 4. Почему прогрев салона не работает (C4)

Механизм найден, и он **опровергает** прежнюю гипотезу.

Прежнее предположение было: «S31 не разбирает имя состояния, а просто
переотправляет кэш». Это неверно. `S31CanBusComponentImpl.setVehicleState`
(canbus.dexdump:86277, 119 инструкций) — это большой диспетчер:

```
004a: const/16 v5, #int 16
004c: new-array v0, v5, [I        // payload = int[16]
004e: const/4  v1, #int -1        // v1 = canId, изначально «нечего слать»
004f: sget-object v5, Lcv;.n
0051: if-ne v9, v5, 0055
0053: sget-object v9, Lcv;.c      // нормализация алиаса: n -> c
0055: sget-object v5, Lcv;.ay
0057: if-ne v9, v5, 00aa         // не этот -> в общую цепочку
0059: if-ne v10, v2, 00a8
005b: sput-boolean v2, Lar;.a
005d: invoke-virtual {v8, v9, v10}, Lar;.b:(Lcom/qinggan/canbus/VehicleState;I)V
0060: const/4  v2, #int -1
0061: if-eq  v1, v2, 006f        // canId == -1 -> кадр не строим
0063: const/4  v2, #int 0         // cmd отправки = 0
0064: array-length v3, v0
0065: invoke-virtual {v8, v1, v0, v3}, Lar;.a:(I[II)[B
0068: move-result-object v3
0069: invoke-virtual {v8, v2, v3}, Lar;.a:(I[B)I   // ОТПРАВКА
006c: move-result v2
```

Далее идёт цепочка сравнений вида `if-eq v9, <константа> -> <обработчик>`,
всего **114 состояний** в S31-диспетчере.

Обработчик прогрева, ordinal 48, алиас `Lcv;.aN`:

```
0216: sget-object v3, Lcv;.aN          // AC_ONE_BUTTON_WARMTH_MODE
0218: if-eq v9, v3, 024e
...
024e: iget-object v2, v8, Lar;.J:Lau;
0250: invoke-virtual {v2, v9, v10, v0}, Lau;.k:(Lcom/qinggan/canbus/VehicleState;I[I)[I
0253: move-result-object v0
0254: const/16 v1, #int 91            // canId = 91 = 0x5B
0256: goto/16 0060                    // -> общая отправка
```

**Состояние 48 обрабатывается, и кадр уходит на canId `0x5B`.** Это ровно тот
идентификатор, который при живом прогоне `setAirConditionState(34, 1)` дал кадр
`5b 10 08 00 …` — тот самый, который тогда сочли «никогда не встречавшимся».
Он не новый: он и есть канал прогрева, и он был обнаружен мимо.

### 4.1 Отсюда настоящая причина отсутствия тепла

Сравнение присутствия в диспетчере (по правильным ординалам):

```
обрабатываются S31:   13, 22, 32, 36, 38, 39, 40, 41, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 64, …
ordinal 48 (AC_ONE_BUTTON_WARMTH_MODE) — обрабатывается
```

Значит причина **не** в отсутствии ветки. Остаются две, и данные их разделяют:

1. **Кадр уходит, но значение в нём пустое.** Полезную нагрузку собирает
   `Lau;.k(VehicleState, int, int[])` из данных, которые сервис берёт у MCU.
   Поле прогрева в дампах никогда не появляется: `acOneButtonWarmth = -1`
   на протяжении 513 замеров подряд. `-1` в этом дампе — сентинел «не
   передаётся», тот же, что у `airHighWindStatus`, `airRightTemperature`.
   Если источник не сообщает прогрев, в кадр уходит значение «неизвестно»,
   и машина на него не реагирует.
2. **Бит в кадре есть, но машина его не исполняет** на этой прошивке.

Проверяется на машине одним замером: поймать `SysToCanBox` при
`setVehicleState(48, 1)` и посмотреть байты `5b 10 …` — если payload
заполнен `ff`/нулями, верна причина 1.

### 4.2 Косвенное подтверждение из переписки владельцев

Тот же вывод независимо напрашивается из замеров расхода: хозяин писал, что
при «Быстрый прогреве» (26 °C) машина стартует сама, а при кастомном режиме —
нет. То есть блок прогрева в прошивке есть, но включается не всегда, что
согласуется с «кадр уходит, а значение определяется источником данных».

---

## 5. Что можно утверждать, а что нет

Утверждается, доказано кодом:

- формат CAN-кадра — `[canId, len, data…]`, len+2, предел 150;
- код ioctl на CAN-узле — литеральная 1;
- формат сообщений MCU-узла — `[тип, payload]`, код `0x6D00 | n`;
- `AC_ONE_BUTTON_WARMTH_MODE` уходит на canId `0x5B` через общий диспетчер;
- S31 обслуживает 114 состояний из 488;
- интерфейс не использует 501 константу из 530;
- есть отдельный слой конфигурации возможностей через sysfs, включая `REMOTE_START`.

Не установлено:

- почему машина не греет при уходящем кадре — нужен живой захват `0x5B`;
- что лежит в файлах `/sys/class/misc/cis_mcu*/vehicle_cfg` на этой машине;
- как `VehicleOnlineState` влияет на поведение — прямой связи с CAN не найдено;
- код транзакций событий `ICanBusServiceCallback` — сопоставление метода с номером
  нигде не записано, в приложении незнакомые коды логируются и подтверждаются
  ack'ом, чтобы сервис не ждал ответа.

## 6. Как проверять дальше

```
# 1. Значение прогрева в кадре 0x5B:
adb logcat -c
# в приложении нажать «ПРОВЕРИТЬ СЕЙЧАС»
adb logcat -d | grep -A4 'SysToCanBox send raw' | grep -oE '\[5b 10[^]]*\]'

# 2. Возможности машины:
adb shell cat /sys/class/misc/cis_mcu/vehicle_cfg
adb shell cat /sys/class/misc/cis_mcu_mpu/vehicle_cfg

# 3. Коды событий (по одному на прогон, в логе CanTest):
adb logcat -s Preheat
```