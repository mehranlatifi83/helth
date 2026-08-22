package ir.mehranlatifi83.roozara.ui;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.os.LocaleListCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.materialswitch.MaterialSwitch;

import ir.mehranlatifi83.roozara.R;
import ir.mehranlatifi83.roozara.manager.ScheduleManager;
import ir.mehranlatifi83.roozara.manager.SleepModeController;
import ir.mehranlatifi83.roozara.util.ActivityLog;
import ir.mehranlatifi83.roozara.manager.WaterReminderManager;
import ir.mehranlatifi83.roozara.receiver.SleepScheduleReceiver;
import ir.mehranlatifi83.roozara.service.WakeAlarmService;
import ir.mehranlatifi83.roozara.util.JalaliCalendar;
import ir.mehranlatifi83.roozara.util.TimePickerHelper;

import java.util.Calendar;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS                = "helth_prefs";
    private static final String KEY_OB_DND_SHOWN     = "onboarding_dnd_shown";
    private static final String KEY_OB_OVERLAY_SHOWN = "onboarding_overlay_shown";
    private static final String KEY_PRIVACY_ACCEPTED = "privacy_policy_accepted";
    private static final String KEY_GUIDE_SHOWN      = "guide_shown";
    private static final String KEY_USE_JALALI       = "use_jalali_calendar";

    private TextView       textSleepTime;
    private TextView       textWakeTime;
    private TextView       textScheduleHint;
    private MaterialSwitch switchSchedule;
    private TextView       textLockMode;
    private TextView       textOverlayStatus;
    private TextView       textSoundName;

    private boolean pendingScheduleEnable = false;
    private boolean requirementsWarningShown = false;

    private final ActivityResultLauncher<Intent> vpnLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    if (pendingScheduleEnable) {
                        pendingScheduleEnable = false;
                        ScheduleManager.setScheduleEnabled(this, true);
                        updateScheduleUI();
                    }
                } else {
                    pendingScheduleEnable = false;
                }
            });

    private final ActivityResultLauncher<Intent> ringtoneLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getParcelableExtra(
                            android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
                    saveAlarmSoundUri(uri != null ? uri.toString() : null);
                }
            });

    private final ActivityResultLauncher<Intent> fileLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        saveAlarmSoundUri(uri.toString());
                    }
                }
            });

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        setupBottomNav();
        setupScheduleCard();
        requestNotificationPermissionIfNeeded();
        showPrivacyPolicyIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateScheduleUI();
        updateOverlayUI();
        checkOnboarding();
        verifyEnabledScheduleRequirements();
    }

    // ─── View wiring ─────────────────────────────────────────────────────────

    private void bindViews() {
        textSleepTime     = findViewById(R.id.text_sleep_time);
        textWakeTime      = findViewById(R.id.text_wake_time);
        textScheduleHint  = findViewById(R.id.text_schedule_hint);
        switchSchedule    = findViewById(R.id.switch_schedule);
        textLockMode      = findViewById(R.id.text_lock_mode);
        textOverlayStatus = findViewById(R.id.text_overlay_status);
        textSoundName     = findViewById(R.id.text_sound_name);

        ((TextView) findViewById(R.id.text_date)).setText(buildLocalizedDate());

        updateScheduleUI();
        updateLockModeUI();
        updateOverlayUI();
        updateSoundUI();

        findViewById(R.id.row_lock_mode).setOnClickListener(v -> toggleLockMode());
        findViewById(R.id.row_overlay).setOnClickListener(v -> onOverlayRowTapped());
        findViewById(R.id.row_sound).setOnClickListener(v -> showSoundPickerDialog());
        ((ImageButton) findViewById(R.id.btn_more)).setOnClickListener(v -> showMoreOptionsMenu(v));
    }

    private void setupBottomNav() {
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        nav.setSelectedItemId(R.id.nav_sleep);
        nav.setOnItemSelectedListener(item -> {
            if (item.getItemId() == R.id.nav_water) {
                startActivity(new Intent(this, WaterActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                finish();
                return true;
            }
            return true;
        });
    }

    private void setupScheduleCard() {
        findViewById(R.id.row_sleep_time).setOnClickListener(v -> showSleepTimePicker());
        findViewById(R.id.row_wake_time).setOnClickListener(v -> showWakeTimePicker());
        switchSchedule.setOnCheckedChangeListener((btn, checked) -> onScheduleSwitchChanged(checked));
    }

    // ─── Schedule time pickers ───────────────────────────────────────────────

    private void showSleepTimePicker() {
        int[] saved = ScheduleManager.getSleepTime(this);
        int h = saved != null ? saved[0] : 23;
        int m = saved != null ? saved[1] : 0;
        TimePickerHelper.show(getSupportFragmentManager(), this,
                getString(R.string.picker_sleep_title), h, m, (hour, min) -> {
                    ScheduleManager.saveSleepTime(this, hour, min);
                    onScheduleTimeChanged();
                });
    }

    private void showWakeTimePicker() {
        int[] saved = ScheduleManager.getWakeTime(this);
        int h = saved != null ? saved[0] : 7;
        int m = saved != null ? saved[1] : 0;
        TimePickerHelper.show(getSupportFragmentManager(), this,
                getString(R.string.picker_wake_title), h, m, (hour, min) -> {
                    ScheduleManager.saveWakeTime(this, hour, min);
                    onScheduleTimeChanged();
                });
    }

    private void onScheduleTimeChanged() {
        if (ScheduleManager.isScheduleEnabled(this)) {
            ScheduleManager.scheduleSleepAlarm(this);
            ScheduleManager.scheduleWakeAlarm(this);
            ScheduleManager.scheduleSleepReminderAlarm(this);
        }
        // Water reminder windows are computed from the wake/sleep times, so a change
        // here invalidates their already-scheduled alarms too.
        if (WaterReminderManager.isEnabled(this)) {
            WaterReminderManager.scheduleAll(this);
        }
        updateScheduleUI();
    }

    private void onScheduleSwitchChanged(boolean checked) {
        if (checked && !ScheduleManager.hasSchedule(this)) {
            switchSchedule.setChecked(false);
            showSleepTimePicker();
            return;
        }
        if (checked && !ScheduleManager.canScheduleExact(this)) {
            switchSchedule.setChecked(false);
            showAlarmPermissionDialog();
            return;
        }
        if (checked && !getSystemService(NotificationManager.class)
                .isNotificationPolicyAccessGranted()) {
            switchSchedule.setChecked(false);
            showDndPermissionDialog();
            return;
        }
        if (checked) {
            // Pre-authorize VPN so it works when the schedule fires automatically at night.
            Intent vpnIntent = VpnService.prepare(this);
            if (vpnIntent != null) {
                pendingScheduleEnable = true;
                vpnLauncher.launch(vpnIntent);
                return;
            }
        }
        ScheduleManager.setScheduleEnabled(this, checked);
        ActivityLog.log(this, "sleep schedule switched " + (checked ? "on" : "off"));

        // Switching the schedule off has to undo a night that is already running, not
        // just cancel tomorrow's alarms. Otherwise turning it off mid-day left the phone
        // silent and the internet blocked with nothing left to put either back.
        if (!checked && SleepModeController.isSleepActive(this)) {
            SleepModeController.releaseSystemState(this, "schedule_switched_off");
            WakeAlarmService.stop(this);
            SleepOverlayGuard.hide(this);
        }

        updateScheduleUI();
    }

    private void verifyEnabledScheduleRequirements() {
        if (!ScheduleManager.isScheduleEnabled(this)) return;
        if (ScheduleManager.canScheduleExact(this)) {
            // Repairs alarms after an app update or if an OEM cleared pending alarms.
            ScheduleManager.rescheduleIfEnabled(this);
        }
        // The early-exit check is what stops the app restarting a night the user has
        // already earned their way out of: leaving early clears "sleep active", and
        // without this, simply opening the app put the lock screen straight back up.
        if (ScheduleManager.isInsideSleepWindow(this)
                && !SleepModeController.isSleepActive(this)
                && !SleepModeController.wasCycleLeftEarly(this)) {
            sendBroadcast(new Intent(this, SleepScheduleReceiver.class)
                    .setAction(SleepScheduleReceiver.ACTION_SLEEP));
        }
        if (requirementsWarningShown) return;
        if (!ScheduleManager.canScheduleExact(this)) {
            requirementsWarningShown = true;
            showAlarmPermissionDialog();
        } else if (VpnService.prepare(this) != null) {
            requirementsWarningShown = true;
            new AlertDialog.Builder(this)
                    .setTitle(R.string.vpn_permission_missing_title)
                    .setMessage(R.string.vpn_permission_missing_text)
                    .setPositiveButton(R.string.go_to_settings, (d, w) -> {
                        pendingScheduleEnable = true;
                        vpnLauncher.launch(VpnService.prepare(this));
                    })
                    .setNegativeButton(R.string.later, null)
                    .show();
        }
    }

    // ─── UI state ────────────────────────────────────────────────────────────

    private void updateScheduleUI() {
        int[] sleep = ScheduleManager.getSleepTime(this);
        int[] wake  = ScheduleManager.getWakeTime(this);
        boolean on  = ScheduleManager.isScheduleEnabled(this);

        textSleepTime.setText(sleep != null ? fmt(sleep[0], sleep[1]) : getString(R.string.schedule_not_set));
        textWakeTime.setText(wake   != null ? fmt(wake[0],  wake[1])  : getString(R.string.schedule_not_set));

        switchSchedule.setOnCheckedChangeListener(null);
        switchSchedule.setChecked(on);
        switchSchedule.setOnCheckedChangeListener((btn, checked) -> onScheduleSwitchChanged(checked));

        textScheduleHint.setText(!ScheduleManager.hasSchedule(this)
                ? R.string.tap_to_set
                : on ? R.string.schedule_active : R.string.schedule_inactive);
    }

    private String fmt(int h, int m) {
        return String.format(Locale.getDefault(), "%02d:%02d", h, m);
    }

    private void toggleLockMode() {
        String[] labels = {
            getString(R.string.challenge_simple),
            getString(R.string.challenge_memory),
            getString(R.string.challenge_math)
        };
        String[] keys = {
            SleepLockActivity.CHALLENGE_SIMPLE,
            SleepLockActivity.CHALLENGE_MEMORY,
            SleepLockActivity.CHALLENGE_MATH
        };
        String current = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(SleepLockActivity.PREF_CHALLENGE, SleepLockActivity.CHALLENGE_SIMPLE);
        int checkedItem = 0;
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(current)) { checkedItem = i; break; }

        new AlertDialog.Builder(this)
                .setTitle(R.string.challenge_select_title)
                .setSingleChoiceItems(labels, checkedItem, (d, which) -> {
                    getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putString(SleepLockActivity.PREF_CHALLENGE, keys[which]).apply();
                    updateLockModeUI();
                    d.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ─── Privacy policy ──────────────────────────────────────────────────────

    private void showPrivacyPolicyIfNeeded() {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_PRIVACY_ACCEPTED, false)) {
            showGuideIfNeeded();
            return;
        }

        String lang = Locale.getDefault().getLanguage();
        String file = "fa".equals(lang) ? "privacy_policy_fa.html" : "privacy_policy_en.html";

        android.webkit.WebView webView = new android.webkit.WebView(this);
        webView.loadUrl("file:///android_asset/" + file);

        new AlertDialog.Builder(this)
                .setTitle(R.string.privacy_policy_title)
                .setView(webView)
                .setCancelable(false)
                .setPositiveButton(R.string.accept, (d, w) -> {
                    prefs.edit().putBoolean(KEY_PRIVACY_ACCEPTED, true).apply();
                    showGuideIfNeeded();
                })
                .setNegativeButton(R.string.decline, (d, w) -> finishAffinity())
                .show();
    }

    private void showGuideIfNeeded() {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_GUIDE_SHOWN, false)) return;
        prefs.edit().putBoolean(KEY_GUIDE_SHOWN, true).apply();
        showGuide();
    }

    private void showGuide() {
        String lang = Locale.getDefault().getLanguage();
        String file = "fa".equals(lang) ? "guide_fa.html" : "guide_en.html";

        android.webkit.WebView webView = new android.webkit.WebView(this);
        webView.loadUrl("file:///android_asset/" + file);

        new AlertDialog.Builder(this)
                .setTitle(R.string.guide_title)
                .setView(webView)
                .setCancelable(false)
                .setPositiveButton(R.string.got_it, null)
                .show();
    }

    private void showPrivacyPolicy() {
        String lang = Locale.getDefault().getLanguage();
        String file = "fa".equals(lang) ? "privacy_policy_fa.html" : "privacy_policy_en.html";

        android.webkit.WebView webView = new android.webkit.WebView(this);
        webView.loadUrl("file:///android_asset/" + file);

        new AlertDialog.Builder(this)
                .setTitle(R.string.privacy_policy_title)
                .setView(webView)
                .setPositiveButton(R.string.got_it, null)
                .show();
    }

    // ─── More options menu ────────────────────────────────────────────────────

    private void showMoreOptionsMenu(android.view.View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor, Gravity.END);
        popup.getMenu().add(0, 1, 0, getString(R.string.menu_language));
        popup.getMenu().add(0, 2, 1, getString(R.string.menu_calendar));
        popup.getMenu().add(0, 5, 2, getString(R.string.menu_permissions));
        popup.getMenu().add(0, 3, 3, getString(R.string.menu_guide));
        popup.getMenu().add(0, 4, 4, getString(R.string.menu_privacy));
        popup.getMenu().add(0, 6, 5, getString(R.string.menu_activity_log));
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: showLanguagePicker(); return true;
                case 2: showCalendarPicker(); return true;
                case 5: startActivity(new Intent(this, PermissionsActivity.class)); return true;
                case 3: showGuide();          return true;
                case 4: showPrivacyPolicy();  return true;
                case 6: showActivityLogMenu(); return true;
            }
            return false;
        });
        popup.show();
    }

    // ─── Activity log ────────────────────────────────────────────────────────

    /**
     * The log controls, gathered in one dialog.
     *
     * The recording state is spelled out in the first item's own label rather than
     * shown as a checkmark, so it is announced when the list is read out.
     */
    private void showActivityLogMenu() {
        boolean enabled = ActivityLog.isEnabled(this);
        String[] options = {
                getString(enabled ? R.string.log_stop_recording : R.string.log_start_recording),
                getString(R.string.log_share),
                getString(R.string.log_clear),
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_activity_log)
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0: toggleActivityLog(!enabled); break;
                        case 1: shareActivityLog();          break;
                        default: clearActivityLog();         break;
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void toggleActivityLog(boolean enabled) {
        ActivityLog.setEnabled(this, enabled);
        toast(getString(enabled ? R.string.log_recording_on : R.string.log_recording_off));
    }

    private void shareActivityLog() {
        Intent share = ActivityLog.shareIntent(this);
        if (share == null) {
            toast(getString(R.string.log_empty));
            return;
        }
        startActivity(Intent.createChooser(share, getString(R.string.log_share)));
    }

    private void clearActivityLog() {
        toast(getString(ActivityLog.clear(this) ? R.string.log_cleared : R.string.log_clear_failed));
    }

    private void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
    }

    private void showLanguagePicker() {
        String[] labels = { getString(R.string.lang_persian), getString(R.string.lang_english) };
        String[] tags   = { "fa", "en" };

        LocaleListCompat current = AppCompatDelegate.getApplicationLocales();
        String currentTag = current.isEmpty()
                ? Locale.getDefault().getLanguage()
                : current.get(0).getLanguage();
        int checked = "fa".equals(currentTag) ? 0 : 1;

        new AlertDialog.Builder(this)
                .setTitle(R.string.language_title)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    AppCompatDelegate.setApplicationLocales(
                            LocaleListCompat.forLanguageTags(tags[which]));
                    d.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showCalendarPicker() {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean useJalali = prefs.getBoolean(KEY_USE_JALALI,
                "fa".equals(Locale.getDefault().getLanguage()));

        String[] labels = { getString(R.string.calendar_jalali), getString(R.string.calendar_gregorian) };
        int checked = useJalali ? 0 : 1;

        new AlertDialog.Builder(this)
                .setTitle(R.string.calendar_title)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    prefs.edit().putBoolean(KEY_USE_JALALI, which == 0).apply();
                    ((TextView) findViewById(R.id.text_date)).setText(buildLocalizedDate());
                    d.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ─── Onboarding ──────────────────────────────────────────────────────────

    private void checkOnboarding() {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        NotificationManager nm  = getSystemService(NotificationManager.class);

        if (!nm.isNotificationPolicyAccessGranted() && !prefs.getBoolean(KEY_OB_DND_SHOWN, false)) {
            prefs.edit().putBoolean(KEY_OB_DND_SHOWN, true).apply();
            showDndOnboardingDialog();
            return;
        }

        boolean dndHandled = nm.isNotificationPolicyAccessGranted()
                || prefs.getBoolean(KEY_OB_DND_SHOWN, false);
        if (dndHandled
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)
                && !prefs.getBoolean(KEY_OB_OVERLAY_SHOWN, false)) {
            prefs.edit().putBoolean(KEY_OB_OVERLAY_SHOWN, true).apply();
            showOverlayOnboardingDialog();
        }
    }

    private void showDndOnboardingDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dnd_dialog_title)
                .setMessage(R.string.dnd_onboarding_message)
                .setPositiveButton(R.string.go_to_settings, (d, w) ->
                        startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)))
                .setNegativeButton(R.string.later, (d, w) -> checkOnboarding())
                .setCancelable(false)
                .show();
    }

    private void showOverlayOnboardingDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.overlay_dialog_title)
                .setMessage(R.string.overlay_onboarding_message)
                .setPositiveButton(R.string.go_to_settings, (d, w) ->
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()))))
                .setNegativeButton(R.string.later, null)
                .setCancelable(false)
                .show();
    }

    private void onOverlayRowTapped() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.overlay_dialog_title)
                .setMessage(R.string.overlay_dialog_message)
                .setPositiveButton(R.string.go_to_settings, (d, w) ->
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()))))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void updateOverlayUI() {
        boolean granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(this);
        textOverlayStatus.setText(granted ? R.string.overlay_on : R.string.overlay_off);
    }

    private void updateLockModeUI() {
        String mode = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(SleepLockActivity.PREF_CHALLENGE, SleepLockActivity.CHALLENGE_SIMPLE);
        int res;
        switch (mode) {
            case SleepLockActivity.CHALLENGE_MEMORY: res = R.string.challenge_memory; break;
            case SleepLockActivity.CHALLENGE_MATH:   res = R.string.challenge_math;   break;
            default:                                 res = R.string.challenge_simple;  break;
        }
        textLockMode.setText(res);
    }

    // ─── Sound picker ─────────────────────────────────────────────────────────

    private void showSoundPickerDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.choose_sound_title)
                .setItems(new CharSequence[]{
                        getString(R.string.choose_system_ringtone),
                        getString(R.string.choose_audio_file)
                }, (dialog, which) -> {
                    if (which == 0) openSystemRingtonePicker();
                    else            openFilePicker();
                })
                .show();
    }

    private void openSystemRingtonePicker() {
        String saved = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(WakeAlarmService.PREF_SOUND_URI, null);
        Intent intent = new Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER);
        intent.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
                android.media.RingtoneManager.TYPE_ALARM);
        intent.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
        intent.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false);
        if (saved != null) {
            intent.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                    Uri.parse(saved));
        }
        ringtoneLauncher.launch(intent);
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        fileLauncher.launch(intent);
    }

    private void saveAlarmSoundUri(String uriStr) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(WakeAlarmService.PREF_SOUND_URI, uriStr).apply();
        updateSoundUI();
    }

    private void updateSoundUI() {
        String saved = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(WakeAlarmService.PREF_SOUND_URI, null);
        if (saved == null) {
            textSoundName.setText(R.string.sound_default);
            return;
        }
        Uri uri = Uri.parse(saved);
        android.media.Ringtone r = android.media.RingtoneManager.getRingtone(this, uri);
        String title = r != null ? r.getTitle(this) : null;
        textSoundName.setText(title != null ? title : getString(R.string.sound_custom_file));
    }

    // ─── Permission dialogs ──────────────────────────────────────────────────

    private void showDndPermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dnd_dialog_title)
                .setMessage(R.string.dnd_dialog_message)
                .setPositiveButton(R.string.go_to_settings, (d, w) ->
                        startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)))
                .setNegativeButton(R.string.cancel, null)
                .setCancelable(false)
                .show();
    }

    private void showAlarmPermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.alarm_permission_title)
                .setMessage(R.string.alarm_permission_message)
                .setPositiveButton(R.string.go_to_settings, (d, w) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM));
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (!nm.canUseFullScreenIntent()) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.fullscreen_permission_title)
                        .setMessage(R.string.fullscreen_permission_message)
                        .setPositiveButton(R.string.go_to_settings, (d, w) ->
                                startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                        Uri.parse("package:" + getPackageName()))))
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }
        }
    }

    // ─── Date ────────────────────────────────────────────────────────────────

    private String buildLocalizedDate() {
        Calendar cal = Calendar.getInstance();
        boolean useJalali = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_USE_JALALI, "fa".equals(Locale.getDefault().getLanguage()));
        if (useJalali) {
            String[] days   = {"یکشنبه","دوشنبه","سه‌شنبه","چهارشنبه","پنجشنبه","جمعه","شنبه"};
            String[] months = {"فروردین","اردیبهشت","خرداد","تیر","مرداد","شهریور",
                               "مهر","آبان","آذر","دی","بهمن","اسفند"};
            int[] j = JalaliCalendar.toJalali(
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH) + 1,
                    cal.get(Calendar.DAY_OF_MONTH));
            return days[cal.get(Calendar.DAY_OF_WEEK) - 1]
                    + "،  " + j[2] + " " + months[j[1] - 1] + " " + j[0];
        }
        return cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, java.util.Locale.getDefault())
                + ",  "
                + cal.getDisplayName(Calendar.MONTH, Calendar.LONG, java.util.Locale.getDefault())
                + " " + cal.get(Calendar.DAY_OF_MONTH) + ", " + cal.get(Calendar.YEAR);
    }
}
