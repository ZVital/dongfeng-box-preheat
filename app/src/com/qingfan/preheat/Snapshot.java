package com.qingfan.preheat;

import android.util.Log;

/** What the climate looked like before preheat touched it, so it can be put back.
 *
 * Preheat moves the setpoint by pressing Up/Down, which is a relative action with no
 * absolute setter, so without a snapshot the head unit is left wherever the heating
 * pushed it. Restoring means walking the setpoint back the same number of half-steps.
 *
 * The setpoint itself comes from CanClient.readCabinTemp(), whose float offset is the
 * one unverified thing here. When that read fails the setpoint is NaN and the restore
 * skips the setpoint and says so, rather than guessing and dragging the value further.
 */
final class Snapshot {

    private final boolean acOn;
    private final boolean defrostOn;
    private final float setpoint;

    private Snapshot(boolean acOn, boolean defrostOn, float setpoint) {
        this.acOn = acOn;
        this.defrostOn = defrostOn;
        this.setpoint = setpoint;
    }

    /** Reads current state. Must be called BEFORE anything is changed. */
    static Snapshot capture(CanClient can) {
        float sp = can.readCabinTemp();
        byte[] blob = can.readAirBlob();
        boolean ac = fieldBool(blob, 0);
        boolean def = fieldBool(blob, 4);
        Log.i(CanClient.TAG, "snapshot: ac=" + acOn(ac) + " defrost=" + acOn(def)
                + " setpoint=" + (Float.isNaN(sp) ? "unreadable" : sp + "C")
                + " blob=" + (blob == null ? "none" : blob.length + "B"));
        return new Snapshot(ac, def, sp);
    }

    private static String acOn(boolean b) { return b ? "on" : "off"; }

    /** The reply blob starts with a parcelable header; airSWStatus and the front
     * defroster flag are the first two ints after it. Their exact positions are NOT
     * verified - this returns false when the blob is too short, so a wrong guess
     * degrades to "assume off", which is the safe direction for a restore. */
    private static boolean fieldBool(byte[] blob, int index) {
        if (blob == null || blob.length < 8 + index * 4) return false;
        int off = 4 + index * 4;
        return blob[off] != 0;
    }

    boolean setpointKnown() { return !Float.isNaN(setpoint); }
    boolean acWasOn()    { return acOn; }
    boolean defrostWasOn() { return defrostOn; }
    float setpoint()     { return setpoint; }

    /** Puts the head unit back the way it was, then turns the climate off.
     *  Order matters: the setpoint is walked back while the unit is still running,
     *  because Up/Down only do anything when the climate is on. */
    void restore(CanClient can) {
        if (acOn) {
            Log.i(CanClient.TAG, "restore: AC was on, leaving it on");
        } else {
            can.closeAc();
            Log.i(CanClient.TAG, "restore: AC was off, turning off");
        }

        if (Float.isNaN(setpoint)) {
            Log.w(CanClient.TAG, "restore: setpoint was unreadable, cannot walk it back");
        } else {
            float now = can.readCabinTemp();
            if (Float.isNaN(now)) {
                Log.w(CanClient.TAG, "restore: setpoint now unreadable, skipping");
            } else if (Math.abs(now - setpoint) >= 0.25f) {
                can.setTempTo(now, setpoint);
                Log.i(CanClient.TAG, "restore: setpoint " + now + " -> " + setpoint);
            } else {
                Log.i(CanClient.TAG, "restore: setpoint already at " + setpoint);
            }
        }

        if (!defrostOn && can.isDefrostOn()) can.defrost(false);
        Log.i(CanClient.TAG, "restore done");
    }
}
