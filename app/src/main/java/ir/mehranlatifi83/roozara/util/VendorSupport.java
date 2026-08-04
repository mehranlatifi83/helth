package ir.mehranlatifi83.roozara.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * Vendor-specific restrictions that stock Android does not have.
 *
 * Several Chinese OEM ROMs — MIUI/HyperOS above all — block an app from starting an
 * activity from the background even when SYSTEM_ALERT_WINDOW has been granted. That is
 * why the sleep lock screen never appears on some Xiaomi phones while every other part
 * of the schedule works: the alarm fires, the receiver runs, and the activity start is
 * then discarded by the ROM.
 *
 * There is no public API to query or request these permissions, so all we can do is
 * detect the ROM and take the user to the right settings page.
 */
public final class VendorSupport {

    private VendorSupport() {}

    // ─── ROM detection ───────────────────────────────────────────────────────

    public static boolean isXiaomi() {
        return matches("xiaomi") || matches("redmi") || matches("poco")
                || !systemProperty("ro.miui.ui.version.name").isEmpty();
    }

    public static boolean isHuawei()  { return matches("huawei") || matches("honor"); }
    public static boolean isOppo()    { return matches("oppo") || matches("realme"); }
    public static boolean isVivo()    { return matches("vivo"); }
    public static boolean isSamsung() { return matches("samsung"); }

    /** True on ROMs known to need permissions beyond the stock Android set. */
    public static boolean needsVendorSetup() {
        return isXiaomi() || isHuawei() || isOppo() || isVivo();
    }

    /**
     * Background pop-up permission. Only MIUI exposes this as a separate toggle, and it
     * is the one that actually decides whether the lock screen can appear.
     */
    public static boolean hasBackgroundPopupSetting() {
        return isXiaomi();
    }

    public static boolean hasAutostartSetting() {
        return needsVendorSetup();
    }

    private static boolean matches(String vendor) {
        return Build.MANUFACTURER != null
                && Build.MANUFACTURER.toLowerCase().contains(vendor)
                || (Build.BRAND != null && Build.BRAND.toLowerCase().contains(vendor));
    }

    // ─── Settings deep links ─────────────────────────────────────────────────

    /**
     * MIUI's per-app permission editor, which contains "Display pop-up windows while
     * running in background". The component has been stable across MIUI versions, but
     * it is not public API, so callers must handle a failed launch.
     */
    public static Intent backgroundPopupIntent(Context ctx) {
        if (!isXiaomi()) return null;
        Intent intent = new Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName("com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity")
                .putExtra("extra_pkgname", ctx.getPackageName());
        return resolves(ctx, intent) ? intent : null;
    }

    /** Autostart manager, so the ROM lets the app run after a reboot. */
    public static Intent autostartIntent(Context ctx) {
        Intent[] candidates = {
                new Intent().setComponent(new ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                new Intent().setComponent(new ComponentName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
                new Intent().setComponent(new ComponentName("com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
                new Intent().setComponent(new ComponentName("com.coloros.safecenter",
                        "com.coloros.safecenter.startupapp.StartupAppListActivity")),
                new Intent().setComponent(new ComponentName("com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
        };
        for (Intent intent : candidates) {
            if (resolves(ctx, intent)) return intent;
        }
        return null;
    }

    private static boolean resolves(Context ctx, Intent intent) {
        return ctx.getPackageManager()
                .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null;
    }

    // ─── Build property access ───────────────────────────────────────────────

    private static String systemProperty(String key) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Object value = systemProperties
                    .getMethod("get", String.class)
                    .invoke(null, key);
            return value == null ? "" : value.toString();
        } catch (Exception e) {
            // Hidden API access can be blocked; MANUFACTURER/BRAND still cover Xiaomi.
            return "";
        }
    }
}
