/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package goodvin.locsync.client;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import goodvin.locsync.shared.AppLog;

import java.lang.reflect.Method;

/**
 * Reads car signals (Motion / Energy / Temperatures) on an eCarX head unit through the vendor
 * AdaptAPI by reflection, so the app builds and runs without the vendor SDK on the classpath and
 * degrades to "no data" on any other unit. This is the vendor-supported, app-level path — no root
 * and no telnet; it reads only what the platform already exposes to apps.
 *
 * <p>The sensor IDs and their decoding are head-unit specific. They are left UNSET here; fill them
 * from the target car with {@code adb shell dumpsys car_service} (property IDs) and the unit's
 * {@code com.ecarx.xui.adaptapi} jar (the ISensor constant names). Until an ID is set, its field
 * stays {@code null} and the UI shows "—".
 */
final class VehicleData {

    // --- signal IDs: fill from the car; UNSET means "not wired, report nothing" ---
    static final int UNSET = -1;
    // Motion
    static final int ID_SPEED = UNSET;          // vehicle speed
    static final int ID_RPM = UNSET;            // engine RPM
    static final int ID_GEAR = UNSET;           // selected gear (raw code)
    // Energy
    static final int ID_FUEL_PERCENT = UNSET;   // fuel/charge level, %
    static final int ID_RANGE = UNSET;          // remaining range
    // Temperatures
    static final int ID_OUTSIDE_TEMP = UNSET;   // outside air temperature (raw)
    static final int ID_COOLANT_TEMP = UNSET;   // engine coolant temperature (raw)

    // --- raw→unit decoding: confirm against the car; these are starting points, not truth ---
    // GVision's QNX bridge decodes the Geely cluster temps as (raw-700)/10 outside, (raw-600)/10 cabin.
    static final float TEMP_OUTSIDE_OFFSET = 700f;
    static final float TEMP_COOLANT_OFFSET = 0f;    // TODO confirm
    static final float TEMP_SCALE = 10f;
    static final float SPEED_SCALE = 1f;            // TODO confirm (km/h vs 0.01 km/h etc.)
    static final float RANGE_SCALE = 1f;            // TODO confirm

    private static final String TAG = "VehicleData";
    private static final long POLL_MS = 1000;
    private static final long RETRY_MS = 5000;

    /** Immutable readout. Any field may be null when that signal isn't reported. */
    static final class Snapshot {
        final Float speedKmh, rpm, fuelPercent, rangeKm, outsideTempC, coolantTempC;
        final Integer gearRaw;

        Snapshot(Float speedKmh, Float rpm, Integer gearRaw, Float fuelPercent,
                 Float rangeKm, Float outsideTempC, Float coolantTempC) {
            this.speedKmh = speedKmh;
            this.rpm = rpm;
            this.gearRaw = gearRaw;
            this.fuelPercent = fuelPercent;
            this.rangeKm = rangeKm;
            this.outsideTempC = outsideTempC;
            this.coolantTempC = coolantTempC;
        }

        boolean hasAny() {
            return speedKmh != null || rpm != null || gearRaw != null || fuelPercent != null
                    || rangeKm != null || outsideTempC != null || coolantTempC != null;
        }

        boolean hasMotion()  { return speedKmh != null || rpm != null || gearRaw != null; }
        boolean hasEnergy()  { return fuelPercent != null || rangeKm != null; }
        boolean hasTemps()   { return outsideTempC != null || coolantTempC != null; }

        static final Snapshot EMPTY = new Snapshot(null, null, null, null, null, null, null);
    }

    private final Context context;
    private final Handler uiHandler;      // delivers onUpdate back to the caller's (UI) thread
    private final Runnable onUpdate;      // called on the UI thread after each poll; may be null
    private volatile Snapshot latest = Snapshot.EMPTY;
    private volatile boolean stopped = true;

    // Polling runs off the UI thread: Car.create/connect/getSensorEvent are reflective binder calls
    // that can block if the head-unit service is slow, which would ANR on the main looper.
    private HandlerThread pollThread;
    private Handler worker;

    private Object sensorManager;   // com.ecarx.xui.adaptapi.car.sensor.ISensor
    private Method getSensorEvent;  // ISensor#getSensorEvent(int) -> Number

    VehicleData(Context context, Handler uiHandler, Runnable onUpdate) {
        this.context = context.getApplicationContext();
        this.uiHandler = uiHandler;
        this.onUpdate = onUpdate;
    }

    /** True once at least one signal ID is wired; otherwise the reader is a no-op by design. */
    static boolean isConfigured() {
        return ID_SPEED != UNSET || ID_RPM != UNSET || ID_GEAR != UNSET || ID_FUEL_PERCENT != UNSET
                || ID_RANGE != UNSET || ID_OUTSIDE_TEMP != UNSET || ID_COOLANT_TEMP != UNSET;
    }

    Snapshot snapshot() {
        return latest;
    }

    void start() {
        if (!isConfigured()) {
            return;   // nothing wired: don't even spin up a thread
        }
        if (pollThread != null) {
            return;   // already running
        }
        stopped = false;
        pollThread = new HandlerThread("vehicle-poll");
        pollThread.start();
        worker = new Handler(pollThread.getLooper());
        worker.post(pollTask);
    }

    void stop() {
        stopped = true;
        if (worker != null) {
            worker.removeCallbacks(pollTask);
            worker = null;
        }
        if (pollThread != null) {
            pollThread.quitSafely();
            pollThread = null;
        }
        sensorManager = null;
        getSensorEvent = null;
        latest = Snapshot.EMPTY;
    }

    private final Runnable pollTask = this::poll;

    /** Runs on the poll thread. */
    private void poll() {
        if (stopped) {
            return;
        }
        try {
            if (sensorManager == null) {
                connect();
            }
            latest = read();
            notifyUi();
            if (!stopped && worker != null) {
                worker.postDelayed(pollTask, POLL_MS);
            }
        } catch (Exception e) {
            AppLog.v(TAG, "AdaptAPI read failed, retrying: " + e.getMessage());
            sensorManager = null;
            getSensorEvent = null;
            latest = Snapshot.EMPTY;
            notifyUi();
            if (!stopped && worker != null) {
                worker.postDelayed(pollTask, RETRY_MS);
            }
        }
    }

    private void notifyUi() {
        if (onUpdate != null) {
            uiHandler.post(onUpdate);
        }
    }

    private void connect() throws Exception {
        Class<?> carCls = Class.forName("com.ecarx.xui.adaptapi.car.Car");
        Object car = carCls.getMethod("create", Context.class).invoke(null, context);
        if (car == null) {
            throw new IllegalStateException("Car.create returned null");
        }
        // Some builds need an explicit connect() before the managers are live.
        try {
            Class<?> connectable = Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable");
            if (connectable.isInstance(car)) {
                connectable.getMethod("connect").invoke(car);
            }
        } catch (Exception ignored) {
            // older adaptapi without IConnectable
        }
        Object mgr = car.getClass().getMethod("getSensorManager").invoke(car);
        if (mgr == null) {
            throw new IllegalStateException("getSensorManager returned null");
        }
        sensorManager = mgr;
        getSensorEvent = mgr.getClass().getMethod("getSensorEvent", int.class);
        AppLog.i(TAG, "AdaptAPI sensor manager connected");
    }

    private Snapshot read() {
        return new Snapshot(
                decode(ID_SPEED, SPEED_SCALE, 0f),
                decode(ID_RPM, 1f, 0f),
                rawInt(ID_GEAR),
                decode(ID_FUEL_PERCENT, 1f, 0f),
                decode(ID_RANGE, RANGE_SCALE, 0f),
                decode(ID_OUTSIDE_TEMP, 1f / TEMP_SCALE, -TEMP_OUTSIDE_OFFSET / TEMP_SCALE),
                decode(ID_COOLANT_TEMP, 1f / TEMP_SCALE, -TEMP_COOLANT_OFFSET / TEMP_SCALE));
    }

    /** raw * scale + offset, or null when the signal isn't wired/available. */
    private Float decode(int id, float scale, float offset) {
        Integer raw = rawInt(id);
        return raw == null ? null : raw * scale + offset;
    }

    private Integer rawInt(int id) {
        if (id == UNSET || getSensorEvent == null) {
            return null;
        }
        try {
            Object v = getSensorEvent.invoke(sensorManager, id);
            if (v instanceof Number) {
                int i = ((Number) v).intValue();
                // AdaptAPI uses Integer.MIN_VALUE as the "no value yet" sentinel.
                return i == Integer.MIN_VALUE ? null : i;
            }
        } catch (Exception e) {
            AppLog.v(TAG, "read id=" + id + " failed: " + e.getMessage());
        }
        return null;
    }
}
