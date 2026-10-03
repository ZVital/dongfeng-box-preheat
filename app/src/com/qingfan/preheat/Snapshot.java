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
    private final boolean acCompressor;
    private final float setpoint;
    private final int windSpeed;

    private Snapshot(boolean acOn, boolean acCompressor, float setpoint, int windSpeed) {
        this.acOn = acOn;
        this.acCompressor = acCompressor;
        this.setpoint = setpoint;
        this.windSpeed = windSpeed;
    }

    /** Must be called BEFORE anything is changed. */
    static Snapshot capture(CanClient can) {
        CanClient.AirState st = can.queryAir();
        Log.i(CanClient.TAG, "snapshot: ac=" + yn(st.acOn)
                + " compressor=" + yn(st.acCompressor)
                + " setpoint=" + (Float.isNaN(st.leftTemp) ? "unreadable" : st.leftTemp + "C")
                + " wind=" + st.windSpeed);
        return new Snapshot(st.acOn, st.acCompressor, st.leftTemp, st.windSpeed);
    }

    private static String yn(boolean b) { return b ? "on" : "off"; }

    float setpoint()      { return setpoint; }
    boolean setpointKnown() { return !Float.isNaN(setpoint); }
    boolean acWasOn()      { return acOn; }
    int windSpeed()        { return windSpeed; }

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

        if (!setpointKnown()) {
            Log.w(CanClient.TAG, "restore: setpoint was unreadable, cannot walk it back");
        } else {
            CanClient.AirState now = can.queryAir();
            if (Float.isNaN(now.leftTemp)) {
                Log.w(CanClient.TAG, "restore: setpoint now unreadable, skipping");
            } else if (Math.abs(now.leftTemp - setpoint) >= 0.25f) {
                can.setTempTo(now.leftTemp, setpoint);
                Log.i(CanClient.TAG, "restore: setpoint " + now.leftTemp + " -> " + setpoint);
            } else {
                Log.i(CanClient.TAG, "restore: setpoint already at " + setpoint);
            }
        }
        Log.i(CanClient.TAG, "restore done");
    }
}