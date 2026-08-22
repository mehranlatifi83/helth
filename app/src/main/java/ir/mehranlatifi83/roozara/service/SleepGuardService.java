package ir.mehranlatifi83.roozara.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;

import ir.mehranlatifi83.roozara.manager.SleepModeController;
import ir.mehranlatifi83.roozara.ui.SleepLockActivity;
import ir.mehranlatifi83.roozara.util.ActivityLog;

/**
 * Keeps the phone unusable while sleep mode is running.
 *
 * The overlay covers the screen and the activity relaunches itself, but neither can
 * close the last gap: no ordinary app may cover the status bar, so the notification
 * shade can still be pulled down, and from there Settings and "force stop" are two taps
 * away. An accessibility service is the only mechanism Android gives a normal app that
 * can see what came to the foreground and react to it.
 *
 * What it does, and nothing more:
 *   - While sleep mode is active, anything that is not Roozara brings the lock screen
 *     straight back.
 *   - The notification shade is collapsed as soon as it opens.
 *   - Settings is treated the same as any other app, which is what closes the
 *     force-stop route.
 *
 * What it deliberately does not do: it never reads screen content, never touches
 * TalkBack, and never consumes a gesture or a key. Several accessibility services run
 * side by side on Android, so a blind user keeps full control of their screen reader
 * while this is active. It is switched off entirely outside the sleep window.
 */
public class SleepGuardService extends AccessibilityService {

    private static final String SYSTEM_UI = "com.android.systemui";

    /**
     * Never bounced away from, whatever the hour.
     *
     * A phone that cannot be answered is not a sleep aid, it is a hazard. Anything to
     * do with a call in progress — the dialer, the in-call screen, the emergency
     * dialer — is left alone entirely. Sleep mode is a commitment device about habits,
     * and it has no business standing between someone and a phone call at 3am.
     */
    private static final String[] CALL_PACKAGES = {
            "dialer", "incallui", "telecom", "telephony", "emergency",
    };

    /**
     * Bounces are rate-limited. Relaunching on every single window event turns into a
     * loop the moment anything transient appears, and a phone that flickers is worse
     * than one that takes half a second to come back.
     */
    private static final long MIN_BOUNCE_INTERVAL_MS = 400;

    private static boolean running = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastBounce = 0;

    // ─── Availability ────────────────────────────────────────────────────────

    /** True when the user has switched the service on in Accessibility settings. */
    public static boolean isEnabled(Context ctx) {
        String enabled = Settings.Secure.getString(
                ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;

        String target = new ComponentName(ctx, SleepGuardService.class).flattenToString();
        String shortTarget = ctx.getPackageName() + "/" + SleepGuardService.class.getSimpleName();
        for (String part : enabled.split(":")) {
            if (part.equalsIgnoreCase(target) || part.equalsIgnoreCase(shortTarget)) return true;
        }
        return false;
    }

    /** True when the service is not merely enabled but actually connected and running. */
    public static boolean isRunning() {
        return running;
    }

    public static android.content.Intent settingsIntent() {
        return new android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = true;

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        // No content retrieval: this only needs to know which package is in front, and
        // asking for less is the right default for a permission this powerful.
        info.flags = AccessibilityServiceInfo.DEFAULT;
        info.notificationTimeout = 100;
        setServiceInfo(info);

        ActivityLog.log(this, "sleep guard connected");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        running = false;
        ActivityLog.log(this, "sleep guard disconnected");
        return super.onUnbind(intent);
    }

    /** True for the dialer, the in-call screen and the emergency dialer. */
    private static boolean isCallRelated(String packageName) {
        String lower = packageName.toLowerCase();
        for (String marker : CALL_PACKAGES) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    /**
     * True while a call is ringing or connected.
     *
     * Checked as well as the package name because dialers are vendor-specific and the
     * name check cannot possibly cover every ROM. The audio mode is the same on all of
     * them, so this catches the ones the list misses.
     */
    private boolean isCallInProgress() {
        android.media.AudioManager audio =
                (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return false;
        int mode = audio.getMode();
        return mode == android.media.AudioManager.MODE_IN_CALL
                || mode == android.media.AudioManager.MODE_IN_COMMUNICATION
                || mode == android.media.AudioManager.MODE_RINGTONE;
    }

    @Override
    public void onInterrupt() {
        // Required by the platform. Nothing to interrupt: this service produces no
        // feedback of its own.
    }

    // ─── The guard ───────────────────────────────────────────────────────────

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        // Only ever active during the sleep window. Outside it this service watches
        // window changes and does nothing at all with them.
        if (!SleepModeController.isSleepActive(this)) return;

        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String packageName = pkg.toString();

        if (packageName.equals(getPackageName())) return;

        if (isCallRelated(packageName) || isCallInProgress()) {
            // Deliberately not even logged as a bounce that was skipped: this is not a
            // near miss, it is the guard correctly staying out of the way.
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastBounce < MIN_BOUNCE_INTERVAL_MS) return;
        lastBounce = now;

        if (packageName.equals(SYSTEM_UI)) {
            // The notification shade, the power menu, or the volume panel. Back closes
            // all three without disturbing anything else.
            performGlobalAction(GLOBAL_ACTION_BACK);
            ActivityLog.log(this, "system panel closed during sleep");
            return;
        }

        ActivityLog.log(this, "app opened during sleep - returning to the lock screen",
                "app=" + packageName);
        // Posted rather than called inline: the window that just appeared is still
        // settling, and starting an activity in the middle of that is unreliable.
        handler.post(() -> SleepLockActivity.launch(this));
    }
}
