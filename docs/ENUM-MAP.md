# Карта параметров: 530 констант, 459 скрытых

Полный перечень управляющих констант CAN-сервиса с разбором, что они делают.
Полная таблица с ординалами: `ENUM-TABLE.txt`.

Метод и его грабли описаны в `CAN-MAP.md`, здесь только смысл.

---

## 1. Сначала — как это устроено, иначе таблицу не прочитать

**Одна большая плоскость, а не карта сообщений.** `VehicleState` — 488 констант подряд,
без пропусков. Ординалы не соответствуют CAN-сообщениям: `AC_*` раскиданы по
всей шкале от 0 до 443. Поэтому группировать можно **только по именам**.

**Второе перечисление — настоящий интерфейс климата.** `AirConditionState` — всего
42 константы подряд, с 0 по 41, и это читаемый список команд климата. По нему видно,
что именно машина умеет и чего вендер не сделал.

**29 из 488 используются интерфейсом, 459 — нет.** И это не случайные дыры: 29
используемых разбросаны по всей шкале (`*` в таблице ниже). То есть интерфейс
выбирает команды отовсюду, а не из одного блока.

**При этом отправить можно любую.** `setVehicleState` принимает перечисление целиком,
поэтому все 459 «скрытых» параметров отправляются в шину прямо из нашего приложения —
одним вызовом, без всякого взлома.

---

## 2. AirConditionState: 42 команды климата

Весь перечень, в порядке объявления. Ни одна из них не используется интерфейсом.

| № | Константа | Что это |
|---|---|---|
| 0 | `AC_BLOWER` | вентилятор |
| 1 | `HVAC_VD_MODE` | режим вентиляции/отладки |
| 2 | `AC_LEFT_TEMP` | температура слева |
| 3 | `AC_RIGHT_TEMP` | температура справа |
| 4 | `AC_SWITCH` | **климат вкл/выкл** |
| 5 | `AC_MAX_SWITCH` | максимальный холод/тепло |
| 6 | `AC_AUTO` | авторежим |
| 7 | `AC_POWER_SWITCH` | питание климата |
| 8 | `AC_FRONT_DEFROST_SWITCH` | обогрев лобового |
| 9 | `AC_REAR_DEFROST_SWITCH` | обогрев заднего |
| 10 | `AC_RECIRC_AIR` | рециркуляция |
| 11 | `AC_DUAL` | двухзонный режим |
| 12 | `AC_FLOW_MODE` | направление потока |
| 13 | `AC_TEMP_DEM` | **задание температуры** |
| 14 | `AC_HEAT_SWITCH` | **обогрев — прямой выключатель нагрева** |
| 15 | `AC_TEMP_DEM_ADD` | температура + |
| 16 | `AC_TEMP_DEM_DEC` | температура − |
| 17 | `AC_BLOWER_DEM_ADD` | скорость + |
| 18 | `AC_BLOWER_DEM_DEC` | скорость − |
| 19 | `AC_ION_SWITCH` | ионизация |
| 20 | `AC_PM_SWITCH` | фильтр частиц |
| 21 | `AC_AQS_SWITCH` | контроль качества воздуха |
| 22 | `AC_TEMP_OUTCAR` | наружная температура |
| 23 | `AC_PM2_5` | PM2.5 |
| 24 | `AC_RUNNING_STATE` | признак работы |
| 25–32 | `AC_COMBINATION_FUNCTION_1…8` | **восемь безымянных комбинированных функций** |
| 33 | `AC_RAPID_COOLING_MODE` | быстрое охлаждение |
| **34** | **`AC_ONE_BUTTON_WARMTH_MODE`** | «одно касание = тепло» — тот, что мы пробовали |
| 35 | `AC_HAZE_MODE` | режим дымки |
| 36 | `AC_BABY_CARE_MODE` | режим детского салона |
| 37 | `AC_AIR_CLEANER` | очистка воздуха |
| 38 | `AC_RAIN_SNOW_MODE` | дождь/снег |
| 39 | `AC_SMOKING_MODE` | режим курения |
| 40 | `AC_STOP_CAR_MODE` | остановка |
| 41 | `AC_REST_MODE` | отдых |

### Что из этого следует

**`AC_HEAT_SWITCH` (14) мы ни разу не вызывали.** Я пробовал только
`AC_ONE_BUTTON_WARMTH_MODE` (34) — «режим одной кнопки», а это отдельная штука.
Стоит проверить первым делом: это прямой переключатель нагрева безо всякой
магии «по касанию».

**Температура ставится напрямую.** `AC_TEMP_DEM` (13) — задание уставки числом,
а мы всё это время шагали `Up/Down` вслепую, потому что метода с абсолютным
значением в интерфейсе не нашли. Он есть — просто в другом перечислении.

**Восемь комбинированных функций безымянные.** `AC_COMBINATION_FUNCTION_1…8`
с ordinal 25 по 32. Ни одна не имеет читаемого имени ни здесь, ни в
`AirCondition`. Что они делают — не сказано нигде в коде; это кандидаты на
режимы, о которых вендор не документировал ничего, в том числе, возможно,
на прогрев по расписанию.

---

## 3. VehicleState: блоки скрытых параметров

Ниже — максимальные блоки подряд идущих **неиспользуемых** параметров с
подписью домена по фактическим именам. Разрывы в списках — это те самые 29
используемых, они выпадают и рвут блок.

### [0..37] — ADAS, парктроник, кузовные функции (38)

```
 0 ASR                          19 AutomaticHeadlightControl
 1 TyresSpeedWarning             20 LaneChangeFlash
 2 TyresSpeed                    21 InstrumentSwitchLighting
 3 TyrePressureMonitoring        22 ComingHomeFunction
 4 AdaptiveCruiseControlSystem   23 LeavingHomeFunction
 5 DriverAlertSystem             24 TravelMode
 6 LastVehicleDistance           25 DoorAmbientLight
 7 FrontAssistSet                26 FootWellLight
 8 PrevWarning                   27 DynamicBigLight
 9 DisplayDistanceWarning        28 DynamicBigLightAssistance
10 LaneKeepingAssist             29 SynchronousAdjustment
11 TravelProgramme               30 LowerWhileReversing
12 VehicleDistance               31 AutomaticWipingInRain
13 ParkingAutoActive             32 RearWindowWipingInReverseGear
14 ParkingFrontVolume            33 ParkingOverlapMirror
15 ParkingFrontTone              34 ConvenienceOpening
16 ParkingBackVolume             35 DoorUnlocking
17 ParkingBackTone               36 SpeedLimit
18 LightOpenedTime               37 AutomaticLocking
```

Преимущественно ADAS и парктроник. Обращает внимание `TravelMode` (24) и
`TravelProgramme` (11) — режимы езды, привязанные к маршруту.

### [81..153] — самый крупный блок, 73 параметра

Смешанный, но подразделяется на шесть смысловых слоёв:

```
 81-84   EPS / передача даты и времени          EPSFunction, EPSModeSelection, SendDateTimetoICU*
 85-89   руль и воздух в салоне                 STEERING_WHEEL_HEATING_SWITCH, LEFT/RIGHT_SEAT_VALICATION_LEVEL,
                                                 CLEAN_AIR_ACTIVE_SWITCH, CLEAN_AIR_LEVEL
 90-100  кнопки и статусы                       OPS_MUTE_SWITCH, OPS_SURFACE_POP_SWITCH,
                                                 OPS_DRAG_CAR_CONNECTION_SWITCH, HEAD_LIGHT_STATUS,
                                                 RVC_REVER_LIGHT_SWITCH, *_OPERABILITY, DAY_LIGHT_SWITCH
105-112  зарядка по расписанию!                 OBC_HOUR_START_SETTING, OBC_MINUTE_START_SETTING,
                                                 OBC_HOUR_END_SETTING, OBC_MINUTE_END_SETTING,
                                                 OBC_CANCEL_SIGNAL_SWITCH, OBC_SET_SIGNAL_SWITCH,
                                                 USER_REMOTE_BOOT_FEED_SWITCH, LOW_POWER_SET_SWITCH
113-127  энергия, BSD, сигнализация             BMS_BATT_SOC_STATUS, ENERGY_RECOVERY_GEAR,
                                                 ENERGY_CONSUM_HISTORY, ENERGY_FLOW, BSD_SYSTEM_SWITCH,
                                                 MCU_ALARM_LEVEL_STATUS, SYS_LANGUAGE, LOW_POWER_TONE_SWITCH
128-141  педали и помощь водителю               SIGNAL_PEDL_SWITCH, WARM_SWITCH, VEHICLE_DOOR_CONTROL,
                                                 VEHICLE_IGNITION_SWITCH, SIGNAL_PEDL_STATUS, WARM_STATUS,
                                                 LKS_LANE_AID_TYPE_REQ, PGS_SWITCH, PEDE_PROT_SWITCH,
                                                 HAM_REQ_SWITCH, TSR_REQ_SWITCH, LKSStatus, TSRStatus
142-153  окна и багажник                         *_WINDOW_CONTROL, ALL_WINDOW_CONTROL,
                                                 SUNROOF_TITL_CONTROL, VEHICLE_TUNK_CONTROL, HIGH_BEAM
```

**`OBC_HOUR_START_SETTING` … `OBC_MINUTE_END_SETTING` (105–108) — это
расписание зарядки по часам, начало и конец.** Рядом `USER_REMOTE_BOOT_FEED_SWITCH`
(111) — удалённый запуск с обратной связью. То есть вендор уже умеет
будить машину по расписанию для зарядки; для климата он сделал то же самое
отдельными константами в `AirConditionState`. Это лучшая находка из блока.

**`WARM_SWITCH` (129) и `WARM_STATUS` (133)** — прямое управление нагревом.
Мы их не пробовали.

**`VEHICLE_IGNITION_SWITCH` (131)** — управление зажиганием.

### [231..295] — крупный блок, 65 параметров

```
231-237  свет готовности, ошибки BCM, сиденья   READY_LAMP, ONE_KM_FLAG, BCM_RCM_ERROR,
                                                 COMBINATION_LIGHT_SWITCH, ALL_SEAT_HEATING_SWITCH,
                                                 ALL_SEAT_VENTILATION_SWITCH, FRONT_SEAT_HEATING_BATTERY_STATUS
238-244  физические кнопки и калибровка камер    AVM_CALIBRATE_START, PHYSICAL_KEY_*, LITTLE_LIGHT_STATUS
245-251  багажник, шторка, свет                 TAILGATE_OPEN/CLOSE_CTRL_REQ, SEAT_MEMORY_*_REQ,
                                                 SUNSHADING_CURTAIN_REQ, FOG_LIGHT, WARNING_LIGHT
252-263  адаптивный круиз, регистратор, сброс    ADAPTIVE_CRUISE_CONTROL_SWITCH, CANBUS_LOG_ENABLE,
                                                 DVR_PASSWORD_RESET, IACC_OR_ACC_*, COMBINATION_VEHICLE_REQ1-3,
                                                 MPU_SET_WEATHER, FLK_SYSTEM_SWITCH, VEHICLE_RESET
264-277  сиденья и режимы езды                 SEAT_POSITION_CHANGE, SET_SEAT_USERACCOUNT, ECO_SWITCH,
                                                 SPORT_MODE_SWITCH, SEAT_MASSAGE_*_REQ, SEAT_VENTILATION_AUTO_OPEN_SWITCH,
                                                 DRIVING_MODE_MEMORY_SWITCH, SEAT_MASSAGE_KEY_FEEDBACK,
                                                 TREND_WELCOME_*, APA_SWITCH, SEAT_VENTILATE_AUTO_SWITCH, ISA_MODE
278-293  КЛИМАТ — направление потока воздуха    LDW_WARNING_MODE, AIR_OUTLET_MAX_REQ,
                                                 AIR_DRIVER_SEAT_OUTLET_REQ, AIR_PASSENGER_SEAT_OUTLET_REQ,
                                                 AIR_DRIVER_SEAT_REM_REQ, AIR_PASSENGER_SEAT_REM_REQ,
                                                 AIR_LEFT_LR_POS_REQ, AIR_LEFT_UD_POS_REQ,
                                                 AIR_LEFT_MID_LR_POS_REQ, AIR_LEFT_MID_UD_POS_REQ,
                                                 AIR_RIGHT_MID_LR_POS_REQ, AIR_RIGHT_MID_UD_POS_REQ,
                                                 AIR_RIGHT_LR_POS_REQ, AIR_RIGHT_UD_POS_REQ,
                                                 MFTS_AC_MAX_AC, MFTS_AC_OUTLET_AUTO
294-295  окно и массаж                           ADP_POPUP_WINDOW_REQUEST, MASSAGE_SAFE_MODE_SWITCH
```

**Константы 279–291 — 13 параметров положения заслонок воздуха**: отдельно
для водителя, пассажира, левого-правого и переднего-заднего края. Это управление
распределением потока, то есть «куда дуть». Плюс `MFTS_AC_MAX_AC` и
`MFTS_AC_OUTLET_AUTO`.

### [314..377] — 64 параметра, почти всё мультимедиа

```
314-317  режимы экрана и звука                   MP5_VBC_MODE, MP5_BBINCAR_MODE, MP5_MUTE, MP5_SURROUND_MODE
318-328  система контроля полосы и камеры        CWP_OPERATING_STATUS, CWP_FAILURE_STATUS, CWP_RD_*,
                                                 BSD_CALIBRATE, BSD_CALIBRATE_RESULT_*, MP5_REQ_AVM,
                                                 AVM_LOG_STATUS, AVM_FAULT
329-337  система усталости водителя (DMS)        MP5_DMS_SYSTEM_ENABLE, EYEFATIGUE_MONITOR, SENSITY,
                                                 MONITOR_LONGTIME_DRIVE, SIDE_VIEW, USING_PHONE,
                                                 SOMKING, DRINK_WATER, USERACCOUNT
338-346  предупреждения о выезде и перестроении MP5_BSD_LCW_SWITCH, MP5_JA_SWITCH, MP5_FCTA_SWITCH,
                                                 MP5_TLC_MODE, MP5_LSS_MODE, MP5_DOW_SWITCH,
                                                 MP5_RCTA_SWITCH, MP5_MULTIMODE_SWITCH, MP5_ESA_SWITCH
347-361  парктроник ENM и AVM                   ENM_PARKING_ASSIST_*, AVM_*_SET_REQUEST,
                                                 AVM_VIEW_MODE_SET_STATUS, FIRST_START_TO_SPRING_AVM_SET_REQUEST
362-369  интеллектный режим, моменты            MP5_INTELLIGENT_SWITCH/MODE/TORQUE_COEF,
                                                 ECU_INTELLIGENT_TORQUE_COEF, DRIVING_MODE,
                                                 DRIVING_MODE_STATE
370-377  яркость в ночи, EPS, фары              VEHICLE_AMBIENTLIGHTBRIGHTNESS_LEVEL_NIGHT,
                                                 MP5_SELECTED_EPS_MODE, MP5_*_LAMP_PATTERN_SWITCH, MP5_ASD_*
```

**`DMS_MONITOR_SOMKING` (335) — система усталости умеет детектировать курение.**
Рядом в этом же блоке `DRINK_WATER`, `LONGTIME_DRIVE`, `SIDE_VIEW`, `USING_PHONE`.

### [379..402] — экраны, климат с клавиш, заряд (24)

```
379-380  спорт и музыка                         MP5_SPORT_MODE_SWITCH, MP5_AL_RELATED_MUSIC_SWITCH
381-385  КЛИМАТ — кнопки на руле и панели       MFTS_AC_KEY_AUTO, MFTS_AC_KEY_OFF,
                                                 MFTS_AC_KEY_DEF_MAX, MFTS_AC_KEY_RDEF, MFTS_AC_KEY_OUTLET_AUTO
386-395  приветствие, зеркала, навигация       MP5_AL_WELCOME_SWITCH, MP5_COURTESY_LIGHT_SWTICH,
                                                 SENSING_TAILGATE_FUNCTION, MP5_RFOGLIGHT_SWITCH,
                                                 IVI_*_REAR_MIRROR_ADJUST_*, MP5_SELECTED_DRIVING_MODE, MP5_DAB_SWITCH
397-402  багажник, фары, пуск, климат           MP5_TAILGATEHEIGHT, FRLOWBEAMADJSW, WELCOME_UNLOCK_WITCH,
                                                 EMS_CREDIT_VHE_LIMP_ST, FORBID_VHE_START_EN_ST, HOT_MAX_AC
```

### [407..487] — зарядка, тяга, навигация, диагностика (60)

```
407-420  климат и зарядка                       AUTOINTWIND, WIPER_MAINTAIN_ST, SOC_CALCULATE,
                                                 DOMELIGHT_TOGE, CRUISRANGE_DISPLAY, RESERVED_CHRGSOC_TARGET,
                                                 BATTERY_TYPE, IMMEDIATE_CHRG_REQ, RESERVED_CHRG_STOPREQ,
                                                 CHRGCURRENT_SET, GUNINSERT_KEEPWARM_SETSW, DISCHRGST_V2L,
                                                 DISCHRG_SOCTARGET, BATTSOC_STATUS
421-429  поиск, замки, приветствие              MP5_SERCHCAR(_VOICE), MP5_UNLOCKSW(_VOICE),
                                                 MP5_LOCKSW(_VOICE), MP5_SAYHI_VOICE, MP5_ACOUSTICS_STOP_REQ
430-439  тяга, зарядка                           PDCU_CHRG_CNCTST, PDCU_DISCHRG_CNCTST_V2L,
                                                 IVI_TRACTION*, PDCU_TRACTIONALARM/REQFB, PDCU_CHRGST,
                                                 IVI_CHARGGUNUNLOCKREQ, MP5_DISCHRGSTOPREQ
440-443  ремни безопасности, отдых               DRIVER_BELTSWITCH, MP5_SEAT_REST_MODE,
                                                 MP5_SEAT_ADJ_POPUP, AC_REST_MODE
444-453  подушки, статус систем, ESC            AIRBAG_FAILSTS, ISASTATUS, LKA/LDW_STATUS_DISPLAY,
                                                 AEBFAULT_STATUS, IHBC_FUCTION_STATUS, SYSFAULTWARN,
                                                 WARNINGLAMPST, ESCALARMSIG, ESC_EBDALARMSIG
454-462  капот, разрешение езды, двигатель      ENGHOODUNLOCKWARMING, DRIVING_PERMIT, OTA_MODE_REQ,
                                                 BCM_CAR_MODE, OTA_ESTIMATED_UPGRADETIME,
                                                 PEPS_AUTHRESULT_RESP, FEUL_CAP_ST, ENGINE_START_MODE,
                                                 DRIVING_PERMIT_STATUS
463-483  ассистент, навигация, ограничения     MP5_ISA_SEVICE*, MP5_NAV*, MP5_NAV_SPEEDLIMIT_V1..V4,
                                                 MP5_EXPVEHLANGUAGESET, MP5_ISALIMCHG_SOUNDSW,
                                                 MP5_ISAWARNING_SOUNDSW, DMS_DROWSNS_LV,
                                                 DMS_DISTRCTN_LV, MP5_DROWSNS_SW, MP5_DISTRCTN_SW
484-487  телематика и батарея                   TBOX_RESERVED_CHRGST, BATTERY_HEALTH,
                                                 PASSENGER_AIRBAG, STEERING_WHEEL_HEATING
```

**Обращают внимание `GUNINSERT_KEEPWARM_SETSW` (417)** — «удержание питата
при вставке зарядного пистолета», и **`FORBID_VHE_START_EN_ST` (401)** —
«запрет запуска включён». То есть пуск двигателя можно частично запретить, и
это отдельная сущность, а не просто флаг.

---

## 4. Что стоит проверить на машине, по убыванию ожидания

1. **`AC_HEAT_SWITCH` (14)** — прямой переключатель нагрева, не пробовался ни разу.
2. **`AC_TEMP_DEM` (13)** — уставка числом, вместо шагов `Up/Down`.
3. **`AC_COMBINATION_FUNCTION_1…8` (25–32)** — восемь безымянных функций;
   единственный способ узнать их смысл — вызвать и посмотреть на реакцию.
4. **`WARM_SWITCH` (129) и `WARM_STATUS` (133)** — прямое управление нагревом
   в `VehicleState`.
5. **`VehicleState` 284–291** — 13 положений заслонок, управление распределением потока.
6. **`MFTS_AC_KEY_*` (381–385)** — пять команд климата с клавиш руля.

## 5. О чём эти данные не говорят

Названия констант — это идентификаторы вендора, а не документация. По
`AC_COMBINATION_FUNCTION_1` нельзя понять назначение. Порядок ординалов в
`VehicleState` ни к чему не привязан: это плоский перечень команд и статусов,
а не раскладка по сообщениям CAN — проверено, `AC_*` раскиданы по всей шкале
0..443.

Полное отсутствие интерфейса у `AirConditionState` ничего не значит: все 42
команды отправляются через `setAirConditionState`, то есть доступны из нашего
приложения без всяких прав.