package ir.mehranlatifi83.roozara.receiver;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;

import java.util.Calendar;

import ir.mehranlatifi83.roozara.R;
import ir.mehranlatifi83.roozara.util.ActivityLog;
import ir.mehranlatifi83.roozara.manager.WaterReminderManager;
import ir.mehranlatifi83.roozara.ui.WaterActivity;
import ir.mehranlatifi83.roozara.ui.WaterOverlayActivity;

public class WaterReminderReceiver extends BroadcastReceiver {

    public  static final String ACTION_WATER = "ir.mehranlatifi83.roozara.ACTION_WATER";
    public  static final String EXTRA_SLOT   = "slot";
    public  static final String EXTRA_HOUR   = "hour";
    public  static final String EXTRA_MIN    = "min";

    private static final String CHANNEL_ID = "water_reminder_channel";

    private static final int[] TITLES = {
        R.string.water_reminder_title_0, R.string.water_reminder_title_1,
        R.string.water_reminder_title_2, R.string.water_reminder_title_3,
        R.string.water_reminder_title_4, R.string.water_reminder_title_5,
        R.string.water_reminder_title_6, R.string.water_reminder_title_7,
    };

    private static final int[] TEXTS = {
        R.string.water_reminder_text_0, R.string.water_reminder_text_1,
        R.string.water_reminder_text_2, R.string.water_reminder_text_3,
        R.string.water_reminder_text_4, R.string.water_reminder_text_5,
        R.string.water_reminder_text_6, R.string.water_reminder_text_7,
    };

    @Override
    public void onReceive(Context ctx, Intent intent) {
        ActivityLog.log(ctx, "water reminder fired");
        if (!ACTION_WATER.equals(intent.getAction())) return;
        if (!WaterReminderManager.isEnabled(ctx)) return;

        int slot = intent.getIntExtra(EXTRA_SLOT, 0);
        int h    = intent.getIntExtra(EXTRA_HOUR, 0);
        int m    = intent.getIntExtra(EXTRA_MIN,  0);

        boolean canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(ctx);

        if (canOverlay) {
            ctx.startActivity(new Intent(ctx, WaterOverlayActivity.class)
                    .putExtra(EXTRA_SLOT, slot)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_NO_HISTORY));
        } else {
            showNotification(ctx, slot);
        }

        // Reschedule for exactly tomorrow at the same h:m (not relative to "now" to avoid drift).
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, h);
        cal.set(Calendar.MINUTE, m);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.DAY_OF_YEAR, 1);
        long tomorrow = cal.getTimeInMillis();
        android.app.AlarmManager am =
                (android.app.AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        Intent reschedule = new Intent(ctx, WaterReminderReceiver.class)
                .setAction(ACTION_WATER)
                .putExtra(EXTRA_SLOT, slot)
                .putExtra(EXTRA_HOUR, h)
                .putExtra(EXTRA_MIN,  m);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 200 + slot, reschedule,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, tomorrow, pi);
        } catch (SecurityException ignored) {
            // Exact alarm permission revoked; tomorrow's alarm will not fire
        }
    }

    static void showNotification(Context ctx, int slot) {
        ensureChannel(ctx);

        int safeSlot = (slot >= 0 && slot < WaterReminderManager.COUNT) ? slot : 0;

        PendingIntent openApp = PendingIntent.getActivity(ctx, 300 + slot,
                new Intent(ctx, WaterActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        ctx.getSystemService(NotificationManager.class).notify(100 + slot,
                new NotificationCompat.Builder(ctx, CHANNEL_ID)
                        .setContentTitle(ctx.getString(TITLES[safeSlot]))
                        .setContentText(ctx.getString(TEXTS[safeSlot]))
                        .setStyle(new NotificationCompat.BigTextStyle()
                                .bigText(ctx.getString(TEXTS[safeSlot])))
                        .setSmallIcon(R.drawable.ic_water)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setContentIntent(openApp)
                        .setAutoCancel(true)
                        .build());
    }

    private static void ensureChannel(Context ctx) {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.water_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        ctx.getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }
}
