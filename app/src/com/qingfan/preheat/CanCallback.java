package com.qingfan.preheat;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/** Registers as a CAN-service client so climate changes arrive as events.
 *
 * The stock app reacts instantly when the A/C is switched off from the wheel, and
 * the service does that by pushing: ICanBusServiceCallback has 68 methods including
 * onAirConditionChanged(AirCondition), onAccStateChanged(int) and
 * onVehicleStateSettingResponse(VehicleState, boolean). Registering costs one binder
 * round-trip via addCallback (transaction 27) and replaces per-minute polling.
 *
 * The callback object is hand-written because this project builds without AIDL
 * tooling. The service calls it through transact(code, data, reply, 0), so onTransact
 * sees a reply-mode Parcel: the interface token is absent, and for a Parcelable
 * argument the layout is an int presence flag followed by the object's own fields.
 * Only the callbacks we actually consume are decoded; everything else replies
 * straight back so the service never sees an unanswered synchronous call.
 *
 * Transaction numbers below are the low 8 bits of each method's one-way AIDL code,
 * which the generated Stub uses as its switch case.
 */
final class CanCallback extends Binder {

    interface Listener {
        void onAirCondition(boolean acOn, int cabinTemp, float setpoint);
        void onAccChanged(int acc);
        void onVehicleStateResponse(boolean accepted);
    }

    private final Listener listener;
    private boolean registered;

    CanCallback(Listener listener) {
        this.listener = listener;
    }

    boolean isRegistered() {
        return registered;
    }

    void register(CanClient can) {
        if (registered || !can.isBound()) return;
        can.callRegister(this);
        registered = true;
        Log.i(CanClient.TAG, "callback registered");
    }

    void unregister(CanClient can) {
        if (!registered) return;
        can.callUnregister(this);
        registered = false;
        Log.i(CanClient.TAG, "callback unregistered");
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        try {
            // Every code goes to the measuring path. The previous cases were
            // guesses (4 = onAirConditionChanged, 3 = onAccStateChanged,
            // 40 = onVehicleStateSettingResponse) and none was ever verified.
            // Code 4 is now known to be wrong: toggling the climate produced 16,
            // not 4. A wrong case silently swallows the event and stops it from
            // ever being identified, so there are no cases at all - just measure.
            if (reportOnce(code)) dump(code, data, flags);
            if (reply != null) reply.writeNoException();
            return true;
        } catch (Exception e) {
            Log.e(CanClient.TAG, "callback onTransact code=" + code, e);
            return true;
        }
    }

    private static String DESCRIPTOR = "com.qinggan.canbus.ICanBusServiceCallback";
    private final java.util.Map<Integer, Integer> reported = new java.util.HashMap<Integer, Integer>();

    /** Best-effort dump of a callback payload.
     *
     *  Everything is guarded and the parcel position is always restored, because a
     *  throw here would leave the transaction unanswered and stall the service.
     *  The intent log tries to skip the interface token like AIDL does, then reports
     *  both readings: as a string plus four ints, and as raw bytes. Whichever is
     *  meaningful identifies the event. */
    private void dump(int code, Parcel data, int flags) {
        int start = data.dataPosition();
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("code=").append(code).append(" flags=").append(flags)
              .append(" size=").append(data.dataSize()).append(" |");
            try {
                data.enforceInterface(DESCRIPTOR);
                sb.append(" token=ok");
            } catch (Exception e) {
                sb.append(" token=").append(e.getClass().getSimpleName());
                data.setDataPosition(start);
            }
            for (int i = 0; i < 4; i++) {
                int v = data.readInt();
                sb.append(" int[").append(i).append("]=").append(v);
            }
            Log.i(CanClient.TAG, "  dump " + sb);
        } catch (Throwable t) {
            Log.w(CanClient.TAG, "  dump code=" + code + " " + t.getClass().getSimpleName());
        } finally {
            try { data.setDataPosition(start); } catch (Throwable ignored) { }
        }
        try {
            byte[] raw = data.marshall();
            int n = Math.min(raw.length, 40);
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < n; i++) hex.append(String.format("%02x ", raw[i]));
            Log.i(CanClient.TAG, "  raw code=" + code + " (" + raw.length + "B): " + hex);
        } catch (Throwable t) {
            Log.w(CanClient.TAG, "  raw code=" + code + " unavailable");
        }
    }

    /** @return true for the first few times a code is seen.
     *
     *  Not just once: the climate callback fires on every state change, and one
     *  dump taken at the wrong moment identifies nothing. Three is enough to see
     *  the value before and after a toggle without flooding the log. */
    private boolean reportOnce(int code) {
        Integer n = reported.get(code);
        if (n != null && n >= 3) return false;
        reported.put(code, n == null ? 1 : n + 1);
        Log.i(CanClient.TAG, "callback: undecoded transaction code " + code
                + " (dump " + (n == null ? 1 : n + 1) + " of 3)");
        return true;
    }
}