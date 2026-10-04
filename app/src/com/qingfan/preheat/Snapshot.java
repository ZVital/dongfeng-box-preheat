package com.qingfan.preheat;

import android.util.Log;

/** What the climate looked like before preheat touched it, so it can be put back.
 *
 * Preheat moves the setpoint by pressing Up/Down, which is a relative action with no
 * absolute setter, so without a snapshot the head unit is left wherever the heating
 * pushed it. Restoring means walking the setpoint back the same number of half-steps.
 *
 * All state comes from CanClient.queryAir(), which parses the Parcel in the field
 * order taken from the stock AirCondition.writeToParcel. Nothing here is guessed.
 */
final class Snapshot {

    private final boolean acOn;
    private final float setpoint;
    private final float target;

    private Snapshot(boolean acOn, float setpoint, float target) {
        this.acOn = acOn;
        this.setpoint = setpoint;
        this.target = target;
    }

    /** Must be called BEFORE anything is changed. */
    static Snapshot capture(CanClient can, float target) {
        CanClient.AirState st = can.queryAir();
        Log.i(CanClient.TAG, "snapshot: ac=" + yn(st.acOn)
                + " leftTemp=" + (Float.isNaN(st.leftTemp) ? "unreadable" : st.leftTemp + "C")
                + " target=" + target + "C");
        return new Snapshot(st.acOn, st.leftTemp, target);
    }

    float targetC() { return target; }

    private static String yn(boolean b) { return b ? "on" : "off"; }

    boolean setpointKnown() { return !Float.isNaN(setpoint); }

    /** Puts the head unit back the way it was, then turns the climate off.
     *
     *  Order matters: the setpoint is walked back while the unit is still running,
     *  because Up/Down only do anything while the climate is on.
     *
     *  Restoring never re-opens the climate. If it was off before preheat - the usual
     *  case, since preheat is what turns it on - then closing it is also what the
     *  owner just did by pressing A/C off from the wheel, so the two agree and we do
     *  not fight them. If it was already on, leaving it alone is correct too.
     */
    void restore(CanClient can) {
        if (acOn) {
            Log.i(CanClient.TAG, "restore: AC was already on, not switching it");
        } else {
            can.closeAc();
            Log.i(CanClient.TAG, "restore: AC was off, closed");
        }

        // The setpoint is never moved by preheat, so there is nothing to walk back.
        // Only the climate on/off state was changed, and that is restored above.
        Log.i(CanClient.TAG, "restore done");
    }
}