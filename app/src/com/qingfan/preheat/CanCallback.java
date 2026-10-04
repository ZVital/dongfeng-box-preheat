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
            switch (code) {
                case 4: { // onAirConditionChanged(AirCondition)
                    data.enforceInterface(DESCRIPTOR);
                    if (data.readInt() != 0) {
                        CanClient.AirState st = CanClient.readAirState(data);
                        listener.onAirCondition(st.acOn, st.cabinTemp, st.setpoint);
                    }
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                case 3:   // onAccStateChanged(int)
                    data.enforceInterface(DESCRIPTOR);
                    listener.onAccChanged(data.readInt());
                    return true;
                case 40:  // onVehicleStateSettingResponse(VehicleState, boolean)
                    data.enforceInterface(DESCRIPTOR);
                    if (data.readInt() != 0) data.readInt();   // VehicleState, unused
                    listener.onVehicleStateResponse(data.readInt() != 0);
                    if (reply != null) reply.writeNoException();
                    return true;
                default:
                    // Log the code instead of guessing at it: the mapping from AIDL
                    // method index to transaction number is not recorded anywhere, so
                    // the first real run tells us what the rest are.
                    if (!reportOnce(code)) return true;
                    if (reply != null) reply.writeNoException();
                    return true;
            }
        } catch (Exception e) {
            Log.e(CanClient.TAG, "callback onTransact code=" + code, e);
            return true;
        }
    }

    private static String DESCRIPTOR = "com.qinggan.canbus.ICanBusServiceCallback";
    private final java.util.Set<Integer> reported = new java.util.HashSet<Integer>();

    /** @return true the first time this code is seen, so each one logs only once. */
    private boolean reportOnce(int code) {
        if (!reported.add(code)) return false;
        Log.i(CanClient.TAG, "callback: unhandled transaction code " + code
                + " - acknowledging without decoding");
        return true;
    }
}