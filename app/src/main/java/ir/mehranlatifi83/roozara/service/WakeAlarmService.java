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
import ir.mehranlatifi83.roozara.manager.SleepModeController;
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

    /**
     * Start the alarm, falling back through every sound available rather than going
     * silent.
     *
     * A chosen sound can stop being playable at any time: the file is deleted, the SD
     * card is gone, or the URI permission was lost when the app was updated. Previously
     * setDataSource() simply threw, the exception was logged, and the alarm never made a
     * sound — and because the half-built player was left in place, every retry returned
     * early believing it was already playing. A wake alarm that is silent is the one
     * failure this app cannot have.
     */
    private void playAlarm() {
        // If already playing, skip — prevents a double-start from briefly stopping
        // and restarting audio when both CountDownTimer and AlarmManager fire together.
        if (player != null) return;

        String chosen = getSharedPreferences("helth_prefs", MODE_PRIVATE)
                .getString(PREF_SOUND_URI, null);
        if (chosen != null && tryPlay(Uri.parse(chosen), "chosen sound")) return;
        if (chosen != null) {
            ActivityLog.log(this, "chosen alarm sound could not be played - falling back");
        }

        Uri alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (alarm != null && tryPlay(alarm, "default alarm")) return;

        Uri ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        if (ringtone != null && tryPlay(ringtone, "default ringtone")) return;

        Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        if (notification != null && tryPlay(notification, "notification sound")) return;

        ActivityLog.log(this, "WAKE ALARM IS SILENT", "reason=no_playable_sound_found");
    }

    /** Attempt one source. Leaves player null on failure so the next one can be tried. */
    private boolean tryPlay(Uri uri, String description) {
        MediaPlayer candidate = null;
        try {
            candidate = new MediaPlayer();
            candidate.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            candidate.setDataSource(this, uri);
            candidate.setLooping(true);
            candidate.setOnPreparedListener(MediaPlayer::start);
            candidate.setOnErrorListener((mp, what, extra) -> {
                // Reported asynchronously, long after this method returned, so the
                // fallback chain cannot help here. Recorded so a silent morning can at
                // least be explained afterwards.
                Log.e(TAG, "MediaPlayer error: " + what + "/" + extra);
                ActivityLog.log(this, "alarm playback failed while running",
                        "what=" + what + " extra=" + extra);
                return true;
            });
            candidate.prepareAsync();
            player = candidate;
            ActivityLog.log(this, "wake alarm playing", "source=" + description);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Could not play " + description, e);
            if (candidate != null) {
                try {
                    candidate.release();
                } catch (Exception ignored) {}
            }
            player = null;
            return false;
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

    // ─── Sleep state cleanup ─────────────────────────────────────────────────

    /** Fully tears down sleep mode. Idempotent — safe to call even if already inactive. */
    private void doFullSleepCleanup() {
        // Through the controller so the ringer goes back to whatever it was before
        // bedtime. Forcing NORMAL here switched the ringer on for anyone who keeps
        // their phone on vibrate.
        SleepModeController.releaseSystemState(this, "wake_alarm_dismissed");

        getSharedPreferences("helth_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_WAKE_ALARM_ACTIVE, false)
                .apply();
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
