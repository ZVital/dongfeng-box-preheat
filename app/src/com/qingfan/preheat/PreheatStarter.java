package com.qingfan.preheat;

import android.content.Context;
import android.util.Log;

/** The one sequence that starts preheat.
 *
 *  Both entry points go through here on purpose: the alarm in PreheatReceiver and
 *  the ПРОВЕРИТЬ button used to be separate code, and they drifted - the button
 *  never touched the setpoint and ignored the AUTO switch. One implementation
 *  makes that class of divergence impossible.
 *
 *  Values are passed in rather than read from the store, so the button can run
 *  with what is currently on screen instead of the last saved values.
 */
final class PreheatStarter {

    static void start(final Context ctx, final CanClient can, final CanCallback cb,
                      float target, boolean auto, boolean defrost) {
        final ScheduleStore store = new ScheduleStore(ctx);
        final Snapshot[] holder = new Snapshot[1];

        can.bind(new CanClient.Ready() { public void onBound() {
            // Subscribe BEFORE starting anything: the owner switching the climate
            // off is a stop signal, and we only see it if the callback is live.
            if (cb != null) cb.register(can);
            try {
                run(ctx, can, store, holder, target, auto, defrost);
            } catch (Throwable t) {
                Log.e(CanClient.TAG, "preheat start failed", t);
            }
        }});
    }

    private static void run(Context ctx, CanClient can, ScheduleStore store,
                            Snapshot[] holder, float target, boolean auto, boolean defrost) {
        Snapshot snap = Snapshot.capture(can, target);
        holder[0] = snap;
        Log.i(CanClient.TAG, "START " + store.describe()
                + " target=" + target + " auto=" + auto + " defrost=" + defrost
                + " | салон=" + can.queryAir().cabinTemp
                + " уставка=" + can.queryAir().setpoint);

        if (auto) {
            // AUTO only hands over the blower-speed loop. The target temperature is
            // still ours to set - without this the cabin is warmed toward whatever
            // was left over from before. Same direct path as plain mode.
            can.autoMode();
            float autoSetpoint = can.queryAir().setpoint;
            if (!Float.isNaN(autoSetpoint) && Math.abs(autoSetpoint - target) >= 0.25f) {
                can.setTempDirect(target);
            } else {
                Log.i(CanClient.TAG, "setpoint already " + autoSetpoint + ", unchanged");
            }
            if (defrost) can.defrost(true);
            PreheatLoop.begin(ctx, can, snap);
            Log.i(CanClient.TAG, "START done (AUTO)");
            return;
        }

        // Plain mode. Both of these are required before the setpoint will move:
        // AUTO must be off (the car owns the setpoint while it is on) and the
        // climate must be reported running (S31 substitutes 25.0C for the step
        // while AirSWStatus is off, yet still returns true).
        can.closeAutoMode();
        can.openAc();
        if (!can.awaitAcOn(6000)) {
            Log.w(CanClient.TAG, "climate never reported running, aborting start");
            snap.restore(can);
            return;
        }

        float setpoint = can.queryAir().setpoint;
        if (!Float.isNaN(setpoint) && Math.abs(setpoint - target) >= 0.25f) {
            can.setTempDirect(target);
        } else {
            Log.i(CanClient.TAG, "setpoint already " + setpoint + ", unchanged");
        }

        if (defrost) can.defrost(true);
        PreheatLoop.begin(ctx, can, snap);
        Log.i(CanClient.TAG, "START done");
    }
}
