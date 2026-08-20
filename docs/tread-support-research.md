# Peloton Tread support — reverse-engineering research & implementation brief

**Status:** Research complete. Production implementation and tests exist on branch
`feature/tread-support` (PR #2) and have been verified end-to-end on a real Peloton
Tread: device detection, the safe binder, live speed + incline on the HUD, and the
FTMS Treadmill Data characteristic (0x2ACD) confirmed via nRF Connect. This document
was the input for implementing Tread support in grupetto (`com.spop.poverlay`).

**Audience:** an LLM (or engineer) implementing the feature. Read this whole file
before writing code. Everything marked **CONFIRMED (hardware)** was verified on a
real Peloton Tread; everything marked **CONFIRMED (code)** was read from decompiled
Peloton system APKs; **INFERRED** and **PROVISIONAL** items still need care.

Date of investigation: 2026-08-19. Hardware used: one Peloton Tread and one Peloton
Bike (Gen 1), both owned by the maintainer.

---

## 0. TL;DR for the implementer

- The Tread exposes live metrics through the **same** on-tablet system service the
  bikes use: `com.onepeloton.affernetservice`, via a different AIDL action
  **`com.onepeloton.affernetservice.ITreadInterface`**. grupetto's existing binder
  pattern (see `sensor/v1new/`) is the correct template — the Tread is
  **callback-based like the Gen-1 bike**, not poll-based like the Bike+.
- Bind, register an `ITreadCallback`, receive a `TreadData` parcel ~20 Hz. Parse
  76 fields (table in §5). **`speed` and `incline` are integers, divide by 10**;
  speed is **mph**, incline is **percent grade**. All four scalings were confirmed
  on hardware (§6).
- The service is `exported=true` with **no permission** and **no caller checks** —
  an unsigned third-party app binds and reads successfully under enforcing SELinux.
  This was proven on the device with a throwaway probe.
- **Safety:** this is a motorized treadmill. `ITreadInterface` contains write/motion
  transactions (`setSpeed`=45, `setIncline`=46, `enableTread`=54, calibration
  55/56/57, etc.). grupetto must call **only** the read getters and the
  callback-lifecycle transactions. Hardcode an allowlist. See §7 — this is not
  optional.
- **There is no anti-tampering.** But there IS a fragile pin-lock-owner watchdog
  that can *disable the belt* if the callback registry loses all four privileged
  Peloton clients. Do not register with a cookie string that collides with a
  Peloton package name. See §7.3.
- **`Build.MODEL` does NOT identify a Tread.** `PLTN-TTR01` is the shared tablet
  used across Bike+, Tread, and Row. Detect the Tread another way (§8).
- FTMS output needs the **Treadmill Data characteristic `0x2ACD`**, which grupetto
  does not implement yet. The feature bits already exist in
  `FitnessMachineConstants.kt`. The exact `0x2ACD` byte layout in §9 is
  **PROVISIONAL** — verify against the Bluetooth SIG FTMS spec before shipping.

---

## 1. Device facts (CONFIRMED hardware)

| Property | Value |
|---|---|
| `ro.product.model` | `PLTN-TTR01` (**shared** across Bike+/Tread/Row — not a Tread ID) |
| `ro.product.brand` | `Peloton` |
| Board / ODM codename | `topaz` |
| Android version | 10 (SDK 29) |
| Build | `Peloton/TTR01/TTR01:10/QT.250804.A/269:user/release-keys` |
| Sensor board | **Prism**, USB VID `0x317E` PID `0xA004` (`Peloton Prism`) |
| `persist.sys.peloton_active` | `true` |
| affernetservice version | `2.4.508` (versionCode 2040508), sharedUserId `android.uid.system` |
| Display | 1920x1080, density 240 |
| SELinux | **Enforcing** (`getenforce`), despite a permissive kernel cmdline flag |

The Prism variant matters: feature bits differ between PRISM (`0xA004`), PRISM_L
(`0xA00E`), PRISM_B (`0xA00F`). This unit is base **PRISM**. Tread+ is a different
platform ("Aurora", `IAuroraInterface`) — out of scope here.

Reference bike used for the control experiment: Bike Gen 1, model `PLTN-RB1VQ`,
Android 11 (SDK 30), affernetservice **3.0.1**. (Note the bike runs a *newer*
affernetservice than the tread.)

---

## 2. How to connect via adb (reproducible)

The Tread runs Android 10 → no "Wireless debugging"/pairing UI. Route is
**USB then `adb tcpip`**:

1. Enable Developer Options → USB debugging on the tread.
2. USB cable tread→PC. On Windows/WSL2 the device enumerates as an ADB interface
   under `winusb.inf`; use platform-tools 34+ (`adb pair` unneeded here).
3. `adb tcpip 5555`, then `adb connect <tread-ip>:5555`. Verify with
   `adb -s <ip>:5555 shell getprop ro.product.model`.
4. **`adb tcpip` does NOT survive reboot** — port 5555 is refused after a restart
   and the cable is needed again for ~30s to re-enable. (The Bike, on Android 11,
   *can* use persistent wireless-debugging pairing; the Tread cannot.)

Pulling files with a Windows `adb.exe` from WSL: pull to a `/mnt/c/...` path, not a
WSL `/tmp` path (the Windows binary can't write WSL paths). **Pull before
uninstalling** — uninstall wipes `/sdcard/Android/data/<pkg>/`.

---

## 3. Two data paths — pick the low-level one

There are two unprotected, exported services that expose Tread metrics.

### Path A — `affernetservice` / `ITreadInterface` (RECOMMENDED)

- Raw hardware telemetry, ~20 Hz, **available whenever the tread is powered** —
  no workout, no unlock required (CONFIRMED hardware: data streamed with
  `treadLocked=true` and no active class).
- Same service family grupetto already integrates for the bikes.
- Gives instantaneous speed/incline/target/odometer/rpm. **Does not** give session
  distance, pace, elapsed, or calories — grupetto must derive those (integrate
  speed over time), exactly as it does today and as the Peloton app itself does.

### Path B — `workoutservices.app` / `IMetricsServiceInterface` (NOT recommended)

- Descriptor `com.onepeloton.workoutservices.metrics.IMetricsServiceInterface`,
  action same, service `.../metrics/MetricsService`, no permission gate.
- Delivers a fully-cooked `Bundle` (`metrics_tread_map`) with pre-computed speed,
  incline, distance, elevation, pace, calories, output, avg/max — via
  `registerCallback` (txn 3) → `IMetricsServiceCallback.onUpdate(Bundle)` (txn 1).
- **BUT it is workout-scoped:** values only flow once a workout is created/started
  (`MetricsService` isn't even running at idle — CONFIRMED hardware: "No services
  match" at idle). Creating a workout would create a real Peloton workout. Wrong
  fit for an always-on broadcast bridge.

**Decision: use Path A.** Path B is documented here only as a fallback and as the
source of the display-unit ground truth (its map values are already mph / percent /
miles, corroborating Path A's scaling).

---

## 4. Binding ITreadInterface (CONFIRMED code + hardware)

```
Intent:   action = "com.onepeloton.affernetservice.ITreadInterface"
          package = "com.onepeloton.affernetservice"
          (AffernetService also accepts the same string as the Intent action arg)
Flags:    BIND_AUTO_CREATE
Component: com.onepeloton.affernetservice/.AffernetService
```

`onBind` dispatches purely on the action string; it returns **null** if the Tread
platform isn't detected yet (helper not constructed). Handle null + retry with
backoff. One `AffernetService` serves seven actions (`IAffernetService`,
`IV1Interface`, `IBikeInterface`, `IAuroraInterface`, `ITreadInterface`,
`ICaesarInterface`, `accessories.IAccessoryService`).

**No AIDL stub** — grupetto hand-rolls raw `transact` calls exactly like
`sensor/v1new/`. Every request parcel starts with
`writeInterfaceToken("com.onepeloton.affernetservice.ITreadInterface")`.
Two-way replies: `readException()` then the value. Callbacks are one-way.

### Callback registration (CONFIRMED code + hardware)

- Descriptor of the callback you implement:
  **`com.onepeloton.affernetservice.ITreadCallback`**.
- Register: **txn 48** — `writeInterfaceToken(ITreadInterface desc)`,
  `writeStrongBinder(yourCallback)`, `writeString(arg2)`, `writeString(arg3)`.
  Peloton's reference client passes **`arg2 = null`** and **`arg3 = <your package
  name>`**. Only `arg3` is used (as a RemoteCallbackList cookie / pin-lock-owner
  attribution). **Match this exactly.**
- Immediately after, call **txn 83 `registerProcessDeath(new Binder(), <your
  package>)`** — the reference client does this and it links the service's cleanup
  to your process. Omitting it was a suspected contributor to an earlier incident;
  include it.
- Unregister: **txn 49** — `writeInterfaceToken`, `writeStrongBinder(cb)`,
  `writeString(arg3)`. Call on every teardown path, exactly once (guard it).

### Callback interface `ITreadCallback` (CONFIRMED code)

Your `onTransact` must handle (all ONEWAY — never write a reply), and must answer
`INTERFACE_TRANSACTION` with the descriptor:

| Code | Method | Payload read order |
|---|---|---|
| 1 | `onSensorDataChange(TreadData)` | `int nullMarker` (0=null,1=body), then TreadData (§5) |
| 2 | `onSensorError(long)` | `readLong()` |
| 3 | `onCalibrationStatus(int,boolean,long)` | `readInt(); readInt()!=0; readLong()` |
| 8 | `onTreadLocked(boolean,int)` | `readInt()!=0; readInt()` (locked, reason) |
| 9 | `onTreadControlEvent(byte,long)` | `readByte(); readLong()` |
| 10 | `onPinLockOwner(String)` | `readString()` |
| 4,5,6 | OTA status | ignore |
| 7 | `onLogDataChange` | ignore |

**Update rate: ~20 Hz (CONFIRMED hardware, ~50 ms between packets).** The source
constant `DEFAULT_TREAD_UPDATE_INTERVAL = 33` (→30 Hz) is NOT what the device does;
measured steady-state was 19.9 Hz belt-stopped and belt-moving alike. Decimate on
grupetto's side; do **not** call `setCallbackReportRate` (txn 47) — it is global
(affects the Peloton UI) and has an inverted clamp bug.

Poll alternative: scalar getters exist (`getCurrentSpeed`=17, etc.) but there is
**no bulk-poll transaction returning TreadData** (unlike Bike+ `IBikeInterface`
txn 14). Use the callback.

---

## 5. `TreadData` wire format — 76 fields (CONFIRMED code + hardware)

`writeToParcel`/read order is identical; this order IS the wire format. After the
`int` null-marker, read in this exact order. Verified end-to-end on hardware: the
probe's post-parse `Parcel.dataAvail()` was **0** across 8717 live packets.

Index / field / type / parcel op:

```
 0 packetTime            long     readLong     (device monotonic ms, NOT epoch)
 1 mcbHWVersion          String   readString
 2 mcbFirmwareVersion    String   readString
 3 mcbSerial             String   readString
 4 mcbChassisSerial      String   readString
 5 mcbState              int      readInt      (0=NORMAL; 4/5/6=fault/e-stop)
 6 mcbError              int[4]   length-prefixed int array (len 4)
 7 mcbErrorTime          String   readString
 8 mcbCalibrationState   int      readInt
 9 mcbCalibrationData    String   readString
10 mcbCurrentTime        long     readLong
11 mcbSpeedUnit          int      readInt      (1 on this unit; clients IGNORE it)
12 mcbGearRatio          int      readInt      (167 observed)
13 mcbMaxSpeed           int      readInt      (125 → 12.5 mph)
14 mcbMaxIncline         int      readInt      (150 → 15.0 %)
15 mcb0InclineAdc        int      readInt
16 mcbCurrentSpeed       int      readInt      *** /10 = mph, ACTUAL belt speed ***
17 mcbCurrentIncline     int      readInt      *** /10 = percent grade, ACTUAL ***
18 mcbTargetSpeed        int      readInt      /10 = mph, SETPOINT
19 mcbTargetIncline      int      readInt      /10 = percent, SETPOINT
20 mcbPersonPresent      int      readInt      (1 while belt moving)
21 mcbPersonPresentEnabled int    readInt
22 mcbTotalMiles         long     readLong     *** /10 = lifetime miles (odometer) ***
23 mcbPassthroughModeTimeout long readLong
24 scHWVersion           String   readString
25 scFirmwareVersion     String   readString
26 scSerialNumber        String   readString
27 scSystemState         int      readInt      (2=AWAKE; 3=ERROR → triggers dialog)
28 scError               int[1]   length-prefixed int array (len 1)
29 scErrorTime           long     readLong
30 scEmergencyKeyState   int      readInt      (safety key)
31 scActionButtonKeyState int     readInt
32 rightLedStateAndColor int      readInt
33 leftLedStateAndColor  int      readInt
34 centerLedStateAndColor int     readInt
35 scMcbSafetyLineState  int      readInt
36 rightButtonState      int      readInt
37 leftButtonState       int      readInt
38 rightEncoderData      int      readInt
39 leftEncoderData       int      readInt
40 packetData            byte[]   createByteArray  (raw MCB frame, ~256 bytes)
41 mcbPersonPresentTimeout int    readInt
42 scInClassState        int      readInt
43 mcbRpm                int      readInt      (live motor RPM; scales with speed)
44 mcbSpeedMotorSerial   String   readString
45 mcbInclineMotorSerial String   readString
46 mcbId                 int      readInt
47 mcbMaxADC             int      readInt
48 mcbMinADC             int      readInt
49 mcbCurrentADC         int      readInt      (read 0 on this unit)
50 mcbTargetADC          int      readInt      (read 0 on this unit)
51 mcbPersonPresentTimer int      readInt
52 mcbSpeedControllerVersion   String readString  (null on this unit)
53 mcbInclineControllerVersion String readString  (null on this unit)
54 actionStopState       int      readInt
55 manualSwitchState     int      readInt
56 countdownTimeout      int      readInt
57 countdownTimer        int      readInt
58 sleepTimeout          int      readInt
59 sleepTimer            int      readInt
60 warningCode           int      readInt
61 eKeyDebounce          int      readInt
62 mcbMotorType          int      readInt
63 mcbProcessorId        int      readInt
64 treadLocked           int      readInt (as boolean)  (PIN control-lock, NOT a data gate)
65 keyAdcValue           int      readInt
66 treadControlFlags     int      readInt
67 lastInteractiveMs     int      readInt
68 systemHealthStatus    int      readInt
69 outPacketCount        int      readInt
70 inPacketCount         int      readInt
71 hardwareType          int      readInt
72 scBootloaderVersion   String   readString   (null on this unit)
73 sleepLockState        int      readInt
74 targetResistance      int      readInt      (sled/resistance mode; unused on Tread)
75 <reserved trailing int> int    readInt  *** field 76; ALWAYS 0, reserved padding ***
```

Notes:
- **Field 75 (the 76th field)** was discovered on hardware: the initial 75-field
  table left `dataAvail()==4`. It read `0` across every idle, incline, speed, and
  ramp-down packet — treat as reserved. It exists on the wire and MUST be read or
  the parcel is left misaligned.
- The int-array fields (6, 28) are length-prefixed; read `int len` then `len` ints
  (`mcbError` len 4, `scError` len 1).
- String fields carry `-1` as the null marker (Android `readString` handles this);
  several SC/controller version strings are legitimately null on this unit.
- `packetData` (40) is the raw firmware frame (SLIP-framed, `c0` bytes) — a fallback
  data source if the scalar fields ever prove insufficient; not needed otherwise.

grupetto should mirror `BikeData.java`: a Java/Kotlin Parcelable named `TreadData`
with a `CREATOR` reading these 76 fields in order.

---

## 6. Units & scaling (CONFIRMED hardware — belt session)

Measured live, display value vs raw wire value:

| Metric | Display | Raw field | Factor |
|---|---|---|---|
| Incline | 3.5 % | `mcbCurrentIncline` = 35 | ÷10, percent grade |
| Incline | 8.0 % | 80 | ÷10 |
| Speed | 3.2 mph | `mcbCurrentSpeed` = 32 | ÷10, **mph** |
| Speed | 6.7 mph | 67 | ÷10 (km/h would read 107 — ruled out) |
| Odometer | (settings) | `mcbTotalMiles` = 13230 | ÷10 → 1323.0 mi |
| Max speed | firmware | `mcbMaxSpeed` = 125 | ÷10 → 12.5 mph |
| Max incline | firmware | `mcbMaxIncline` = 150 | ÷10 → 15.0 % |

Corroboration (CONFIRMED code): Peloton's own client
(`com.onepeloton.sensor.tread.TreadSensorDataUtils.formatValueAsDouble`) divides by
10.0; its pace math converts speed with `MILES_TO_KILOMETERS_RATIO = 1.609344`,
proving speed is mph; UI unit strings are `mph` and `%`. `mcbSpeedUnit` exists on
the wire but no Peloton client reads it — units are unconditionally mph/percent.

**Current vs target (CONFIRMED hardware):** on belt ramp-down, `mcbTargetSpeed`
snapped to 0 (setpoint) while `mcbCurrentSpeed` decayed 10→9→7→…→0. For broadcasting
real belt state to a training app, **use `mcbCurrentSpeed` / `mcbCurrentIncline`**,
not the targets. (Peloton's own overlay shows target except in MANUAL mode — a UI
choice, not what a sensor bridge wants.)

**Derived metrics grupetto must compute** (none are on the wire):
- distance: integrate `currentSpeed` over `packetTime` deltas.
- pace (min/mi or sec/km): from current speed.
- power (for the CPS service, optional): Peloton's formula
  `((grade% × k1) + k2) × mph × 43.2521`, with `(k1,k2) = (0.05,0.95)` for grade
  < 10 %, else `(0.07,0.75)`. (CONFIRMED code.)
- `mcbTotalMiles` is a **lifetime odometer**, not session distance — do not use it
  for the workout.

---

## 7. SAFETY — mandatory (CONFIRMED code)

This is a motorized treadmill with a powered incline actuator. The bind is
unauthenticated, so nothing but grupetto's own discipline prevents a motion command.

### 7.1 Allowlist — the ONLY transactions grupetto may send

Reads (safe, pure getters):
```
1,12,13,14,15,17,18,19,20,21,23,28,30,41,44,61,88
```
Callback lifecycle (required):
```
48 registerCallback, 49 unregisterCallback, 83 registerProcessDeath
```
Implement a single `transact()` wrapper that checks the code against a hardcoded
immutable allowlist and throws otherwise. Never compute a transaction code from a
variable or loop. In practice grupetto only needs **48/49/83** plus the callback
stream; scalar reads are optional.

### 7.2 DO-NOT-CALL — physical-motion / persistent-state hazards

Never emit these (motion or device mutation):
```
45 setSpeed          46 setIncline        54 enableTread       72 lockTread
73 unlockTread        55 startCalibration  56 startInclineCalibration
57 startSpeedCalibration  70 resetCalibrationDefaults  71 setMcbMotorType
50/51 fake-data mode  52/53/69 OTA flashing  65/66 bootloader
43 resetErrors        47 setCallbackReportRate  58/59 person-present safety
74-80 PIN ops         81 setTreadControls   68/84 serial writes  85/86 debug
```
`setSpeed`=45 and `getCurrentSpeed`=17 differ by a handful — an off-by-one or a
fuzzing loop is the difference between reading a number and driving the belt. The
allowlist is the guardrail.

### 7.3 The pin-lock-owner watchdog (CONFIRMED code) — real hazard, not tamper

`affernetservice`'s `PelotonRemoteCallbackList` runs a 5-second watchdog: if the
callback registry contains **none** of four privileged packages —
`com.peloton.activity`, `com.onepeloton.sensorstateindicator`,
`com.onepeloton.systempluginui.keyguard`, `com.onepeloton.fwupdateservice` — it
sends `MSG_ENABLE_TREAD(0)` and **disables the belt/incline** (belt stops responding,
`treadLocked` set) with no hardware error flag. The registry keys clients on the
**self-declared cookie string** (`arg3` of `registerCallback`) with no UID check,
and removal is by string equality.

Consequences for grupetto:
- **Register with your own package cookie** (`com.grupetto...`), which never
  collides with the four privileged names. A colliding cookie could, on
  unregister/death, delete the genuine owner's entry and trip the watchdog.
- Keep a clean lifecycle: run as a **foreground Service**, not an Activity that the
  Peloton launcher may force-finish. Always call `unregisterCallback` exactly once.
  Include `registerProcessDeath` so the service cleans up if your process dies.

This behavior was verified benign with the above discipline: a foreground-service
probe with a non-colliding cookie streamed for minutes with zero watchdog events.

### 7.4 The "Something went wrong. Please restart your Tread" dialog (CONFIRMED code)

String `error_something_went_wrong_power_cycle_tread`. It renders **only** when
`scSystemState == 3` (SC serial/hardware error) — e.g. an SC packet-timeout the
service self-synthesizes as code 22 (`T0222`). **A binder client cannot cause this:**
callbacks are one-way and guarded, and the serial watchdog is independent of clients.
During investigation this dialog appeared once and cleared on reboot; the probe's
data showed `scSystemState=2` throughout, i.e. the condition for the dialog was never
in the stream. Treat as an unrelated transient/hardware event. Recovery is a power
cycle (for a genuine fault: unplug from wall, wait ~5 min for MCB capacitors, reseat
safety key).

---

## 8. Detecting a Tread (CONFIRMED by device — `peloton_platform`, NOT the model string)

### 8.1 The model string identifies the TABLET, not the machine

`PLTN-TTR01` is the "Topaz" **tablet**, not a Tread. Peloton's own code says so:

- FactoryTest APK: `public static boolean isTopaz() { return Build.MODEL.startsWith("PLTN-TTR01"); }`
  — a *tablet* test.
- `com/peloton/sensor/client/HardwareType.java`:
  `isTopaz() { return this == TITAN || this == PRISM || isCaesar(); }`, where
  **TITAN = Bike+, PRISM = Tread, CAESAR = Row**. All three ship the Topaz tablet and
  all three report `PLTN-TTR01`.
- Upstream `selalipop/grupetto` was written for the **Bike+** and had
  `IsBikePlus = Build.MODEL == "PLTN-TTR01"`; its PR #10 came from a Bike+ owner whose
  machine reported `PLTN-TTR01-2`.

An earlier revision of this document claimed `PLTN-TTR01` identified a Tread and that
Bike+ was `g700`. **Both were wrong** — the `g700` value belongs to the 2025
Amber/Redstone tablets, not the 2020 Bike+ fleet. Acting on that claim shipped a
regression that routed every Bike+ onto the Tread path (Tread HUD, FTMS `0x2ACD`
instead of `0x2AD2`, no power/cadence/resistance, and an `ITreadInterface` bind).

### 8.2 The discriminator: `Settings.Global["peloton_platform"]`

`affernetservice` detects the attached mainboard by USB VID/PID and writes the platform
into `Settings.Global` (`PlatformGlobal.java:105-108`). There is also
`peloton_platform_variant` (e.g. `prism-l`, `prism-b`).

| `peloton_platform` | `HardwareType` | Machine |
|---|---|---|
| `titan` | TITAN | Bike+ |
| `prism` | PRISM | Tread |
| `caesar` | CAESAR | Row |
| `aurora` | AURORA | Tread+ |
| `v1` | — | Bike Gen 1 |

Hardware-verified read-only over adb:

| Machine | `ro.product.model` | `peloton_platform` | `peloton_platform_variant` |
|---|---|---|---|
| Tread | `PLTN-TTR01` | `prism` | `prism` |
| Bike Gen 1 | `PLTN-RB1VQ` | `v1` | `v1` |

### 8.3 What grupetto does

`util/Peloton.kt` exposes `readPelotonPlatform(context)` (reads the global; null on any
failure) and `isTreadPlatform(platform)`, which is true **only** for `prism` or a
`prism-` prefixed variant. `sensor/SensorSelection.kt` adds the pure
`selectSensorForDevice(isRunningOnPeloton, model, platform)` and the Context-taking
`selectSensorForCurrentDevice(context)` used by `GrupettoApplication` and
`OverlayService`; `selectSensor(...)` itself stays boolean-only and unit-testable.
Tread is still checked before the Bike+/V1 branch, because the Bike+ model test
(`Build.MODEL.contains("PLTN-T")`) also matches a Tread's tablet.

`isTreadModel(model)` remains as a pure helper for logging/diagnostics only. It is a
Topaz-tablet test and must never again gate the tread path on its own.

**Fallback (deliberate): missing, empty or unreadable `peloton_platform` ⇒ NOT a
Tread.** We fall back to the bike path and do not consult the model string. Putting a
Tread on the bike HUD is cosmetic; binding a Bike+ — grupetto's most common device — to
`ITreadInterface` is not.

### 8.4 Do NOT use the bind-probe

An earlier version detected "non-null `ITreadInterface` binder ⇒ Tread". This is FALSE
and caused a Bike v1 to be misdetected as a Tread (Incline+Speed HUD on a bike). Root
cause, confirmed in the Bike's affernetservice 3.0.1 `AffernetService`: `onCreate()`
unconditionally instantiates **every** helper
(`treadServiceHelper = new TreadServiceHelper(...)`) regardless of platform, so
`onBind(ITreadInterface)` returns `treadServiceHelper.getBinder()` — a non-null binder —
even on a bike. The bind-probe therefore always returned non-null.

`ITreadInterface` binding is still used, but only by `PelotonTreadSensorInterface` to
READ data once a Tread has already been selected by platform — never as the detector.

---

## 9. FTMS / BLE output — Treadmill Data `0x2ACD` (PROVISIONAL)

grupetto today writes only FTMS **Indoor Bike Data `0x2AD2`**
(`ble/FitnessMachineService.kt`). Tread needs **Treadmill Data `0x2ACD`**, which is
not implemented. The relevant feature/flag/opcode constants already exist unused in
`ble/FitnessMachineConstants.kt` (`InclinationSupported`, `PaceSupported`,
`SetTargetSpeed`, `SetTargetInclination`, etc.).

**The exact `0x2ACD` field order, flag bits, and units below are PROVISIONAL and
MUST be checked against the Bluetooth SIG FTMS spec (and ideally a known-good
implementation such as qdomyos-zwift `characteristicnotifier2acd.cpp`) before
shipping.** During research a sub-agent fabricated several FTMS details (sentinel
values, a non-existent erratum) that were later retracted; do not trust FTMS
specifics from memory. Cross-checked-and-plausible summary:

- Treadmill Data value = `uint16 flags` (LE) followed by present fields in a fixed
  order. Flag **bit 0 has inverted meaning** for Instantaneous Speed: speed is
  present when bit 0 is *0*. Confirmed against two independent parsers
  (qdomyos-zwift, python `pyftms`).
- Field order when present: Instantaneous Speed (u16, 0.01 km/h) → Average Speed
  (u16, 0.01 km/h) → Total Distance (u24, meters) → Inclination (s16, 0.1 %) →
  Ramp Angle (s16, 0.1 deg) → Elevation Gain (2×u16) → Instantaneous Pace (u8) →
  Average Pace (u8) → Total Energy / per-hour / per-minute → Heart Rate (u8) →
  MET → Elapsed Time (u16 s) → Remaining Time (u16 s) → Force/Power (2×s16).
  **BLE FTMS speed is km/h** — convert grupetto's mph accordingly
  (`km/h = mph × 1.609344`).
- A concrete known-accepted record: qdomyos-zwift emits flags `0x050E` with
  speed + avg speed + distance + inclination + ramp + elapsed, in ~16 bytes; keep
  the notification ≤ 20 bytes (single ATT packet) where possible.
- Advertise the FTMS service and set the Treadmill bits in Fitness Machine Feature
  `0x2ACC` (Inclination, Pace, Elevation Gain, etc.) — verify bit numbers against
  the spec.

BLE peripheral capability on the Tread was **not** confirmable read-only (the
adapter was off and Android 10's dumpsys doesn't expose the flags without a Binder
call). The Tread's Qualcomm `cherokee`/WCN3990-class radio supports peripheral mode
and multi-advertisement in general, but **verify grupetto can actually start a GATT
server + advertise on the Tread** early — it is a feature-level go/no-go independent
of everything above. (On the Bike, the GATT server/advertiser role was observed
free/unoccupied.)

---

## 10. Where this plugs into grupetto

Architecture recap (single module `:app`, manual DI in `GrupettoApplication`,
Compose UI, no Hilt). Relevant files:

- **New binder package** `sensor/tread/` mirroring `sensor/v1new/`:
  - `Binder.kt` — `getTreadBinder()` binding the intent above.
  - `TreadData.(kt|java)` — the 76-field Parcelable (§5).
  - `TreadCombinedSensor.kt` — registers `ITreadCallback` (txn 48 + 83),
    emits from `onSensorDataChange`, scales ÷10, derives distance/pace.
  - `PelotonTreadSensorInterface.kt` — implements the app abstraction.
- **`sensor/interfaces/SensorInterface.kt`** — currently only `power`, `cadence`,
  `resistance`, derived `speed`. Extend for Tread: add `incline`, and treat
  `speed` as a first-class real value (not the bike's power-derived estimate).
  Prefer an additive change (new optional members with defaults, or a sibling
  interface / sealed type) so bike implementations are untouched. Do **not** reuse
  `calculateSpeedFromPelotonV1Power` for the Tread — it has real speed.
- **`util/Peloton.kt`** — add Tread detection and fix the `IsBikePlus`
  mis-match (§8).
- **DI in two places (keep in sync):** `GrupettoApplication.createSensorInterface()`
  and `OverlayService.buildDialog()` — select the Tread interface when a Tread is
  detected.
- **BLE:** add `TreadmillDataService : BaseBleService` (char `0x2ACD`) registered in
  `BleServer.baseServices()`. `BaseBleService.onSensorDataUpdated(cadence, power,
  speed, resistance)` currently hardcodes the four bike metrics — extend the
  signature (or pass a data object) to carry incline/speed for the treadmill.
  DIRCON picks up new services automatically via the mDNS UUID list.
- **UI (optional):** extend `MetricType` (`overlay/OverlaySensorViewModel.kt`) and
  the stat cards with incline/pace; add drawables.

---

## 11. Known grupetto defects found during this work

1. **`BikeData.java` is 6 fields short** vs affernetservice 2.4.508/3.0.1 (missing
   trailing `mV3BikeData`, `mPowerSource`, `mIRAFEnabled`, `mIRAFBaseline`,
   `systemHealthStatus`, `newErrorMap`). Harmless for reads (`createFromParcel`
   stops early; the used fields are first), would break if grupetto ever *wrote* a
   `BikeData` (it doesn't). Confirmed against the maintainer's actual Bike (v3.0.1).
2. **`BikePlusCombinedSensor.kt` mislabels the typed-object null marker** as "skip
   the first integer." If the service returns null it reads garbage instead of
   skipping; caught by try/catch + error counter, so it degrades rather than
   corrupts. `v1new` handles it correctly — copy that pattern for Tread.
3. **`IsBikePlus` matches Treads** (§8).

Fix these while implementing Tread (they're in the same subsystem), each with a test.

---

## 12. Testing strategy (this repo uses JUnit4 + MockK; follow TDD)

- **Parser TDD:** write `TreadData` parcel round-trip / field-order tests before the
  parser. Golden fixtures were captured on hardware: 428 raw idle parcels at
  `scratchpad/tread-capture/fixtures/*.packets` (length-prefixed
  `[int32 LE len][bytes]`, replayable via `Parcel.unmarshall`). The self-check to
  assert is **`dataAvail() == 0` after reading all 76 fields** — this caught the
  missing field 76 on hardware.
- **Moving-belt values** (for assertion-based tests) are preserved as decoded logs in
  `scratchpad/tread-capture/stage3.log` (8717 packets across the full incline/speed
  sweep). Note: the raw *moving-belt* parcels were lost (uninstall wiped them before
  a successful pull); only idle raw parcels remain. Re-capture moving-belt raw
  parcels if a byte-level moving test is wanted — but the decoded values suffice for
  value assertions.
- **Scaling tests:** assert `35→3.5%`, `80→8.0%`, `32→3.2mph`, `67→6.7mph`,
  `13230→1323.0mi` (the confirmed pairs).
- **Detection test:** assert `PLTN-TTR01` with `peloton_platform=titan` selects the
  **Bike+** interface, `prism` selects the Tread, and a missing/empty platform falls
  back to the bike path (`DeviceSensorSelectionTest`, `PelotonPlatformDetectionTest`).
- **Control experiment (already done, for confidence):** the same decompiled APK's
  `IV1Interface`/`BikeData` were re-derived and matched grupetto's shipped, known-
  working bike code (descriptor, txns 1/2, callback codes, 57/57 field order, and
  `power/100` scaling derivable from source). This validated the method used to
  derive the Tread format.

---

## 13. Provisional / open items (do not treat as settled)

- **FTMS `0x2ACD` exact byte layout, flag bits, feature bits** — §9. Verify against
  the SIG spec + a reference implementation.
- **BLE peripheral/advertising works on the Tread** — unverified; confirm early.
- **Field 75 (76th field) semantics** — read 0 in all captured states; treated as
  reserved. Harmless as long as it's consumed.
- **`isFeatureSupported` (txn 88) ids 0–7 return true, 8 false** on this unit — map
  to `PrismFeatureBits` if any feature-gating is needed.
- **Transaction codes are version-specific.** This table is affernetservice 2.4.508.
  The descriptor strings and register/unregister semantics are stable across the
  family, but verify codes against the installed version
  (`pm path com.onepeloton.affernetservice`) if targeting other firmware. A public
  cross-reference exists: `github.com/briancollins/tread` `ITreadInterface.aidl`.

---

## 14. Sources

- **On-device (CONFIRMED hardware):** live adb capture, a read-only foreground-service
  probe binding `ITreadInterface`, and a belt/incline calibration session on the
  maintainer's Tread. Artifacts under `scratchpad/tread-capture/`.
- **Decompiled Peloton APKs (CONFIRMED code):** `com.onepeloton.affernetservice`
  2.4.508 (`ITreadInterface`, `ITreadCallback`, `TreadData`, `TreadConstants`,
  `PelotonRemoteCallbackList`, `TreadServiceHelper`, `platform/**`);
  `com.onepeloton.workoutservices.app` 1.4.1104 (`IMetricsServiceInterface`,
  `metrics_tread_map`); `com.onepeloton.systempluginui` 1.1.986 (the Tread metrics
  overlay client — `TreadSensorDataUtils`, `TreadMetricsMapper`, `TreadErrorMapper`);
  `com.peloton.activity` 2.8.3314 (`com.peloton.sensor.core.client`). All 25 Peloton
  packages were pulled; no anti-tamper/attestation/signature/root-enforcement code
  was found active on a home unit.
- **External:** Bluetooth SIG FTMS spec (to be consulted for §9), qdomyos-zwift
  `characteristicnotifier2acd.cpp`, `dudanov/python-pyftms`,
  `github.com/briancollins/tread` (public `ITreadInterface.aidl`), OpenPelo docs
  (sideloading is reversible via factory reset; no reports of bricking).
