package com.qingfan.preheat;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/** Fires at the scheduled times and drives the climate.
 *
 * The head unit suspends to RAM rather than powering off (/sys/power/state contains
 * "freeze mem"), so an RTC alarm wakes it with memory intact. setAlarmClock is used
 * rather than setExactAndAllowWhileIdle because on Android 8.1 the latter gives no
 * guarantee in deep sleep, while setAlarmClock is the strongest alarm type the system
 * has and is the least likely to be trimmed by the OEM build.
 *
 * Every step logs under the Preheat tag so whether the alarm actually fired can be
 * checked from logcat without anyone having to go out to the car.
 */
public class PreheatReceiver extends BroadcastReceiver {

    static final String ACTION_START = "com.qingfan.preheat.START";
    static final String ACTION_STOP = "com.qingfan.preheat.STOP";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        Log.i(CanClient.TAG, "ALARM FIRED action=" + action
                + " at " + android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss",
                        System.currentTimeMillis())
                + " (uptime " + android.os.SystemClock.elapsedRealtime() / 1000 + "s)");

        final Context app = ctx.getApplicationContext();
        final ScheduleStore store = new ScheduleStore(app);
        final boolean start = ACTION_START.equals(action);

        final CanClient can = new CanClient(app);
        CanCallback cb = new CanCallback(new CanCallback.Listener() {
            public void onAirCondition(boolean acOn, int cabinTemp, float setpoint) {
                // Primary stop path. PreheatLoop holds the snapshot, so ask it
                // whether it is running - the receiver no longer carries one.
                if (!PreheatLoop.isActive()) return;
                if (!acOn) {
                    Log.i(CanClient.TAG, "callback: AC switched off while preheat ran -> finishing");
                    PreheatLoop.finish(can);
                    return;
                }
                // leftTemp is the setpoint, so it cannot signal "cabin reached target".
                // Only the owner's own A/C-off is acted on here.
            }
            public void onAccChanged(int acc) {
                Log.i(CanClient.TAG, "callback: acc state = " + acc);
            }
            public void onVehicleStateResponse(boolean accepted) {
                Log.i(CanClient.TAG, "callback: vehicle state accepted = " + accepted);
            }
        });
        CB.set(cb);

        if (start) {
            // Single implementation shared with the ПРОВЕРИТЬ button.
            PreheatStarter.start(app, can, cb, store.target(), store.isAuto(), store.defrost());
        } else {
            Log.i(CanClient.TAG, "STOP fired, restoring settings");
            PreheatLoop.finish(can);
            Log.i(CanClient.TAG, "STOP done. " + store.describe());
        }
    }

    /** Held between start and finish so the callback can reach the same CanClient. */
    private static final class CB {
        static CanCallback value;
        static void set(CanCallback c) { value = c; }
        static CanCallback get() { return value; }
    }


    /** Schedules both slots. Re-arming always replaces the previous alarms, so the
     * stored schedule and the armed alarms can never drift apart. */
    static final class Scheduler {
        static void reschedule(Context ctx) {
            ScheduleStore store = new ScheduleStore(ctx);
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) { Log.w(CanClient.TAG, "no AlarmManager"); return; }

            PendingIntent oldStart = pi(ctx, ACTION_START, 0);
            PendingIntent oldStop = pi(ctx, ACTION_STOP, 0);
            am.cancel(oldStart);
            am.cancel(oldStop);

            long start = store.nextStartMillis();
            long stop = store.nextStopMillis();
            Log.i(CanClient.TAG, "reschedule: " + store.describe()
                    + " nextStart=" + fmt(start) + " nextStop=" + fmt(stop));

            if (start > 0) {
                PendingIntent p = pi(ctx, ACTION_START, start);
                if (Build.VERSION.SDK_INT >= 21)
                    am.setAlarmClock(new AlarmManager.AlarmClockInfo(start, pi(ctx, ACTION_START, 0)), p);
                else
                    am.setExact(AlarmManager.RTC_WAKEUP, start, p);
            }
            if (stop > 0) {
                PendingIntent p = pi(ctx, ACTION_STOP, stop);
                if (Build.VERSION.SDK_INT >= 21)
                    am.setAlarmClock(new AlarmManager.AlarmClockInfo(stop, pi(ctx, ACTION_STOP, 0)), p);
                else
                    am.setExact(AlarmManager.RTC_WAKEUP, stop, p);
            }
        }

        private static PendingIntent pi(Context ctx, String action, long data) {
            Intent i = new Intent(action).setPackage(ctx.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            return PendingIntent.getBroadcast(ctx, action.hashCode(), i, flags);
        }

        private static String fmt(long t) {
            return t <= 0 ? "none"
                 : android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", t).toString();
        }
    }
}
