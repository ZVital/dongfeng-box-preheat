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
    static final int TX_OPEN_AC = 88;
    static final int TX_CLOSE_AC = 89;
    static final int TX_OPEN_DEFROST = 92;
    static final int TX_CLOSE_DEFROST = 93;
    static final int TX_TEMP_UP = 94;
    static final int TX_TEMP_DOWN = 95;

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

    boolean openAc()  { boolean r = callBoolean(TX_OPEN_AC);  Log.i(TAG, "openAirConditionSwitch -> " + r);  return r; }
    boolean closeAc() { boolean r = callBoolean(TX_CLOSE_AC); Log.i(TAG, "closeAirConditionSwitch -> " + r); return r; }
    boolean defrost(boolean on) {
        boolean r = callBoolean(on ? TX_OPEN_DEFROST : TX_CLOSE_DEFROST);
        Log.i(TAG, "front defroster " + (on ? "on" : "off") + " -> " + r);
        return r;
    }
    boolean tempUp()   { return callBoolean(TX_TEMP_UP); }
    boolean tempDown() { return callBoolean(TX_TEMP_DOWN); }

    /** Climate state as delivered by onAirConditionChanged / getAirCondition.
     *
     * Field order is taken from AirCondition.writeToParcel in the stock launcher's
     * copy of the class - ten ints, then the floats. The earlier version guessed a
     * float at bytes 4..7; it is actually slot 10, byte 40. Read as a Parcel stream
     * in the same order the generated Stub uses, not by byte arithmetic. */
    static final class AirState {
        boolean acOn;          // airSWStatus
        boolean acCompressor;  // airACStatus
        float leftTemp;        // airLeftTemperature
        float rightTemp;
        float rearTemp;
        int windSpeed;
        int supply;
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

    static AirState readAirState(Parcel reply) {
        AirState st = new AirState();
        st.acOn = reply.readInt() != 0;      // airSWStatus
        reply.readInt();                       // airACStatus
        st.acCompressor = reply.readInt() != 0;
        reply.readInt();                       // airHighWindStatus
        reply.readInt();                       // airLowWindStatus
        reply.readInt();                       // airDUALStatus
        reply.readInt();                       // airMaxFrontStatus
        reply.readInt();                       // airRearLightStatus
        st.supply = reply.readInt();           // airSupplyStatus
        reply.readInt();                       // airDisplaySW
        st.windSpeed = reply.readInt();        // airWindSpeed
        st.leftTemp = reply.readFloat();       // airLeftTemperature
        st.rightTemp = reply.readFloat();
        st.rearTemp = reply.readFloat();
        return st;
    }

    AirState queryAir() {
        AirState fallback = new AirState();
        if (binder == null) { fallback.leftTemp = Float.NaN; return fallback; }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TOKEN);
            if (!binder.transact(TX_GET_AIR_CONDITION, data, reply, 0)) {
                fallback.leftTemp = Float.NaN;
                return fallback;
            }
            reply.readException();
            AirState st = readAirState(reply);
            Log.i(TAG, "queryAir: acOn=" + st.acOn + " leftTemp=" + st.leftTemp
                    + " wind=" + st.windSpeed);
            return st;
        } catch (Exception e) {
            Log.w(TAG, "queryAir: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            fallback.leftTemp = Float.NaN;
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
    boolean setTempTo(float current, float target) {
        int steps = Math.round((target - current) * 2f);
        if (steps > 40) steps = 40;
        if (steps < -40) steps = -40;
        Log.i(TAG, "temp " + current + " -> " + target + " (" + steps + " steps)");
        for (int i = 0; i < Math.abs(steps); i++) {
            boolean ok = steps > 0 ? tempUp() : tempDown();
            if (!ok) { Log.w(TAG, "temp step failed at " + i); return false; }
        }
        return true;
    }
}
