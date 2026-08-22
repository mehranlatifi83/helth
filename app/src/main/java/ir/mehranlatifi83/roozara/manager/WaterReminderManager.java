package ir.mehranlatifi83.roozara.manager;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import ir.mehranlatifi83.roozara.receiver.WaterReminderReceiver;

import java.util.ArrayList;
import java.util.List;
import ir.mehranlatifi83.roozara.util.ActivityLog;

/**
 * Schedules 8 water reminders in the medically optimal windows of the user's day.
 *
 * Medical rules applied (standard clinical hydration guidelines):
 *  - Blocked window per meal: [meal - 30 min, meal + 90 min]
 *      30 min before → stomach lining needs pre-hydration, not dilution at mealtime
 *      90 min after  → avoids diluting gastric enzymes during active digestion
 *  - Minimum 45 min between consecutive reminders (prevents osmotic overload on kidneys)
 *  - 8 reminders distributed as evenly as possible across the remaining valid windows
 */
public class WaterReminderManager {

    static final String KEY_BREAKFAST_H = "breakfast_hour";
    static final String KEY_BREAKFAST_M = "breakfast_min";
    static final String KEY_LUNCH_H     = "lunch_hour";
    static final String KEY_LUNCH_M     = "lunch_min";
    static final String KEY_DINNER_H    = "dinner_hour";
    static final String KEY_DINNER_M    = "dinner_min";
    static final String KEY_WATER_ON    = "water_reminders_enabled";

    private static final String PREFS          = "helth_prefs";
    private static final int    REQ_BASE       = 200;
    public  static final int    COUNT          = 8;

    private static final int BLOCK_BEFORE_MIN = 30;
    private static final int BLOCK_AFTER_MIN  = 90;
    private static final int MIN_GAP_MIN      = 45;

    // ─── Meal time storage ───────────────────────────────────────────────────

    public static void saveBreakfast(Context ctx, int h, int m) {
        prefs(ctx).edit().putInt(KEY_BREAKFAST_H, h).putInt(KEY_BREAKFAST_M, m).apply();
    }

    public static void saveLunch(Context ctx, int h, int m) {
        prefs(ctx).edit().putInt(KEY_LUNCH_H, h).putInt(KEY_LUNCH_M, m).apply();
    }

    public static void saveDinner(Context ctx, int h, int m) {
        prefs(ctx).edit().putInt(KEY_DINNER_H, h).putInt(KEY_DINNER_M, m).apply();
    }

    public static int[] getBreakfast(Context ctx) { return getMeal(ctx, KEY_BREAKFAST_H, KEY_BREAKFAST_M); }
    public static int[] getLunch(Context ctx)      { return getMeal(ctx, KEY_LUNCH_H,     KEY_LUNCH_M);     }
    public static int[] getDinner(Context ctx)     { return getMeal(ctx, KEY_DINNER_H,    KEY_DINNER_M);    }

    // ─── Enable / disable ────────────────────────────────────────────────────

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_WATER_ON, false);
    }

    public static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_WATER_ON, on).apply();
        if (on) scheduleAll(ctx);
        else    cancelAll(ctx);
    }

    // ─── Schedule computation ─────────────────────────────────────────────────

    /**
     * Returns up to COUNT [hour, minute] pairs at medically optimal times.
     *
     * Algorithm:
     *  1. Determine awake window from sleep schedule (fallback 07:00–23:00).
     *  2. Build blocked intervals around each meal.
     *  3. Merge and subtract blocked intervals from awake window → valid segments.
     *  4. Distribute COUNT reminders evenly across total valid time, skipping any
     *     candidate that is within MIN_GAP_MIN of the previous placed reminder.
     */
    public static List<int[]> computeReminderTimes(Context ctx) {
        int[] wake  = ScheduleManager.getWakeTime(ctx);
        int[] sleep = ScheduleManager.getSleepTime(ctx);

        int startMin = (wake  != null) ? wake[0]  * 60 + wake[1]  : 7  * 60;
        int endMin   = (sleep != null) ? sleep[0] * 60 + sleep[1] : 23 * 60;
        if (endMin <= startMin) endMin += 24 * 60;

        List<int[]> blocked = new ArrayList<>();
        int[][] meals = {getBreakfast(ctx), getLunch(ctx), getDinner(ctx)};
        for (int[] meal : meals) {
            if (meal == null) continue;
            int m = meal[0] * 60 + meal[1];
            blocked.add(new int[]{m - BLOCK_BEFORE_MIN, m + BLOCK_AFTER_MIN});
        }
        blocked.sort((a, b) -> a[0] - b[0]);
        List<int[]> merged = mergeIntervals(blocked);

        List<int[]> valid = new ArrayList<>();
        int cursor = startMin;
        for (int[] b : merged) {
            if (b[0] > cursor) valid.add(new int[]{cursor, Math.min(b[0], endMin)});
            cursor = Math.max(cursor, b[1]);
            if (cursor >= endMin) break;
        }
        if (cursor < endMin) valid.add(new int[]{cursor, endMin});

        int totalValid = 0;
        for (int[] v : valid) totalValid += Math.max(0, v[1] - v[0]);

        List<int[]> result = new ArrayList<>();
        if (totalValid <= 0) return result;

        int interval = totalValid / (COUNT + 1);
        if (interval < MIN_GAP_MIN) interval = MIN_GAP_MIN;

        // Use -1000 (not MIN_VALUE) to avoid integer overflow in the gap check below
        int lastClock = -1000;
        for (int i = 1; i <= COUNT; i++) {
            int targetOffset = interval * i;
            int clock = (targetOffset <= totalValid) ? offsetToClockMin(valid, targetOffset) : -1;

            // If the evenly-spaced slot is too close (or past valid time), try placing
            // the reminder at lastClock + MIN_GAP_MIN instead.
            if (clock < 0 || clock - lastClock < MIN_GAP_MIN) {
                clock = lastClock + MIN_GAP_MIN;
                if (!isInValidSegment(valid, clock)) break;
            }

            result.add(new int[]{(clock % (24 * 60)) / 60, (clock % (24 * 60)) % 60});
            lastClock = clock;
        }

        return result;
    }

    // ─── Alarm scheduling ─────────────────────────────────────────────────────

    public static void scheduleAll(Context ctx) {
        ActivityLog.log(ctx, "water reminders rescheduled");
        if (!canScheduleExact(ctx)) return;
        cancelAll(ctx);
        List<int[]> slots = computeReminderTimes(ctx);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        for (int i = 0; i < slots.size() && i < COUNT; i++) {
            int[] slot = slots.get(i);
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                        nextTriggerMs(slot[0], slot[1]),
                        buildIntent(ctx, i, slot[0], slot[1]));
            } catch (SecurityException ignored) {
                break;
            }
        }
    }

    public static void cancelAll(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        for (int i = 0; i < COUNT; i++) {
            am.cancel(buildCancelIntent(ctx, i));
        }
    }

    public static boolean canScheduleExact(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ((AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE))
                    .canScheduleExactAlarms();
        }
        return true;
    }

    // ─── Interval helpers ─────────────────────────────────────────────────────

    private static List<int[]> mergeIntervals(List<int[]> sorted) {
        List<int[]> out = new ArrayList<>();
        for (int[] cur : sorted) {
            if (out.isEmpty() || cur[0] > out.get(out.size() - 1)[1]) {
                out.add(new int[]{cur[0], cur[1]});
            } else {
                out.get(out.size() - 1)[1] = Math.max(out.get(out.size() - 1)[1], cur[1]);
            }
        }
        return out;
    }

    private static int offsetToClockMin(List<int[]> valid, int offset) {
        int acc = 0;
        for (int[] v : valid) {
            int len = v[1] - v[0];
            if (len <= 0) continue;
            if (acc + len > offset) return v[0] + (offset - acc);
            acc += len;
        }
        return -1;
    }

    private static boolean isInValidSegment(List<int[]> valid, int clockMin) {
        for (int[] v : valid) {
            if (clockMin >= v[0] && clockMin <= v[1]) return true;
        }
        return false;
    }

    // ─── Alarm intent helpers ─────────────────────────────────────────────────

    public static long nextTriggerMs(int hour, int min) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour);
        cal.set(java.util.Calendar.MINUTE, min);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
        }
        return cal.getTimeInMillis();
    }

    private static PendingIntent buildIntent(Context ctx, int slot, int h, int m) {
        Intent i = new Intent(ctx, WaterReminderReceiver.class)
                .setAction(WaterReminderReceiver.ACTION_WATER)
                .putExtra(WaterReminderReceiver.EXTRA_SLOT, slot)
                .putExtra(WaterReminderReceiver.EXTRA_HOUR, h)
                .putExtra(WaterReminderReceiver.EXTRA_MIN,  m);
        return PendingIntent.getBroadcast(ctx, REQ_BASE + slot, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent buildCancelIntent(Context ctx, int slot) {
        Intent i = new Intent(ctx, WaterReminderReceiver.class)
                .setAction(WaterReminderReceiver.ACTION_WATER);
        return PendingIntent.getBroadcast(ctx, REQ_BASE + slot, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static int[] getMeal(Context ctx, String hKey, String mKey) {
        SharedPreferences p = prefs(ctx);
        int h = p.getInt(hKey, -1);
        if (h == -1) return null;
        return new int[]{h, p.getInt(mKey, 0)};
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
