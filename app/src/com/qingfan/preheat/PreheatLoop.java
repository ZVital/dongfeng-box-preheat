package com.qingfan.preheat;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

/** In-process watchdog that runs while preheat is active.
 *
 * The alarm is only needed to WAKE the system - once the head unit is awake and
 * this process is alive, the re-check is a plain program timer rather than a second
 * alarm. The existing STOP alarm is the backstop: if the process is ever frozen the
 * timer stops with it, and the STOP alarm still shuts the climate down, just later.
 *
 * The plausible-range guard is no longer about an unverified read: field order comes
 * from the stock AirCondition.writeToParcel, so the parse is exact. It stays as a
 * cheap sanity check because a misparsed Parcel would otherwise silently steer the
 * setpoint, and the setpoint is only moved relatively. */
final class PreheatLoop {

    private static final long PERIOD_MS = 600_000L;
    private static final float PLAUSIBLE_MIN = -40f;
    private static final float PLAUSIBLE_MAX = 70f;
    private static final int STEP_PER_TICK = 2;

    private final ScheduleStore store;
    private final CanClient can;
    private final HandlerThread thread;
    private final Handler handler;

    private boolean running;
    private int rejected;
    private Snapshot snap;

    private final Runnable tick = new Runnable() {
        public void run() {
            if (!running) return;
            try {
                step();
            } catch (Throwable t) {
                Log.e(CanClient.TAG, "loop tick failed", t);
            }
            if (running) handler.postDelayed(this, PERIOD_MS);
        }
    };

    private PreheatLoop(ScheduleStore store, CanClient can, Snapshot snap) {
        this.store = store;
        this.can = can;
        this.snap = snap;
        thread = new HandlerThread("preheat-loop");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    private static PreheatLoop active;

    static synchronized void start(Context ctx, CanClient can, Snapshot snap) {
        stop();
        ScheduleStore store = new ScheduleStore(ctx);
        active = new PreheatLoop(store, can, snap);
        active.running = true;
        active.handler.post(active.tick);
        Log.i(CanClient.TAG, "loop started, period " + PERIOD_MS + "ms");
    }

    /** Stops ticking and puts the settings back. Called both when the target is
     * reached and when the STOP alarm fires, so the restore path is identical. */
    static synchronized void finish(CanClient can) {
        if (active == null) { can.closeAc(); return; }
        PreheatLoop l = active;
        active = null;
        l.running = false;
        l.handler.removeCallbacks(l.tick);
        l.thread.quit();
        if (l.snap != null) l.snap.restore(can);
        else { can.closeAc(); if (l.store.defrost()) can.defrost(false); }
        Log.i(CanClient.TAG, "loop finished");
    }

    static synchronized void stop() {
        if (active == null) return;
        active.running = false;
        active.handler.removeCallbacks(active.tick);
        active.thread.quit();
        active = null;
    }

    private void step() {
        float cabin = can.queryAir().leftTemp;

        if (Float.isNaN(cabin)) {
            Log.w(CanClient.TAG, "tick: cabin unreadable, skipping");
            return;
        }
        if (cabin < PLAUSIBLE_MIN || cabin > PLAUSIBLE_MAX) {
            rejected++;
            Log.w(CanClient.TAG, "tick: cabin " + cabin + "C outside plausible range, rejected #" + rejected);
            return;
        }
        if (rejected > 0) { rejected = 0; Log.i(CanClient.TAG, "tick: cabin reading sane again: " + cabin + "C"); }

        if (cabin >= store.target() || cabin >= store.bandHi()) {
            Log.i(CanClient.TAG, "tick: cabin " + cabin + "C reached target "
                    + store.target() + " / band " + store.bandHi() + ", restoring");
            // Must go through finish(), not closeAc(): finish() restores the
            // snapshot. Calling closeAc() here left the setpoint wherever the
            // heating had walked it. This was the bug.
            finish(can);
            return;
        }

        Log.i(CanClient.TAG, "tick: cabin " + cabin + "C, holding");
    }
}
