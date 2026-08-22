package ir.mehranlatifi83.roozara.ui;

import android.Manifest;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import ir.mehranlatifi83.roozara.R;
import ir.mehranlatifi83.roozara.util.VendorSupport;

public class PermissionsActivity extends AppCompatActivity {

    /** Set when we send the user to grant overlay access, so we can follow up on return. */
    private boolean awaitingOverlayResult = false;

    private final ActivityResultLauncher<String> notificationLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> updateRows());
    private final ActivityResultLauncher<Intent> vpnLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> updateRows());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permissions);
        findViewById(R.id.button_back).setOnClickListener(v -> finish());
        setupRows();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateRows();
        if (awaitingOverlayResult) {
            followUpAfterOverlayGranted();
        }
    }

    private void setupRows() {
        bind(R.id.row_notifications, R.string.permission_notifications,
                R.string.permission_notifications_desc, v -> openNotifications());
        bind(R.id.row_exact_alarm, R.string.permission_exact_alarm,
                R.string.permission_exact_alarm_desc, v -> openExactAlarm());
        bind(R.id.row_vpn, R.string.permission_vpn,
                R.string.permission_vpn_desc, v -> openVpn());
        bind(R.id.row_dnd, R.string.permission_dnd,
                R.string.permission_dnd_desc, v ->
                        safeStart(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)));
        bind(R.id.row_overlay, R.string.permission_overlay,
                R.string.permission_overlay_desc, v -> openOverlay());
        bind(R.id.row_fullscreen, R.string.permission_fullscreen,
                R.string.permission_fullscreen_desc, v -> openFullScreen());
        bind(R.id.row_battery, R.string.permission_battery,
                R.string.permission_battery_desc, v ->
                        safeStart(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));

        setupVendorRows();
    }

    /**
     * Rows that only exist on ROMs which add their own restrictions. On stock Android
     * they are hidden entirely rather than shown as permanently ungranted, which would
     * be noise a screen reader has to read past on every visit.
     */
    private void setupVendorRows() {
        View popupRow = findViewById(R.id.row_vendor_popup);
        if (VendorSupport.hasBackgroundPopupSetting()
                && VendorSupport.backgroundPopupIntent(this) != null) {
            bind(R.id.row_vendor_popup, R.string.permission_vendor_popup,
                    R.string.permission_vendor_popup_desc,
                    v -> safeStart(VendorSupport.backgroundPopupIntent(this)));
        } else {
            popupRow.setVisibility(View.GONE);
        }

        View autostartRow = findViewById(R.id.row_vendor_autostart);
        if (VendorSupport.hasAutostartSetting() && VendorSupport.autostartIntent(this) != null) {
            bind(R.id.row_vendor_autostart, R.string.permission_vendor_autostart,
                    R.string.permission_vendor_autostart_desc,
                    v -> safeStart(VendorSupport.autostartIntent(this)));
        } else {
            autostartRow.setVisibility(View.GONE);
        }
    }

    private void bind(int rowId, int title, int description, View.OnClickListener listener) {
        View row = findViewById(rowId);
        ((TextView) row.findViewById(R.id.permission_name)).setText(title);
        ((TextView) row.findViewById(R.id.permission_description)).setText(description);
        row.setOnClickListener(listener);
    }

    private void updateRows() {
        boolean notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        notifications &= NotificationManagerCompat.from(this).areNotificationsEnabled();
        status(R.id.row_notifications, notifications, false);

        boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || getSystemService(AlarmManager.class).canScheduleExactAlarms();
        status(R.id.row_exact_alarm, exact, false);
        status(R.id.row_vpn, VpnService.prepare(this) == null, false);
        status(R.id.row_dnd, getSystemService(NotificationManager.class)
                .isNotificationPolicyAccessGranted(), false);
        boolean overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(this);
        status(R.id.row_overlay, overlay, true);

        if (VendorSupport.hasBackgroundPopupSetting()) {
            // Neither MIUI setting can be queried from an app, so these rows never claim
            // to know the answer. Saying "Granted" without evidence would be worse than
            // saying nothing, especially for a user who cannot glance at the toggle.
            statusUnknown(R.id.row_vendor_popup);
        }
        if (VendorSupport.hasAutostartSetting()) {
            statusUnknown(R.id.row_vendor_autostart);
        }

        boolean fullScreen = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                || getSystemService(NotificationManager.class).canUseFullScreenIntent();
        status(R.id.row_fullscreen, fullScreen, true);

        PowerManager pm = getSystemService(PowerManager.class);
        status(R.id.row_battery, pm.isIgnoringBatteryOptimizations(getPackageName()), true);
    }

    private void statusUnknown(int rowId) {
        View row = findViewById(rowId);
        if (row.getVisibility() != View.VISIBLE) return;
        TextView text = row.findViewById(R.id.permission_status);
        text.setText(R.string.permission_check);
        text.setTextColor(ContextCompat.getColor(this, R.color.colorOnSurfaceVariant));
    }

    private void status(int rowId, boolean granted, boolean optional) {
        TextView text = findViewById(rowId).findViewById(R.id.permission_status);
        text.setText(granted ? R.string.permission_granted
                : optional ? R.string.permission_recommended : R.string.permission_required);
        text.setTextColor(ContextCompat.getColor(this,
                granted ? R.color.colorPrimary : R.color.colorError));
    }

    private void openNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        safeStart(i);
    }

    private void openExactAlarm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            safeStart(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri()));
        }
    }

    private void openOverlay() {
        awaitingOverlayResult = true;
        safeStart(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri()));
    }

    /**
     * Overlay access on its own is not enough to keep the lock screen in place, so the
     * moment it is granted we walk the user through the two settings that complete it:
     * screen pinning, and on MIUI the background pop-up permission without which the
     * lock screen never appears at all.
     */
    private void followUpAfterOverlayGranted() {
        awaitingOverlayResult = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return;
        }

        if (VendorSupport.hasBackgroundPopupSetting()
                && VendorSupport.backgroundPopupIntent(this) != null) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.vendor_popup_prompt_title)
                    .setMessage(R.string.vendor_popup_prompt_message)
                    .setPositiveButton(R.string.open_settings,
                            (d, w) -> safeStart(VendorSupport.backgroundPopupIntent(this)))
                    .setNegativeButton(R.string.later, null)
                    .show();
        }
    }

    private void openVpn() {
        Intent consent = VpnService.prepare(this);
        if (consent != null) vpnLauncher.launch(consent);
    }

    private void openFullScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            safeStart(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri()));
        }
    }

    private Uri packageUri() {
        return Uri.parse("package:" + getPackageName());
    }

    private void safeStart(Intent intent) {
        try {
            startActivity(intent);
        } catch (Exception ignored) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri()));
        }
    }
}
