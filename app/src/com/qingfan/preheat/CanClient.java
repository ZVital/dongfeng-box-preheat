package com.qingfan.preheat;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/** Binds to com.qinggan.canbus.service/.CanBusService and calls it by raw transaction code.
 *
 * Everything here was measured on the car on 2026-10-02; see PREHEAT-FINDINGS.md.
 * The service is exported with no android:permission, so this needs no permission,
 * no root and no native code.
 *
 * Transaction codes were read out of the Launcher's own AIDL copy, where each one
 * appears as a const/16 immediately before IBinder.transact.
 */
final class CanClient {

    static final String TAG = "Preheat";
    private static final String PKG = "com.qinggan.canbus.service";
    private static final String ACTION = "com.qinggan.canbus.CanBusService";
    private static final String TOKEN = "com.qinggan.canbus.ICanBusService";

    static final int TX_ADD_CALLBACK = 27;
    static final int TX_REMOVE_CALLBACK = 28;
    static final int TX_GET_AIR_CONDITION = 29;
    static final int TX_GET_AMBIENT_TEMP = 6;
    static final int TX_OPEN_AUTO = 90;
    static final int TX_SET_AIRCOND = 30;
    /** AirConditionState ordinals, from the enum's own declaration order:
     *  13 = AC_TEMP_DEM, 4 = AC_SWITCH, 33 = AC_RAPID_COOLING_MODE. */
    static final int AC_TEMP_DEM = 13;
    static final int AC_SWITCH = 4;
    static final int AC_RAPID_COOLING_MODE = 33;
    /** The unit this service uses for AC_TEMP_DEM: tenths of a degree, proved by
     *  S31CanBusComponentImpl.setAirLeftTemperatureUp, which does
     *  mul-float v2, v0, v6 with v6 = 10.0 before sending. 22.5 goes over as 225.
     *  The same code clamps to 10.0..32.0. */
    static final int TEMP_DEM_TENTHS = 10;
    private static final float TEMP_DEM_MIN = 10.0f;
    private static final float TEMP_DEM_MAX = 32.0f;
    static final int TX_CLOSE_AUTO = 91;
    static final int TX_TEMP_UP = 94;
    static final int TX_TEMP_DOWN = 95;
    static final int TX_OPEN_AC = 88;
    static final int TX_CLOSE_AC = 89;
    static final int TX_OPEN_DEFROST = 92;
    static final int TX_CLOSE_DEFROST = 93;
    // 94/95 (setAirLeftTemperatureUp/Down) найдены, но намеренно не
    // используются: единственный способ выставить уставку. См. CAN-MAP.

    interface Ready {
        void onBound();
    }

    private static IBinder binder;
    private static boolean binding;
    private static Ready pending;

    private final Context ctx;

    CanClient(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    private final ServiceConnection conn = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = service;
            binding = false;
            Log.i(TAG, "CAN bound: " + name);
            if (pending != null) { Ready r = pending; pending = null; r.onBound(); }
        }

        public void onServiceDisconnected(ComponentName name) {
            binder = null;
            Log.w(TAG, "CAN disconnected: " + name);
        }
    };

    /** Binds once and keeps the handle; rebinds if the service dies. */
    void bind(Ready ready) {
        if (binder != null) { ready.onBound(); return; }
        pending = ready;
        if (binding) return;
        binding = true;
        Intent i = new Intent(ACTION).setPackage(PKG);
        boolean ok;
        try {
            ok = ctx.bindService(i, conn, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            Log.e(TAG, "bindService denied: " + e.getMessage());
            ok = false;
        }
        Log.i(TAG, "bindService -> " + ok);
        if (!ok) { binding = false; pending = null; }
    }

    /** Raw reply blob of getAirCondition, or null. Snapshot reads the same blob
     * rather than a second round-trip. */
    boolean isBound() {
        return binder != null;
    }

    private boolean callBoolean(int code) {
        if (binder == null) { Log.w(TAG, "call " + code + ": not bound"); return false; }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TOKEN);
            if (!binder.transact(code, data, reply, 0)) {
                Log.w(TAG, "transact(" + code + ") returned false");
                return false;
            }
            reply.readException();
            return reply.readInt() != 0;
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "transact(" + code + ") " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** AUTO hands the closed loop to the vehicle's own climate ECU: it reads its
     *  own sensor, picks the blower speed and stops when warm. The head unit has
     *  no cabin temperature (airTempInCar is always -1), so this is the only
     *  command that can decide "warm enough" without us. */
    boolean autoMode() {
        boolean r = callBoolean(TX_OPEN_AUTO);
        Log.i(TAG, "openAirAutoMode -> " + r);
        return r;
    }

    /** Leaves AUTO. Necessary before our own setpoint steps do anything: with
     *  AUTO on, the car owns the setpoint and ignores Up/Down - measured on the
     *  car, six Down presses changed nothing while AUTO was active. */
    boolean closeAutoMode() {
        boolean r = callBoolean(TX_CLOSE_AUTO);
        Log.i(TAG, "closeAirAutoMode -> " + r);
        return r;
    }

    /** Sets the setpoint directly instead of stepping. setAirConditionState with
     *  AC_TEMP_DEM is the path the service itself uses, in tenths of a degree,
     *  clamped the same way. Stepping is only a UI convenience on top of it. */
    boolean setTempDirect(float target) {
        if (target < TEMP_DEM_MIN) { target = TEMP_DEM_MIN; Log.w(TAG, "temp clamped up"); }
        if (target > TEMP_DEM_MAX) { target = TEMP_DEM_MAX; Log.w(TAG, "temp clamped down"); }
        int tenths = Math.round(target * TEMP_DEM_TENTHS);
        boolean ok = callAirConditionState(AC_TEMP_DEM, tenths);
        // The setpoint is applied asynchronously by the car: read too early and
        // it still shows the old value. Measured - a 700 ms wait is enough.
        try { Thread.sleep(700); } catch (InterruptedException e) { }
        float now = queryAir().setpoint;
        Log.i(TAG, "setTempDirect: " + target + "C -> AC_TEMP_DEM=" + tenths
                + " sent=" + ok + ", car reports " + now
                + (Math.abs(now - target) < 0.25f ? " (ПРИНЯТО)" : " (НЕ ПРИНЯТО)"));
        return ok && Math.abs(now - target) < 0.25f;
    }

    /** Raw Parcel for setAirConditionState(AirConditionState,int)V:
     *  interface token, presence, the enum ordinal, the enum's own int value,
     *  then the method's int argument. */
    private boolean callAirConditionState(int ordinal, int value) {
        if (binder == null) return false;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInterfaceToken(TOKEN);
            d.writeInt(1);
            d.writeInt(ordinal);
            d.writeInt(0);
            d.writeInt(value);
            if (!binder.transact(TX_SET_AIRCOND, d, r, 0)) return false;
            r.readException();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "callAirConditionState: " + e.getClass().getSimpleName());
            return false;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    boolean tempUp()   { return callBoolean(TX_TEMP_UP); }
    boolean tempDown() { return callBoolean(TX_TEMP_DOWN); }

    /** The OEM setpoint slider moves in 0.5C steps (owner-confirmed on the car),
     *  so one press of Up/Down is 0.5C. */
    static final float STEP_C = 0.5f;

    /** Moves the SETPOINT by pressing Up/Down - the only way the interface offers,
     *  there is no absolute setter in ICanBusService. */
    boolean setTempTo(float from, float to) {
        int steps = Math.round((to - from) / STEP_C);
        if (steps > 60) { steps = 60; Log.w(TAG, "temp steps clamped to 60"); }
        if (steps < -60) { steps = -60; }
        Log.i(TAG, "setpoint " + from + " -> " + to + " = " + steps + " presses of 0.5C");
        for (int i = 0; i < Math.abs(steps); i++) {
            boolean ok = steps > 0 ? tempUp() : tempDown();
            if (!ok) { Log.w(TAG, "temp step failed at " + i); return false; }
        }
        return true;
    }


    /** Waits for the car to report the climate actually running.
     *
     *  S31CanBusComponentImpl.setAirLeftTemperatureUp gates on AirSWStatus:
     *  if the climate is off it ignores the step, sends AC_TEMP_DEM=250
     *  (25.0C) as a fallback, and still returns true. So a true from
     *  setAirLeftTemperatureUp means "the method ran", not "the setpoint moved",
     *  and stepping before AirSWStatus goes non-zero silently corrupts the target.
     *  Bytecode: canbus.dexdump S31CanBusComponentImpl, offset 0x0011. */
    boolean awaitAcOn(int maxMs) {
        long deadline = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < deadline) {
            if (queryAir().acOn) return true;
            try { Thread.sleep(250); } catch (InterruptedException e) { return false; }
        }
        Log.w(TAG, "awaitAcOn: still off after " + maxMs + "ms");
        return false;
    }

    boolean openAc()  { boolean r = callBoolean(TX_OPEN_AC);  Log.i(TAG, "openAirConditionSwitch -> " + r);  return r; }
    boolean closeAc() { boolean r = callBoolean(TX_CLOSE_AC); Log.i(TAG, "closeAirConditionSwitch -> " + r); return r; }
    boolean defrost(boolean on) {
        boolean r = callBoolean(on ? TX_OPEN_DEFROST : TX_CLOSE_DEFROST);
        Log.i(TAG, "front defroster " + (on ? "on" : "off") + " -> " + r);
        return r;
    }

    /** Registers an ICanBusServiceCallback object so the service pushes changes.
     *  addCallback takes the callback's binder, so it is written as a strong reference
     *  argument rather than a typed one. */
    void callRegister(android.os.IBinder callback) {
        if (binder == null) return;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInterfaceToken(TOKEN);
            d.writeStrongBinder(callback);
            boolean ok = binder.transact(TX_ADD_CALLBACK, d, r, 0);
            if (ok) { r.readException(); Log.i(TAG, "addCallback -> " + r.readInt()); }
            else Log.w(TAG, "addCallback returned false");
        } catch (Exception e) {
            Log.e(TAG, "addCallback: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    void callUnregister(android.os.IBinder callback) {
        if (binder == null) return;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInterfaceToken(TOKEN);
            d.writeStrongBinder(callback);
            if (binder.transact(TX_REMOVE_CALLBACK, d, r, 0)) { r.readException(); r.readInt(); }
        } catch (Exception e) {
            Log.w(TAG, "removeCallback: " + e.getClass().getSimpleName());
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    /** Climate state, fields in the order AirCondition.writeToParcel writes them:
     *  ten ints then the floats. Taken from the stock launcher's copy of the class,
     *  not guessed - the earlier build read a float at byte 4 and was wrong.
     *  airLeftTemperature is NOT yet confirmed to be the measured cabin temperature
     *  rather than the requested setpoint; see CAN-MAP.md section 4. */
    static final class AirState {
        boolean acOn;          // airSWStatus
        boolean acCompressor;  // airACStatus
        float setpoint;        // airLeftTemperature (slot 10) - commanded, NOT cabin
        float rightTemp;
        float rearTemp;
        int windSpeed;
        int supply;
        int cabinTemp;         // airTempInCar  (slot 33), 0x7FFFFFFF = not reported
        int outsideTemp;       // airTempOutCar (slot 34), 0x7FFFFFFF = not reported
    }

    static AirState readAirState(Parcel reply) {
        AirState st = new AirState();
        st.cabinTemp = Integer.MIN_VALUE;
        st.outsideTemp = Integer.MIN_VALUE;
        // AIDL writes the Parcelable as: int presence, then the object's own
        // fields. Skipping the presence flag is what makes the slots line up -
        // without it every read is shifted by one and returns denormal garbage.
        if (reply.readInt() == 0) return st;
        st.acOn = reply.readInt() != 0;      // 0  airSWStatus
        reply.readInt();                       // 1  airACStatus
        st.acCompressor = reply.readInt() != 0;// 2  airHighWindStatus
        // 3..9  airLowWind, DUAL, MaxFront, RearLight, Supply, DisplaySW, WindSpeed
        for (int i = 3; i <= 9; i++) {
            if (i == 9) st.windSpeed = reply.readInt(); else reply.readInt();
        }
        st.setpoint = reply.readFloat();       // 10 airLeftTemperature - SETPOINT
        st.rightTemp = reply.readFloat();      // 11
        st.rearTemp = reply.readFloat();       // 12
        // 13..32  airCirculationMode, seat heating, mode flags, vents...
        for (int i = 13; i <= 32; i++) {
            if (i == 19) st.supply = reply.readInt(); else reply.readInt();
        }
        st.cabinTemp = reply.readInt();        // 33 airTempInCar  - CABIN
        st.outsideTemp = reply.readInt();      // 34 airTempOutCar - OUTSIDE
        return st;

    }

    /** Outside temperature, raw integer exactly as the service returns it.
     *  No scaling is applied on purpose: the value is logged raw so it can be
     *  compared against what the head unit shows and the scale decided from
     *  evidence rather than guessed. */
    int queryAmbientRaw() {
        if (binder == null) return Integer.MIN_VALUE;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInterfaceToken(TOKEN);
            if (!binder.transact(TX_GET_AMBIENT_TEMP, d, r, 0)) return Integer.MIN_VALUE;
            r.readException();
            int v = r.readInt();
            Log.i(TAG, "queryAmbientRaw -> " + v + " (raw, unscaled)");
            return v;
        } catch (Exception e) {
            Log.w(TAG, "queryAmbientRaw: " + e.getClass().getSimpleName());
            return Integer.MIN_VALUE;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    AirState queryAir() {
        AirState fallback = new AirState();
        if (binder == null) { fallback.setpoint = Float.NaN; return fallback; }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TOKEN);
            if (!binder.transact(TX_GET_AIR_CONDITION, data, reply, 0)) {
                fallback.setpoint = Float.NaN;
                return fallback;
            }
            reply.readException();
            AirState st = readAirState(reply);
            Log.i(TAG, "queryAir: acOn=" + st.acOn + " cabin=" + st.cabinTemp
                    + " outside=" + st.outsideTemp + " setpoint=" + st.setpoint
                    + " wind=" + st.windSpeed);
            return st;
        } catch (Exception e) {
            Log.w(TAG, "queryAir: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            fallback.setpoint = Float.NaN;
            return fallback;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Walks the setpoint up in half-degree steps. The AIDL exposes only
     * setAirLeftTemperatureUp/Down with no absolute setter, so this is the only
     * way to reach a target. The step count is capped so a bad reading cannot
     * drive the setpoint to an extreme. */
}
