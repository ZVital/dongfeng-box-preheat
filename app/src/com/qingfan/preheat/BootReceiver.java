package com.qingfan.preheat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/** Re-arms the alarms after a reboot or an app update, because Android drops all
 * alarms belonging to a killed or replaced package. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        PreheatReceiver.Scheduler.reschedule(ctx.getApplicationContext());
    }
}
