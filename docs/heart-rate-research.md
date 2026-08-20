# Heart-rate on a Peloton tablet — research brief

Status: investigation complete (2026-08-20), hardware- and code-confirmed on a Peloton Tread
(PLTN-TTR01) with a Bike Gen 1 as control. This documents how HR reaches the Peloton tablet
software and what that means for grupetto, so the question does not get re-investigated from
scratch. Companion to `tread-support-research.md`.

## TL;DR

- grupetto already gets real HR the simple way: it is a **BLE 0x180D central**
  (`sensor/heartrate/HeartRateManager.kt`). A chest strap, or an iPhone HR-broadcaster app
  (HeartCast / Echo / BlueHeart), pairs straight in. No new code.
- Peloton's own tablet HR is owned by `com.onepeloton.workoutservices.app` and exposed via an
  AIDL service (`IHeartRate`). grupetto *could* passively read it, but for the **Apple Watch**
  case this needs a **started Peloton workout** and gives no advantage over the routes above.
- **Apple Watch HR without a Peloton workout is not achievable from grupetto.** The reason is
  architectural, not a permission grupetto is missing (details below). Do not spend time trying.

## How the tablet gets HR

`com.onepeloton.workoutservices.app` merges three producers into one flow and exposes it via
`IHeartRate` (action/descriptor `com.onepeloton.workoutservices.heartrate.IHeartRate`):

1. **BLE 0x180D strap** — the tablet scans as a GATT central (`HRMGattCallback`, HR Measurement
   char `2A37`). This is the conventional path.
2. **ANT+** — `AntPlusHeartRatePcc` (the Tread tablet has an ANT radio).
3. **Apple Watch / Wear OS** — internally codenamed **"Tangerine"**. It does **not** use
   Bluetooth from the watch. It travels Watch → iPhone → **Peloton cloud** (a gRPC channel
   Peloton calls **"Emissary"**) → tablet. Confirmed by Peloton's own support docs
   ("One-Tap Apple Watch Tracking … does not use Bluetooth") and by the decompiled transport.

BPM surfaces at `heartRateData.calculatedHeartRate`; watch HR at
`tangerine_tracking_state_connected_map.heartRate`.

Peloton's "One-Tap Apple Watch Tracking" is a different feature from Apple **GymKit** (NFC,
Bike+ only). The Tread has no functional GymKit (`GymkitService` logs
`shouldAcceptConnections: out_of_class`); Tangerine is the Tread's watch-HR path.

## Could grupetto read Peloton's HR service?

Technically yes, for passive listening: `IHeartRate` (and `IHeartRateConnection`) are
`exported=true` with no `android:permission`, and `registerCallback` performs no caller
validation. A sibling project (`orbitalmutiny/switchback`) already binds it. If we ever add a
"read Peloton's HR" source, it plugs in behind `HeartRateManager`'s single `heartRate`
StateFlow, and it is a plain `LifecycleService` on a non-motorized subsystem — none of the
`affernetservice` pin-lock-watchdog hazard applies (normal unbind-on-stop discipline still does).

The value-added action verbs (`START`, device connect/disconnect, enabling the watch path) are
gated to an allowlist of Peloton's own client IDs. That check is weak, but there is no legitimate
reason to defeat it — see the next section for why it would not even help.

## Why Apple Watch HR needs a started workout

Two gates; the binding one is not on the tablet:

1. **Workout-ID rendezvous.** The state only flips to "connected" when the HR packet arriving
   over the Emissary cloud channel carries a workoutId equal to the one the tablet set locally
   when a workout started. The cloud tags the watch's stream with that id only for a real
   backend workout the watch is bound to. A local app can set its own side of the match; it
   cannot make Peloton's cloud tag the watch stream.
2. **The watch emits nothing until a workout starts.** Peloton's watchOS app states outright:
   *"Start a workout on your Peloton app or equipment and keep your Peloton watch app open to
   sync your heart rate."* Verified on-device: at the Peloton home screen, no HR / Tangerine /
   Emissary activity is logged at all. There is no upstream data to intercept.

There is a *userId-keyed* path in the code (`USER_SESSION_BASED_TANGERINE_ENABLED`) that would
not need a workout — but it is armed only when a server-side Optimizely flag (`tangerine_by_user`,
default off) is enabled for the account, decided by Peloton's backend. On the tablet tested it is
off (Optimizely is fetching datafiles, yet the login-time Tangerine session observer never fires).
grupetto cannot set this flag.

Net: the only way to get Apple Watch HR through Peloton's stack is to start a Peloton workout —
a free "Just Run" / Freestyle session suffices (no paid class). And if a Peloton workout is
running, Peloton already syncs it to Apple Health and Strava itself, which tends to make a
grupetto-based chain redundant for the watch case specifically.

## Recommended routes for grupetto (unchanged by all of the above)

| Route | Code needed | Notes |
|---|---|---|
| BLE chest strap → grupetto (0x180D central) | none | Most reliable. `HeartRateManager` handles it today. |
| iPhone broadcaster (HeartCast/Echo) → grupetto | none | Keep the broadcaster app foregrounded; enable grupetto's "match by name" for iOS's rotating BLE address. |
| Apple Watch via Peloton `IHeartRate` | new source behind `HeartRateManager` | Needs a started Peloton workout; no advantage over the above for this user. Not recommended. |

## On-device probe (read-only) if revisiting the watch path

To check whether `tangerine_by_user` is enabled on a given tablet/account:

```
adb shell logcat -c
adb shell logcat -v time '*:V' | grep -Ei \
  'TangerineUserSession|start Tangerine User Session|currentWorkoutId|tangerine_tracking_state_connected'
```

Log in and reach the home screen (no workout). `start Tangerine User Session with user:` at
login means the flag is ON and the watch path needs no class. `connected` while
`currentWorkoutId -> null` proves HR arrived with no workout; `connected` only when
`currentWorkoutId` is non-null means a workout is still mandatory.

---

## GATT connect invariants (`HeartRateManager`)

Android registers a GATT *client* per `BluetoothDevice.connectGatt()` and only unregisters
it on `close()` **after** a client interface has been assigned. A connect that is aborted
before that point (`close()` with `mClientIf=0`) leaks the registry slot permanently. The
process cap is ~32; past it `connectGatt()` returns **null** and heart rate never connects
again — silently, if nothing logs. Observed on a Tread (API 29): two `GATT_Connect` calls
per tap, `wl_direct_connect_timeout_cb`, `status=133`, then nothing.

The rules that keep that from recurring:

- **At most one connect in flight.** `connectToAddress` is `@Synchronized` and gated on an
  `AtomicBoolean` released by `onConnectionStateChange`, by a null `connectGatt`, and by a
  `ConnectTimeoutMs` watchdog so a callback that never arrives cannot wedge the guard.
- **Stop scanning before connecting, never after.** The device picker runs a
  `SCAN_MODE_LOW_LATENCY` scan; a result delivered mid-connect used to re-enter
  `maybeAutoConnectSaved` (`_connectedDevice` is still null) and fire a second, overlapping
  `connectGatt` for the same device.
- **`disconnect()` and `close()` go in separate `try` blocks.** `close()` is the call that
  actually frees the registration; a throwing `disconnect()` must not skip it.
- **Null-check `connectGatt`** and log it at error level — that return is the symptom of an
  exhausted registry.
- **Log every `onConnectionStateChange` with address and `status`.** A silent 133 loop is
  indistinguishable from "the tap did nothing".

All of the above is unit tested in `HeartRateManagerTest` on the JVM. Every Android
Bluetooth call sits behind `HeartRateBleTransport` purely so those sequencing rules can be
tested without Robolectric; keep that interface thin and policy-free.
