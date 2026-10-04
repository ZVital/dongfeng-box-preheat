package com.qingfan.preheat;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

/** Schedule storage. Plain SharedPreferences: no permission, survives reboot, and the
 * head unit has no app-private-filesystem restrictions to work around. */
final class ScheduleStore {

    private static final String F = "preheat";
    private static final String K_ENABLED = "enabled";
    private static final String K_AUTO = "auto";   // 1 = отдать контур машине
    private static final String K_START_H = "start_h";
    private static final String K_START_M = "start_m";
    private static final String K_STOP_H = "stop_h";
    private static final String K_STOP_M = "stop_m";
    private static final String K_REPEAT = "repeat";       // 0 once, 1 daily, 2 custom
    private static final String K_DAYS = "days";           // bitmask, bit0 = Monday
    private static final String K_TARGET = "target";       // degC
    private static final String K_BAND_LO = "band_lo";
    private static final String K_BAND_HI = "band_hi";
    private static final String K_DEFROST = "defrost";

    private final SharedPreferences sp;

    ScheduleStore(Context ctx) {
        sp = ctx.getSharedPreferences(F, Context.MODE_PRIVATE);
    }

    boolean isEnabled()      { return sp.getBoolean(K_ENABLED, false); }
    /** AUTO: the vehicle's own ECU runs the closed loop. Off: we drive the
     *  setpoint ourselves with Up/Down steps. */
    boolean isAuto()         { return sp.getBoolean(K_AUTO, false); }
    int startHour()          { return sp.getInt(K_START_H, 7); }
    int startMinute()        { return sp.getInt(K_START_M, 0); }
    int stopHour()           { return sp.getInt(K_STOP_H, 8); }
    int stopMinute()         { return sp.getInt(K_STOP_M, 0); }
    int repeat()             { return sp.getInt(K_REPEAT, 2); }
    int days()               { return sp.getInt(K_DAYS, 0b0011111); }
    float target()           { return sp.getFloat(K_TARGET, 22.0f); }
    float bandLo()           { return sp.getFloat(K_BAND_LO, 5.0f); }
    float bandHi()           { return sp.getFloat(K_BAND_HI, 12.0f); }
    boolean defrost()        { return sp.getBoolean(K_DEFROST, true); }

    void save(boolean enabled, int sh, int sm, int ph, int pm,
              int repeat, int days, float target,
              float bandLo, float bandHi, boolean defrost, boolean auto) {
        sp.edit()
          .putBoolean(K_ENABLED, enabled)
          .putBoolean(K_AUTO, auto)
          .putInt(K_START_H, sh).putInt(K_START_M, sm)
          .putInt(K_STOP_H, ph).putInt(K_STOP_M, pm)
          .putInt(K_REPEAT, repeat).putInt(K_DAYS, days)
          .putFloat(K_TARGET, target)
          .putFloat(K_BAND_LO, bandLo).putFloat(K_BAND_HI, bandHi)
          .putBoolean(K_DEFROST, defrost)
          .apply();
    }

    /** True when the schedule should fire today. */
    boolean runsToday() {
        if (!isEnabled()) return false;
        if (repeat() == 1) return true;
        if (repeat() == 0) return true;   // "once" still fires on the next matching slot
        // Calendar.MONDAY == 2, our bit0 == Monday
        int bit = 1 << (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 2);
        return (days() & bit) != 0;
    }

    /** Next firing time in epoch millis, or -1 if the schedule is off. */
    long nextStartMillis() {
        if (!isEnabled()) return -1;
        Calendar c = nextSlot(startHour(), startMinute());
        return c == null ? -1 : c.getTimeInMillis();
    }

    long nextStopMillis() {
        if (!isEnabled()) return -1;
        Calendar c = nextSlot(stopHour(), stopMinute());
        return c == null ? -1 : c.getTimeInMillis();
    }

    private Calendar nextSlot(int hour, int minute) {
        Calendar now = Calendar.getInstance();
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now.getTimeInMillis()) {
            if (repeat() == 0) return null;          // one-shot already passed
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        // For a daily/custom schedule, skip forward to a day that is actually enabled.
        for (int i = 0; i < 8; i++) {
            if (repeat() == 2) {
                int bit = 1 << (c.get(Calendar.DAY_OF_WEEK) - 2);
                if ((days() & bit) != 0) return c;
            } else {
                return c;
            }
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return null;
    }

    String describe() {
        return (isEnabled() ? "вкл" : "выкл")
             + "  старт " + fmt(startHour()) + ":" + fmt(startMinute())
             + "  стоп " + fmt(stopHour()) + ":" + fmt(stopMinute())
             + "  цель " + target() + "C"
             + "  зона " + bandLo() + ".." + bandHi()
             + (isAuto() ? "  [AUTO]" : "");
    }

    private static String fmt(int v) { return v < 10 ? "0" + v : String.valueOf(v); }
}
