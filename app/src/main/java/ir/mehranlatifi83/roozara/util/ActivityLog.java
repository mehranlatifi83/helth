package ir.mehranlatifi83.roozara.util;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A plain-text record of everything Roozara does.
 *
 * The app spends most of its life with no screen showing, doing things the user is
 * asleep for. When something goes wrong afterwards there is nothing to look at, and
 * "the alarm didn't ring" is impossible to diagnose from memory. This writes one line
 * per action: what happened, when, and what the result was.
 *
 * Deliberately simple. It is meant to be read aloud by TalkBack and shared with someone
 * who can help, not parsed by a machine.
 *
 * Nothing here ever throws. A logging failure must never break the feature it was only
 * meant to observe.
 */
public final class ActivityLog {

    private static final String TAG        = "RoozaraLog";
    private static final String PREFS      = "helth_prefs";
    private static final String KEY_ENABLED = "logging_enabled";
    private static final String FILE_NAME  = "activity.log";

    /** Rotated past this size so it cannot grow without bound over months of use. */
    private static final long MAX_BYTES = 1024 * 1024;

    private static final Object LOCK = new Object();

    private ActivityLog() {}

    // ─── On/off ──────────────────────────────────────────────────────────────

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply();
        // Written whatever the new state is, so a gap in the file is never unexplained.
        write(ctx, enabled ? "logging enabled by the user" : "logging disabled by the user");
    }

    // ─── Recording ───────────────────────────────────────────────────────────

    /** Record one action, e.g. log(ctx, "sleep mode started"). */
    public static void log(Context ctx, String event) {
        if (ctx == null || !isEnabled(ctx)) return;
        write(ctx, event);
    }

    /** Record one action with a detail, e.g. log(ctx, "internet blocked", "succeeded=yes"). */
    public static void log(Context ctx, String event, String detail) {
        if (ctx == null || !isEnabled(ctx)) return;
        write(ctx, event + " " + detail);
    }

    public static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    // ─── File ────────────────────────────────────────────────────────────────

    public static File file(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "logs");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Could not create the log directory");
        }
        return new File(dir, FILE_NAME);
    }

    public static long sizeBytes(Context ctx) {
        File f = file(ctx);
        return f.exists() ? f.length() : 0;
    }

    /** Empty the log. Recording carries on afterwards if it is switched on. */
    public static boolean clear(Context ctx) {
        synchronized (LOCK) {
            try (FileWriter writer = new FileWriter(file(ctx), false)) {
                writer.write("");
            } catch (Exception e) {
                Log.w(TAG, "Could not clear the log", e);
                return false;
            }
        }
        write(ctx, "log cleared by the user");
        return true;
    }

    /**
     * Build a share intent for the log file.
     *
     * Returns null when there is nothing to share, so the caller can say so rather than
     * opening an empty share sheet.
     */
    public static Intent shareIntent(Context ctx) {
        File f = file(ctx);
        if (!f.exists() || f.length() == 0) return null;
        try {
            Uri uri = FileProvider.getUriForFile(
                    ctx, ctx.getPackageName() + ".fileprovider", f);
            return new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, "Roozara activity log")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            Log.w(TAG, "Could not build the share intent", e);
            return null;
        }
    }

    // ─── Internals ───────────────────────────────────────────────────────────

    private static void write(Context ctx, String line) {
        synchronized (LOCK) {
            try {
                File f = file(ctx);
                rotateIfNeeded(f);
                String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(new Date());
                try (FileWriter writer = new FileWriter(f, true)) {
                    writer.append(stamp).append("  ").append(line).append('\n');
                }
            } catch (Exception e) {
                // Losing a line is always preferable to breaking the action.
                Log.w(TAG, "Could not write to the log", e);
            }
        }
    }

    private static void rotateIfNeeded(File f) {
        if (!f.exists() || f.length() < MAX_BYTES) return;
        File previous = new File(f.getParentFile(), FILE_NAME + ".previous");
        if (previous.exists() && !previous.delete()) return;
        if (!f.renameTo(previous)) {
            Log.w(TAG, "Could not rotate the log");
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
