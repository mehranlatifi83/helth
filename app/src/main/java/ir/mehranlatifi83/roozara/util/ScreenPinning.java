package ir.mehranlatifi83.roozara.util;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/**
 * Lock task mode ("screen pinning") for the sleep lock screen.
 *
 * Hiding the system bars with WindowInsetsController is not enough: the behaviour is
 * SHOW_TRANSIENT_BARS_BY_SWIPE, so a sighted user simply swipes and gets the navigation
 * buttons and the notification shade back, then force-stops the app. Covering the
 * system bars with an overlay window is not an option either — since API 26,
 * TYPE_APPLICATION_OVERLAY is deliberately placed below the status and navigation bars
 * so that no ordinary app can cover them.
 *
 * Lock task mode is the supported mechanism that does work. While it is active the
 * notification shade cannot be pulled down and Home and Recents are disabled.
 *
 * It is not an escape-proof cage, and the app should not pretend otherwise: the user
 * can still leave by holding Back and Recents together. It raises the cost of quitting
 * rather than removing the possibility.
 */
public final class ScreenPinning {

    private static final String TAG = "ScreenPinning";

    /** Hidden Settings.Secure key backing the "Screen pinning" switch. */
    private static final String LOCK_TO_APP_ENABLED = "lock_to_app_enabled";

    private ScreenPinning() {}

    /**
     * True when the user has enabled screen pinning in system settings.
     *
     * Without it startLockTask() has no effect on most ROMs, so this is surfaced as its
     * own row on the permissions screen rather than failing silently at bedtime.
     */
    public static boolean isEnabledInSettings(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        try {
            return Settings.Secure.getInt(cr, LOCK_TO_APP_ENABLED, 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isActive(Context ctx) {
        ActivityManager am = ctx.getSystemService(ActivityManager.class);
        return am != null
                && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
    }

    /**
     * Enter lock task mode. Safe to call repeatedly.
     *
     * @return true if the activity is pinned afterwards.
     */
    public static boolean start(Activity activity) {
        if (isActive(activity)) return true;
        try {
            activity.startLockTask();
            return true;
        } catch (Exception e) {
            // Throws when the ROM forbids pinning, or when the activity is not in the
            // right state. Bedtime must continue regardless — the lock screen still
            // shows, it is just easier to leave.
            Log.w(TAG, "Could not enter lock task mode", e);
            return false;
        }
    }

    /** Leave lock task mode. Must be called before finishing, or the task stays pinned. */
    public static void stop(Activity activity) {
        if (!isActive(activity)) return;
        try {
            activity.stopLockTask();
        } catch (Exception e) {
            Log.w(TAG, "Could not leave lock task mode", e);
        }
    }

    /** Settings screen holding the screen-pinning switch. */
    public static Intent settingsIntent() {
        return new Intent(Settings.ACTION_SECURITY_SETTINGS);
    }
}
