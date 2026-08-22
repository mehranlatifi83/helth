package ir.mehranlatifi83.roozara.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import android.media.AudioManager;

import ir.mehranlatifi83.roozara.manager.ScheduleManager;
import ir.mehranlatifi83.roozara.manager.WaterReminderManager;
import ir.mehranlatifi83.roozara.service.SleepVpnService;
import ir.mehranlatifi83.roozara.util.ActivityLog;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        ActivityLog.log(ctx, "device restarted - restoring the schedule");
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !Intent.ACTION_TIME_CHANGED.equals(action)
                && !Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                && !android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(action)) {
            return;
        }

        // If the phone rebooted mid-sleep, the sleep_active flag is stale.
        // Services are dead after reboot, so clean up silently: restore ringer
        // and clear the flag so the app starts in a consistent state.
        boolean wasSleeping = Intent.ACTION_BOOT_COMPLETED.equals(action)
                && ctx.getSharedPreferences("helth_prefs", Context.MODE_PRIVATE)
                .getBoolean("sleep_active", false);
        if (wasSleeping) {
            ((AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE))
                    .setRingerMode(AudioManager.RINGER_MODE_NORMAL);
            ctx.getSharedPreferences("helth_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("sleep_active", false)
                    .putBoolean("wake_alarm_active", false)
                    .remove(ir.mehranlatifi83.roozara.ui.SleepLockActivity.KEY_SLEEP_START)
                    .apply();
        }

        ScheduleManager.rescheduleIfEnabled(ctx);

        // A reboot/update/time change can occur inside the sleep window. Waiting until
        // tomorrow would leave the phone unprotected for the rest of tonight.
        if (ScheduleManager.isScheduleEnabled(ctx)
                && ScheduleManager.isInsideSleepWindow(ctx)
                && !ctx.getSharedPreferences("helth_prefs", Context.MODE_PRIVATE)
                        .getBoolean("sleep_active", false)) {
            Intent sleepNow = new Intent(ctx, SleepScheduleReceiver.class)
                    .setAction(SleepScheduleReceiver.ACTION_SLEEP);
            ctx.sendBroadcast(sleepNow);
        }

        if (WaterReminderManager.isEnabled(ctx)) {
            WaterReminderManager.scheduleAll(ctx);
        }
    }
}
