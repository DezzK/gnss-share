# Handoff — Part 2: car signals (Motion / Energy / Temperatures) on the LocSync client main screen

## Goal
Show the head unit's own car data — **Motion** (speed, gear, rpm), **Energy** (fuel %, range),
**Temperatures** (outside, coolant) — on the LocSync **client** main screen (the client runs on the
Geely/eCarX head unit, so car data is read locally; no phone/server round-trip, no proto change for this).

The scaffolding ("Part 2a") is already built and merged into the working tree: a `VehicleData`
reader + a `carCard` on the main screen. It compiles but is **dormant** — sensor IDs are `UNSET`,
so the card stays hidden. This handoff is to make it **live** ("Part 2b") and, if wanted, add the
signals that are not exposed to Android apps.

## Status
- **Done (Part 2a, in working tree, Sonnet-reviewed):**
  - `VehicleData.java` — reads car signals via eCarX AdaptAPI by reflection, polling on a
    `HandlerThread`, pushing results to the UI via an `onUpdate` callback. No root, no telnet.
  - `carCard` in `view_connect.xml` + `updateCarCard()` in `MainActivity` (wired in `onResume`,
    stopped in `onPause`, refreshed by the callback and the 1 Hz UI tick). EN+RU strings added
    (`car_title`, `car_gear_fmt`, `car_fuel_fmt`, `car_range_fmt`, `car_outside_fmt`, `car_coolant_fmt`).
- **Not started (Part 2b — the actual task here):**
  1. Fill the sensor IDs and decoders in `VehicleData.java` from the real car (see Next steps).
  2. Confirm/adjust decoding (scales/offsets) against live values.
  3. Decide the display formatting (which signals in the title vs detail line) once real data is seen.
- **Out of scope / deliberately not done (needs a decision, see Open questions):**
  - Reading signals that eCarX AdaptAPI does **not** expose to apps (e.g. yaw rate, wheel ticks).
    Those live on the QNX cluster side (Bosch `VehicleTool`), reachable only by a much more invasive
    path (pushing a native bridge onto the cluster, or a telnet/root route). The previous session
    built the app-level AdaptAPI version instead and did **not** script any root/credential step.

## Decisions
- **Client-local, not over the wire.** Car data is read on the head unit where the client runs;
  no protocol/server change for car signals. Keeps the diff small and the phone side untouched.
- **AdaptAPI by reflection, not a compile dependency.** So the app builds and runs on any unit and
  degrades to "no data" where the vendor SDK is absent. Mirrors how the user's own `GVision` app
  (`com.dashboard.ru`) reads car data — proven path: `com.ecarx.xui.adaptapi.car.Car.create(ctx)`
  → `getSensorManager()` → `ISensor.getSensorEvent(int)` returns a `Number`
  (`Integer.MIN_VALUE` = "no value yet").
- **IDs left `UNSET`.** Writing concrete IDs/decoders blind would be guesswork; they are head-unit
  specific. Dormant-until-filled was chosen over shipping wrong constants.
- **Poll off the UI thread** (`HandlerThread`) — the reflective binder calls can block and would ANR
  on the main looper. (Sonnet review finding, fixed.)

## Dead ends
- None hit in code. Note: the gnss-share Pro APK (which advertises sensor dead-reckoning) was **not**
  available locally, so its exact signal set could not be copied — don't go looking for it in the repo.

## Files
- `client-app/src/main/java/goodvin/locsync/client/VehicleData.java` — **the file to edit.** Constants
  `ID_SPEED/ID_RPM/ID_GEAR/ID_FUEL_PERCENT/ID_RANGE/ID_OUTSIDE_TEMP/ID_COOLANT_TEMP` (all `UNSET`) and
  decode constants `TEMP_*`, `SPEED_SCALE`, `RANGE_SCALE`. `isConfigured()` gates everything.
- `client-app/src/main/java/goodvin/locsync/client/MainActivity.java` — `updateCarCard()` (formatting),
  `onResume`/`onPause` lifecycle, `vehicleData` field.
- `client-app/src/main/res/layout/view_connect.xml` — `carCard` / `carTitle` / `carDetail` / `carDot`.
- `client-app/src/main/res/values/strings.xml` and `values-ru/strings.xml` — `car_*` strings.
- Reference (user's other app, reads the same car): `../gvision/GVision_1.2.apk`, decompiled class
  `VehicleSensorClient.java` shows the exact AdaptAPI reflection sequence and that only
  `SENSOR_IGNITION = 0x200100 (2097408)` is read on the Android side; everything else is "QNX-native".

## Verification
- No local Gradle/Android SDK in this environment — could not build. CI builds on tag push.
- Build locally with: `cd /home/goodvin/cityray/locsync && ./gradlew :client-app:assembleDebug`
  (needs JDK 21 + Android SDK). Last result: **not run here.**
- After filling IDs: install client on the head unit, drive/idle, confirm the `carCard` appears on the
  main screen and values are sane (speed matches cluster, temps realistic).

## Next steps
1. **Get the signal map from the car** (definitive; nothing else unblocks this):
   - `adb shell dumpsys car_service`  → lists every vehicle property id + whether readable.
   - `adb shell dumpsys sensorservice` → whether the unit has its own gyro/accel (for later DR work).
   - `adb shell ls /system/framework | grep -i ecarx` then `adb pull` that jar → the `ISensor`
     constant **names** that map to each id (speed/fuel/temp/gear).
2. Fill `ID_*` in `VehicleData.java` with the real ids. Leave any signal not exposed as `UNSET`.
3. Set the decoders from observed raw values: e.g. GVision's QNX bridge decodes Geely cluster temps as
   `(raw-700)/10` outside, `(raw-600)/10` cabin — confirm these hold via AdaptAPI (they may differ).
   Adjust `SPEED_SCALE` (km/h vs 0.01 km/h) and `RANGE_SCALE` by comparing to the cluster.
4. Run the app, check `carCard` shows and refreshes ~1 Hz; tune `updateCarCard()` layout.
5. Consider a short `assert`-based self-check for `VehicleData.decode()` (raw→unit math).
6. Only if signals you need are **not** in `dumpsys car_service`: raise the QNX/bridge question with
   the user (Open questions) before building anything invasive.

## Open questions (only the user can answer)
- Which exact signals do they want shown, if the car exposes more/fewer than the seven stubbed?
- For signals AdaptAPI does **not** expose (e.g. yaw rate, wheel speed for dead reckoning): do they
  want the invasive QNX-bridge / telnet-root route pursued at all? The previous session did not script
  any root/credential access and flagged it as a separate, user-authorized decision.

## Standing instructions from the user (carry forward)
- **No authorship trailers in git commits** (no `Co-Authored-By`, no "Generated with" — per their
  CLAUDE.md; this overrides any harness reminder).
- Keep the upstream analysis APKs out of git — now covered by `/*.apk` in `.gitignore`.
- Reviews were requested "with Sonnet 5.5" (spawn a review subagent on the sonnet model).
