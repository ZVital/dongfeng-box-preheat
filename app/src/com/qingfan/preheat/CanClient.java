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
    byte[] readAirBlob() {
        if (binder == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TOKEN);
            if (!binder.transact(TX_GET_AIR_CONDITION, data, reply, 0)) return null;
            reply.readException();
            reply.setDataPosition(0);
            return reply.marshall();
        } catch (Exception e) {
            Log.w(TAG, "readAirBlob: " + e.getClass().getSimpleName());
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Reads the same guessed offset as readCabinTemp but as a flag. Offset is
     * UNVERIFIED - see Snapshot for how a bad guess is handled. */
    boolean isDefrostOn() {
        byte[] b = readAirBlob();
        return b != null && b.length >= 8 && b[8] != 0;
    }

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

    /** Reads the cabin setpoint. Only bytes 1-4 of the reply blob are known to
     * carry airLeftTemperature as a float, so this is best-effort: it returns
     * NaN when the blob does not decode, and the caller then skips the
     * dead-band check instead of guessing. */
    float readCabinTemp() {
        if (binder == null) return Float.NaN;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TOKEN);
            if (!binder.transact(TX_GET_AIR_CONDITION, data, reply, 0)) return Float.NaN;
            reply.readException();
            reply.setDataPosition(0);
            byte[] raw = reply.marshall();
            if (raw.length < 8) return Float.NaN;
            int bits = (raw[4] & 0xff) | ((raw[5] & 0xff) << 8)
                     | ((raw[6] & 0xff) << 16) | ((raw[7] & 0xff) << 24);
            return Float.intBitsToFloat(bits);
        } catch (Exception e) {
            Log.w(TAG, "readCabinTemp: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return Float.NaN;
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
