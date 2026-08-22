package ir.mehranlatifi83.roozara.ui;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

import ir.mehranlatifi83.roozara.R;

/**
 * A full-screen overlay that covers whatever is behind the sleep lock screen.
 *
 * This replaces lock task mode ("screen pinning"). Pinning worked, but it demanded a
 * system setting the user had to find and switch on themselves, it took over the whole
 * device in a way that alarmed people, and leaving it was a documented two-button
 * gesture anyway — so it asked a lot and delivered a soft guarantee.
 *
 * The overlay approach uses the permission the app already asks for and already
 * explains: "display over other apps". While the lock activity is in the foreground it
 * is not needed. The moment the activity is pushed aside — Home, Recents, another app
 * coming forward — this window is raised, and because it is a window rather than an
 * activity, Home does not dismiss it. Whatever the user switched to is still running
 * underneath, but it is covered and cannot be touched, and the lock activity is brought
 * back within a few hundred milliseconds regardless.
 *
 * Honest limits, the same ones the lock screen has always had:
 *   - Without overlay access this does nothing at all, and the app falls back to
 *     relaunching the activity, which is what it did before.
 *   - It cannot cover the status bar. Since API 26 the system deliberately places
 *     TYPE_APPLICATION_OVERLAY below the status and navigation bars, and no ordinary
 *     app can get above them.
 * It raises the cost of wandering off. It is not a cage, and the app should not
 * pretend otherwise.
 */
public final class SleepOverlayGuard {

    private static final String TAG = "SleepOverlayGuard";

    private static View overlayView;

    private SleepOverlayGuard() {}

    public static boolean isAvailable(Context ctx) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(ctx);
    }

    public static boolean isShowing() {
        return overlayView != null;
    }

    /** Raise the cover. Safe to call repeatedly and from any activity. */
    public static void show(Context ctx) {
        if (overlayView != null || !isAvailable(ctx)) return;

        WindowManager wm = (WindowManager) ctx.getApplicationContext()
                .getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Not focusable: the overlay must never steal key events from the lock
                // activity underneath it, and a screen reader has to keep working. It
                // still consumes touches, which is what stops the app behind it being
                // used.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        params.gravity = Gravity.TOP | Gravity.START;

        try {
            View view = LayoutInflater.from(ctx).inflate(R.layout.overlay_sleep_guard, null);
            // Tapping anywhere goes straight back to the lock screen, so someone who
            // ends up here is never stuck looking at a blank cover.
            view.setOnClickListener(v -> SleepLockActivity.launch(ctx));
            wm.addView(view, params);
            overlayView = view;
        } catch (Exception e) {
            // Overlay access can be revoked while the app runs, and some ROMs refuse the
            // window even with it granted. Bedtime must carry on either way.
            Log.w(TAG, "Could not show the sleep overlay", e);
            overlayView = null;
        }
    }

    /** Take the cover down. Safe to call when it was never shown. */
    public static void hide(Context ctx) {
        if (overlayView == null) return;
        WindowManager wm = (WindowManager) ctx.getApplicationContext()
                .getSystemService(Context.WINDOW_SERVICE);
        try {
            if (wm != null) wm.removeView(overlayView);
        } catch (Exception e) {
            Log.w(TAG, "Could not remove the sleep overlay", e);
        } finally {
            overlayView = null;
        }
    }
}
