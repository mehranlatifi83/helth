package ir.mehranlatifi83.roozara.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import ir.mehranlatifi83.roozara.R;
import ir.mehranlatifi83.roozara.util.ActivityLog;
import ir.mehranlatifi83.roozara.ui.MainActivity;
import ir.mehranlatifi83.roozara.ui.SleepLockActivity;

public class WakeAlarmService extends Service {

    private static final String TAG        = "WakeAlarmService";
    private static final String CHANNEL_ID = "wake_alarm_channel";
    private static final int    NOTIF_ID   = 4;

    public static final String ACTION_DISMISS       = "ir.mehranlatifi83.roozara.ACTION_DISMISS_WAKE";
    public static final String PREF_SOUND_URI       = "alarm_sound_uri";
    public static final String KEY_WAKE_ALARM_ACTIVE = "wake_alarm_active";

    private MediaPlayer player;
    private boolean     cleanupDone = false;

    // ─── Service lifecycle ───────────────────────────────────────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureChannel();
        startForeground(NOTIF_ID, buildNotification());

        if (ACTION_DISMISS.equals(intent != null ? intent.getAction() : null)) {
            // Stop alarm immediately — don't wait for onDestroy.
            stopAlarm();
            cleanupDone = true;
            doFullSleepCleanup();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        // Mark wake alarm active so SleepLockActivity knows to show the challenge on start.
        getSharedPreferences("helth_prefs", MODE_PRIVATE)
                .edit().putBoolean(KEY_WAKE_ALARM_ACTIVE, true).apply();

        playAlarm();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Safety net: only clean up if the dismiss path didn't already do it.
        stopAlarm();
        if (!cleanupDone) doFullSleepCleanup();
        getSystemService(NotificationManager.class).cancel(NOTIF_ID);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ─── Static helpers ───────────────────────────────────────────────────────

    public static void start(Context ctx) {
        ActivityLog.log(ctx, "wake alarm starting");
        ctx.startForegroundService(new Intent(ctx, WakeAlarmService.class));
    }

    public static void stop(Context ctx) {
        ActivityLog.log(ctx, "wake alarm stopped");
        try {
            ctx.startForegroundService(
                    new Intent(ctx, WakeAlarmService.class).setAction(ACTION_DISMISS));
        } catch (Exception e) {
            ctx.stopService(new Intent(ctx, WakeAlarmService.class));
        }
    }

    // ─── Alarm sound ─────────────────────────────────────────────────────────

    private void playAlarm() {
        // If already playing, skip — prevents a double-start from briefly stopping
        // and restarting audio when both CountDownTimer and AlarmManager fire together.
        if (player != null) return;
        Uri uri = resolveAlarmUri();
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setDataSource(this, uri);
            player.setLooping(true);
            player.prepareAsync();
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: " + what + "/" + extra);
                return false;
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to start alarm player", e);
        }
    }

    private void stopAlarm() {
        if (player != null) {
            try {
                player.setOnPreparedListener(null);
                player.setOnErrorListener(null);
                // reset() is safe in every MediaPlayer state (including Preparing)
                player.reset();
                player.release();
            } catch (Exception ignored) {}
            player = null;
        }
    }

    private Uri resolveAlarmUri() {
        String saved = getSharedPreferences("helth_prefs", MODE_PRIVATE)
                .getString(PREF_SOUND_URI, null);
        if (saved != null) return Uri.parse(saved);
        Uri def = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        return def != null ? def : RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
    }

    // ─── Sleep state cleanup ─────────────────────────────────────────────────

    /** Fully tears down sleep mode. Idempotent — safe to call even if already inactive. */
    private void doFullSleepCleanup() {
        stopService(new Intent(this, SleepVpnService.class));
        SleepVpnService.disconnect();

        ((AudioManager) getSystemService(AUDIO_SERVICE))
                .setRingerMode(AudioManager.RINGER_MODE_NORMAL);

        getSharedPreferences("helth_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("sleep_active", false)
                .putBoolean(KEY_WAKE_ALARM_ACTIVE, false)
                .apply();

        getSystemService(NotificationManager.class).cancel(2);
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private Notification buildNotification() {
        String challengeMode = getSharedPreferences("helth_prefs", MODE_PRIVATE)
                .getString(SleepLockActivity.PREF_CHALLENGE, SleepLockActivity.CHALLENGE_SIMPLE);
        boolean isSimple = SleepLockActivity.CHALLENGE_SIMPLE.equals(challengeMode);

        Intent dismissIntent = new Intent(this, WakeAlarmService.class).setAction(ACTION_DISMISS);
        PendingIntent dismissPi = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? PendingIntent.getForegroundService(this, 0, dismissIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                : PendingIntent.getService(this, 0, dismissIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // fullScreenIntent always opens the challenge screen, not MainActivity.
        PendingIntent challengePi = PendingIntent.getActivity(
                this, 11,
                new Intent(this, SleepLockActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.wake_alarm_notif_title))
                .setContentText(isSimple
                        ? getString(R.string.wake_alarm_notif_text)
                        : getString(R.string.wake_alarm_notif_challenge_text))
                .setSmallIcon(R.drawable.ic_sun)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .setAutoCancel(false)
                .setFullScreenIntent(challengePi, true)
                .setContentIntent(isSimple ? dismissPi : challengePi);

        if (isSimple) {
            builder.addAction(0, getString(R.string.confirm_awake), dismissPi);
        } else {
            // Non-simple: force user to go through the challenge — no direct dismiss.
            builder.addAction(0, getString(R.string.open_challenge), challengePi);
        }

        return builder.build();
    }

    private void ensureChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.wake_alarm_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        // Sound is handled by MediaPlayer; silence the channel to avoid double audio
        ch.setSound(null, null);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }
}
