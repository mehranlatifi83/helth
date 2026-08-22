package ir.mehranlatifi83.roozara.manager;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;

import ir.mehranlatifi83.roozara.service.SleepVpnService;
import ir.mehranlatifi83.roozara.ui.SleepLockActivity;
import ir.mehranlatifi83.roozara.util.ActivityLog;

/**
 * Owns the system-level side of sleep mode: the ringer and the internet block.
 *
 * Split out so that every path which ends the night — the wake alarm, the early exit,
 * and switching the schedule off from the main screen — undoes exactly the same things
 * in the same order. Previously each did its own subset, which is how turning the
 * schedule off mid-day could leave the phone silent and offline with nothing left to
 * put it back.
 */
public final class SleepModeController {

    private static final String PREFS = "helth_prefs";

    public static final String KEY_SLEEP_ACTIVE = "sleep_active";

    /**
     * Ringer mode the phone was in before sleep mode silenced it.
     *
     * Persisted rather than held in memory: the process can be killed overnight, and
     * without this the phone was simply forced back to NORMAL on wake — so anyone who
     * keeps their phone on vibrate got their ringer switched on by going to bed.
     */
    private static final String KEY_PREV_RINGER = "previous_ringer_mode";

    /**
     * Wake time of a night the user already left early.
     *
     * Leaving early clears the "sleep active" flag, and anything that asks only "are we
     * inside the sleep window and not currently sleeping?" then answers yes and starts
     * the night over. Opening the app after an early exit did exactly that. This marks
     * the night as finished until its wake time passes.
     */
    private static final String KEY_CYCLE_LEFT_UNTIL = "sleep_cycle_left_until";

    private static final int NOTIF_SLEEP = 2;

    private SleepModeController() {}

    public static boolean isSleepActive(Context ctx) {
        return prefs(ctx).getBoolean(KEY_SLEEP_ACTIVE, false);
    }

    /** True while we are inside a night the user has already left early. */
    public static boolean wasCycleLeftEarly(Context ctx) {
        long until = prefs(ctx).getLong(KEY_CYCLE_LEFT_UNTIL, 0);
        if (until <= 0) return false;
        if (System.currentTimeMillis() >= until) {
            // The night is over; forget it so it can never suppress a later one.
            prefs(ctx).edit().remove(KEY_CYCLE_LEFT_UNTIL).apply();
            return false;
        }
        return true;
    }

    /** Mark tonight as finished, so re-opening the app does not restart it. */
    public static void markCycleLeftEarly(Context ctx) {
        int[] wake = ScheduleManager.getWakeTime(ctx);
        if (wake == null) return;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, wake[0]);
        cal.set(java.util.Calendar.MINUTE, wake[1]);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
        }
        prefs(ctx).edit().putLong(KEY_CYCLE_LEFT_UNTIL, cal.getTimeInMillis()).apply();
        ActivityLog.log(ctx, "night marked as finished early");
    }

    // ─── Entering ────────────────────────────────────────────────────────────

    /** Silence the phone, remembering how it was, and cut the internet. */
    public static void applySystemState(Context ctx) {
        AudioManager audio = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        try {
            if (audio != null && !prefs(ctx).contains(KEY_PREV_RINGER)) {
                prefs(ctx).edit().putInt(KEY_PREV_RINGER, audio.getRingerMode()).apply();
            }
            if (audio != null) audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);
            ActivityLog.log(ctx, "phone silenced");
        } catch (SecurityException e) {
            // Do Not Disturb access can be revoked after the schedule was enabled. The
            // rest of bedtime must still run instead of aborting here.
            ActivityLog.log(ctx, "could not silence the phone", "reason=no_dnd_access");
        }

        if (android.net.VpnService.prepare(ctx) == null) {
            ctx.startForegroundService(new Intent(ctx, SleepVpnService.class));
            ActivityLog.log(ctx, "internet block starting");
        } else {
            ActivityLog.log(ctx, "internet not blocked", "reason=vpn_not_authorised");
        }
    }

    // ─── Leaving ─────────────────────────────────────────────────────────────

    /**
     * Undo everything sleep mode changed and clear its state.
     *
     * Safe to call when sleep mode is not running, and safe to call more than once.
     * reason is recorded in the log so the file explains why the night ended.
     */
    public static void releaseSystemState(Context ctx, String reason) {
        boolean wasActive = isSleepActive(ctx);

        restoreRinger(ctx);

        ctx.stopService(new Intent(ctx, SleepVpnService.class));
        SleepVpnService.disconnect();

        prefs(ctx).edit()
                .putBoolean(KEY_SLEEP_ACTIVE, false)
                .remove(SleepLockActivity.KEY_SLEEP_START)
                .apply();

        // The bedtime notification is ongoing, so nothing else will ever remove it.
        // Leaving it behind left a permanent "time to sleep" entry in the shade for
        // anyone who never opened the lock screen.
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIF_SLEEP);

        if (wasActive) {
            ActivityLog.log(ctx, "sleep mode ended", "reason=" + reason);
        }
    }

    /** Put the ringer back exactly as it was, not merely to NORMAL. */
    private static void restoreRinger(Context ctx) {
        SharedPreferences prefs = prefs(ctx);
        int previous = prefs.getInt(KEY_PREV_RINGER, AudioManager.RINGER_MODE_NORMAL);
        try {
            AudioManager audio = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (audio != null) audio.setRingerMode(previous);
            ActivityLog.log(ctx, "ringer restored", "mode=" + ringerName(previous));
        } catch (SecurityException e) {
            ActivityLog.log(ctx, "could not restore the ringer", "reason=no_dnd_access");
        } finally {
            prefs.edit().remove(KEY_PREV_RINGER).apply();
        }
    }

    /** Force sound on for the wake alarm, without forgetting the pre-sleep mode. */
    public static void unsilenceForAlarm(Context ctx) {
        try {
            AudioManager audio = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (audio != null) audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        } catch (SecurityException ignored) {
            // Nothing to do: the alarm plays on the alarm stream regardless.
        }
    }

    private static String ringerName(int mode) {
        switch (mode) {
            case AudioManager.RINGER_MODE_SILENT:  return "silent";
            case AudioManager.RINGER_MODE_VIBRATE: return "vibrate";
            default:                               return "normal";
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
