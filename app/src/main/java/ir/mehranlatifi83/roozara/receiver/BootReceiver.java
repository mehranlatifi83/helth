package ir.mehranlatifi83.roozara.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import ir.mehranlatifi83.roozara.manager.ScheduleManager;
import ir.mehranlatifi83.roozara.manager.SleepModeController;
import ir.mehranlatifi83.roozara.manager.WaterReminderManager;
import ir.mehranlatifi83.roozara.util.ActivityLog;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !Intent.ACTION_TIME_CHANGED.equals(action)
                && !Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                && !android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(action)) {
            return;
        }
        // Logged after the filter, so the file says what actually happened rather than
        // claiming a restart for every unrelated broadcast that reaches this receiver.
        ActivityLog.log(ctx, "restoring the schedule", "trigger=" + action);

        // If the phone rebooted mid-sleep, the sleep_active flag is stale.
        // Services are dead after reboot, so clean up silently: restore ringer
        // and clear the flag so the app starts in a consistent state.
        boolean wasSleeping = Intent.ACTION_BOOT_COMPLETED.equals(action)
                && ctx.getSharedPreferences("helth_prefs", Context.MODE_PRIVATE)
                .getBoolean("sleep_active", false);
        if (wasSleeping) {
            ActivityLog.log(ctx, "phone rebooted during sleep - cleaning up");
            // Goes through the controller so the ringer returns to whatever it was
            // before bedtime. Forcing NORMAL here meant a reboot mid-sleep switched the
            // ringer on for anyone who keeps their phone on vibrate.
            SleepModeController.releaseSystemState(ctx, "reboot_during_sleep");
            ctx.getSharedPreferences("helth_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("wake_alarm_active", false)
                    .apply();
        }

        ScheduleManager.rescheduleIfEnabled(ctx);

        // A reboot/update/time change can occur inside the sleep window. Waiting until
        // tomorrow would leave the phone unprotected for the rest of tonight.
        if (ScheduleManager.isScheduleEnabled(ctx)
                && ScheduleManager.isInsideSleepWindow(ctx)
                // A night the user already earned their way out of stays finished. Without
                // this, an early exit followed by a reboot — or by the phone simply being
                // restarted later that evening — dropped them straight back into the lock
                // screen they had just passed a challenge to leave.
                && !SleepModeController.wasCycleLeftEarly(ctx)
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
