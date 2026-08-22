package ir.mehranlatifi83.roozara.ui;

import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import ir.mehranlatifi83.roozara.R;
import ir.mehranlatifi83.roozara.manager.ScheduleManager;
import ir.mehranlatifi83.roozara.manager.SleepModeController;
import ir.mehranlatifi83.roozara.util.ActivityLog;
import ir.mehranlatifi83.roozara.service.SleepVpnService;
import ir.mehranlatifi83.roozara.service.WakeAlarmService;
import ir.mehranlatifi83.roozara.util.JalaliCalendar;

import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

public class SleepLockActivity extends AppCompatActivity {

    // ─── Public constants ────────────────────────────────────────────────────
    public static final String CHALLENGE_SIMPLE = "simple";
    public static final String CHALLENGE_MEMORY = "memory";
    public static final String CHALLENGE_MATH   = "math";
    public static final String PREF_CHALLENGE   = "wake_challenge_mode";
    public static final String KEY_SLEEP_START  = "sleep_start_time";

    private static final String PREFS = "helth_prefs";

    // ─── Views ───────────────────────────────────────────────────────────────

    // Countdown section (always visible during sleep)
    private LinearLayout   sectionTimed;
    private TextView       textCountdown;
    private TextView       textCountdownLabel;
    private TextView       textNoExitHint;
    private MaterialButton btnConfirmAwake;
    private MaterialButton btnEarlyExit;
    private View           dividerEarlyExit;
    private TextView       textEarlyExitHint;

    // Challenge section (math or memory — shown for alarm and early exit)
    private LinearLayout      sectionChallenge;
    private TextView          textChallengePrompt;
    private TextView          textMathProblem;
    private TextInputEditText editAnswer;
    private TextInputLayout   inputLayoutAnswer;
    private TextView          textError;
    private MaterialButton    btnRetryMemory;

    // Clock (shared)
    private TextView textClock;
    private TextView textLockDate;

    // ─── State ───────────────────────────────────────────────────────────────
    private int     mathAnswer;
    private String  memorySequence;
    private int     wrongCount          = 0;
    private boolean exitCalled          = false;
    private boolean wakeChallengeActive = false;
    private boolean earlyExitButtonShown = false;

    // True while this activity is the visible foreground activity.
    private static boolean isInForeground = false;

    public static boolean isActivityInForeground() { return isInForeground; }

    private CountDownTimer countDownTimer;
    private final Handler  handler   = new Handler(Looper.getMainLooper());
    private final Runnable clockTick = new Runnable() {
        @Override public void run() { updateClock(); handler.postDelayed(this, 1000); }
    };

    // Delayed relaunch decision posted in onPause(). We check the actual screen power
    // state via PowerManager rather than racing an ACTION_SCREEN_OFF broadcast against
    // the activity lifecycle — that race was the cause of inconsistent behavior (the
    // lock screen sometimes turning itself back on right after the user powered the
    // screen off, or failing to come back when it should have).
    private final Runnable relaunchIfNeeded = () -> {
        if (!isDestroyed() && !isFinishing() && !exitCalled && isScreenOn()) {
            SleepLockActivity.launch(SleepLockActivity.this);
        }
    };

    private boolean isScreenOn() {
        android.os.PowerManager pm =
                (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }

    // ─── Entry point ─────────────────────────────────────────────────────────

    public static void launch(Context ctx) {
        ctx.startActivity(new Intent(ctx, SleepLockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupWindowFlags();
        setContentView(R.layout.activity_sleep_lock);
        hideSystemBars();
        blockBackButton();
        bindViews();
        updateClock();

        boolean wakeAlarmActive = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(WakeAlarmService.KEY_WAKE_ALARM_ACTIVE, false);
        if (wakeAlarmActive) {
            wakeChallengeActive = true;
            keepScreenOn();
            showWakeChallengeUI();
        } else {
            startCountdown();
        }
    }

    private void bindViews() {
        textClock           = findViewById(R.id.text_clock);
        textLockDate        = findViewById(R.id.text_lock_date);
        sectionTimed        = findViewById(R.id.section_timed);
        textCountdown       = findViewById(R.id.text_countdown);
        textCountdownLabel  = findViewById(R.id.text_countdown_label);
        textNoExitHint      = findViewById(R.id.text_no_exit_hint);
        btnConfirmAwake     = findViewById(R.id.btn_confirm_awake);
        btnEarlyExit        = findViewById(R.id.btn_early_exit);
        dividerEarlyExit    = findViewById(R.id.divider_early_exit);
        textEarlyExitHint   = findViewById(R.id.text_early_exit_hint);
        sectionChallenge    = findViewById(R.id.section_math);
        textChallengePrompt = findViewById(R.id.text_challenge_prompt);
        textMathProblem     = findViewById(R.id.text_math_problem);
        inputLayoutAnswer   = findViewById(R.id.input_layout_answer);
        editAnswer          = findViewById(R.id.edit_answer);
        textError           = findViewById(R.id.text_error);
        btnRetryMemory      = findViewById(R.id.btn_retry_memory);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Cancel any pending relaunch (e.g., we resumed before the 300 ms fired).
        isInForeground = true;
        handler.removeCallbacks(relaunchIfNeeded);
        handler.post(clockTick);
        hideSystemBars();
        // The overlay guard exists to cover the screen while this activity is away.
        // Now that we are back, take it down.
        SleepOverlayGuard.hide(this);
        // Self-heal the internet block. If the tunnel was torn down by a process kill or
        // by another VPN app taking the slot, it comes back rather than leaving the user
        // quietly online until morning.
        if (!exitCalled) SleepVpnService.ensureRunning(this);
        if (wakeChallengeActive) {
            keepScreenOn();
        } else {
            // Re-evaluate against the wall clock every time the screen/activity comes
            // back to foreground. A Handler.postDelayed() scheduled while the device was
            // in deep sleep can fire late (or effectively never, if the process was killed
            // in the meantime), so the early-exit button could stay hidden even though the
            // half-sleep-time threshold already passed. Recomputing here self-corrects.
            scheduleEarlyExitUnlock();
        }
    }

    /**
     * Called just before the user leaves via Home or Recents.
     *
     * The relaunch below is debounced, which is right for a transient pause but leaves
     * the launcher visible for those few hundred milliseconds. Raising the overlay here
     * closes that gap: it is up before the home screen is ever drawn.
     */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (!exitCalled) SleepOverlayGuard.show(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        isInForeground = false;
        handler.removeCallbacks(clockTick);
        // During wake alarm, clear FLAG_KEEP_SCREEN_ON so the power button can turn
        // the screen off. It is restored in onResume() when the user comes back.
        if (wakeChallengeActive) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        // Small debounce so a transient pause (e.g. a system dialog briefly stealing
        // focus) doesn't immediately relaunch; the actual decision is based on the
        // screen's power state at the time this runs, not on elapsed time.
        // Cover whatever is behind us until this activity is back. Only does anything
        // when overlay access is granted; without it the app has no way to draw over
        // another app and falls back to the relaunch below on its own.
        if (!exitCalled && isScreenOn()) SleepOverlayGuard.show(this);
        handler.postDelayed(relaunchIfNeeded, 300);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!wakeChallengeActive) {
            boolean wakeAlarmActive = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getBoolean(WakeAlarmService.KEY_WAKE_ALARM_ACTIVE, false);
            if (wakeAlarmActive) {
                if (countDownTimer != null) { countDownTimer.cancel(); countDownTimer = null; }
                handler.removeCallbacksAndMessages(null);
                // Do not re-post clockTick here — onResume() will do it.
                wakeChallengeActive = true;
                keepScreenOn();
                showWakeChallengeUI();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (countDownTimer != null) countDownTimer.cancel();
        handler.removeCallbacksAndMessages(null);
    }

    // ─── Window ──────────────────────────────────────────────────────────────

    private void setupWindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    }

    private void keepScreenOn() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void hideSystemBars() {
        WindowInsetsControllerCompat ctrl =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        // BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE let a swipe bring the navigation buttons
        // and the notification shade straight back, which is how people were leaving the
        // lock screen. The default behaviour keeps them hidden.
        ctrl.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_DEFAULT);
        ctrl.hide(WindowInsetsCompat.Type.systemBars());
    }

    private void blockBackButton() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { /* intentionally empty */ }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)
            return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            View focused = getCurrentFocus();
            if (focused instanceof EditText) {
                Rect rect = new Rect();
                focused.getGlobalVisibleRect(rect);
                if (!rect.contains((int) event.getRawX(), (int) event.getRawY())) {
                    focused.clearFocus();
                    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                            .hideSoftInputFromWindow(focused.getWindowToken(), 0);
                }
            }
        }
        return super.dispatchTouchEvent(event);
    }

    // ─── Clock ───────────────────────────────────────────────────────────────

    private void updateClock() {
        Calendar cal = Calendar.getInstance();
        textClock.setText(String.format(Locale.getDefault(),
                "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)));
        textLockDate.setText(buildLocalizedDate(cal));
    }

    private String buildLocalizedDate(Calendar cal) {
        String lang = Locale.getDefault().getLanguage();
        if ("fa".equals(lang)) {
            String[] days   = {"یکشنبه","دوشنبه","سه‌شنبه","چهارشنبه","پنجشنبه","جمعه","شنبه"};
            String[] months = {"فروردین","اردیبهشت","خرداد","تیر","مرداد","شهریور",
                               "مهر","آبان","آذر","دی","بهمن","اسفند"};
            int[] j = JalaliCalendar.toJalali(
                    cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1,
                    cal.get(Calendar.DAY_OF_MONTH));
            return days[cal.get(Calendar.DAY_OF_WEEK) - 1] + "،  " + j[2] + " " + months[j[1] - 1];
        }
        return cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.getDefault())
                + ",  "
                + cal.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault())
                + " " + cal.get(Calendar.DAY_OF_MONTH);
    }

    // ─── Countdown ───────────────────────────────────────────────────────────

    private void startCountdown() {
        int[] wake = ScheduleManager.getWakeTime(this);
        if (wake == null) { textCountdown.setText(R.string.no_wake_time_set); return; }

        long wakeMs    = nextWakeMs(wake[0], wake[1]);
        long remaining = wakeMs - System.currentTimeMillis();

        countDownTimer = new CountDownTimer(remaining, 1000) {
            @Override public void onTick(long ms) {
                long h = ms / 3_600_000, m = (ms % 3_600_000) / 60_000,
                     s = (ms % 60_000) / 1_000;
                textCountdown.setText(String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s));
            }
            @Override public void onFinish() {
                textCountdown.setText("00:00:00");
                onWakeTimeReached();
            }
        }.start();
    }

    private long nextWakeMs(int hour, int min) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, min);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis())
            cal.add(Calendar.DAY_OF_YEAR, 1);
        return cal.getTimeInMillis();
    }

    // ─── Early exit ──────────────────────────────────────────────────────────

    private final Runnable earlyExitRunnable = this::showEarlyExitButton;

    private void scheduleEarlyExitUnlock() {
        handler.removeCallbacks(earlyExitRunnable);
        if (earlyExitButtonShown || exitCalled || wakeChallengeActive) return;

        long sleepStartMs = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getLong(KEY_SLEEP_START, 0);
        long now    = System.currentTimeMillis();
        int[] wake  = ScheduleManager.getWakeTime(this);
        long wakeMs = (wake != null) ? nextWakeMs(wake[0], wake[1]) : 0;

        long halfSleepMs = (sleepStartMs > 0 && wakeMs > sleepStartMs)
                ? (wakeMs - sleepStartMs) / 2
                : 4 * 60 * 60 * 1000L;

        long delay = (sleepStartMs + halfSleepMs) - now;
        if (delay <= 0) showEarlyExitButton();
        else            handler.postDelayed(earlyExitRunnable, delay);
    }

    private void showEarlyExitButton() {
        if (exitCalled || earlyExitButtonShown) return;
        earlyExitButtonShown = true;
        dividerEarlyExit.setVisibility(View.VISIBLE);
        textEarlyExitHint.setVisibility(View.VISIBLE);
        btnEarlyExit.setVisibility(View.VISIBLE);
        btnEarlyExit.setOnClickListener(v -> onEarlyExitTapped());
    }

    private void onEarlyExitTapped() {
        String mode = challengeMode();
        switch (mode) {
            case CHALLENGE_SIMPLE:
                new AlertDialog.Builder(this)
                        .setTitle(R.string.early_exit_confirm_title)
                        .setMessage(R.string.early_exit_confirm_msg)
                        .setPositiveButton(R.string.confirm_awake, (d, w) -> exitSleepMode())
                        .setNegativeButton(R.string.cancel, null)
                        .show();
                break;
            case CHALLENGE_MEMORY:
                showEarlyExitMemoryDialog();
                break;
            default:
                showEarlyExitMathDialog();
                break;
        }
    }

    private void showEarlyExitMathDialog() {
        int[] qa = randomMathProblem(0);
        EditText et = buildAnswerInput();
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.early_exit_math_prompt))
                .setMessage(mathProblemString(qa))
                .setView(et)
                .setCancelable(true)
                .setPositiveButton(R.string.confirm, (d, w) -> {
                    if (checkInput(et, qa[0])) exitSleepMode();
                    else handler.post(this::showEarlyExitMathDialog);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showEarlyExitMemoryDialog() {
        String seq = randomDigitSequence(5);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.memory_show_prompt))
                .setMessage(formatSequence(seq))
                .setCancelable(false)
                .setPositiveButton(R.string.memory_got_it, (d, w) -> showEarlyExitMemoryInput(seq))
                .show();
    }

    private void showEarlyExitMemoryInput(String seq) {
        EditText et = buildAnswerInput();
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.memory_enter_prompt))
                .setView(et)
                .setCancelable(true)
                .setPositiveButton(R.string.confirm, (d, w) -> {
                    String typed = et.getText().toString().trim();
                    if (typed.equals(seq)) exitSleepMode();
                    else handler.post(this::showEarlyExitMemoryDialog);
                })
                .setNeutralButton(R.string.retry, (d, w) ->
                        handler.post(this::showEarlyExitMemoryDialog))
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    // ─── Wake alarm challenge ─────────────────────────────────────────────────

    private void onWakeTimeReached() {
        if (wakeChallengeActive) return;
        wakeChallengeActive = true;
        keepScreenOn();
        // Always start WakeAlarmService so the alarm rings. If SleepScheduleReceiver
        // also fires at the same time, it checks isActivityInForeground() and skips
        // its own start, preventing a double-start. If the service is already playing
        // (edge case where both race), WakeAlarmService.playAlarm() is idempotent.
        WakeAlarmService.start(this);
        showWakeChallengeUI();
    }

    private void showWakeChallengeUI() {
        textCountdownLabel.setVisibility(View.GONE);
        dividerEarlyExit.setVisibility(View.GONE);
        textEarlyExitHint.setVisibility(View.GONE);
        btnEarlyExit.setVisibility(View.GONE);

        String mode = challengeMode();
        switch (mode) {
            case CHALLENGE_MATH:   showAlarmMathChallenge();   break;
            case CHALLENGE_MEMORY: showAlarmMemoryChallenge(); break;
            default:               showAlarmSimple();           break;
        }
    }

    /** Simple mode: just tap the button to confirm awake and stop alarm. */
    private void showAlarmSimple() {
        textNoExitHint.setText(R.string.wake_time_reached);
        btnConfirmAwake.setVisibility(View.VISIBLE);
        btnConfirmAwake.setOnClickListener(v -> exitSleepMode());
    }

    /** Math mode: switch layout to show math challenge inline; alarm keeps ringing. */
    private void showAlarmMathChallenge() {
        sectionTimed.setVisibility(View.GONE);
        sectionChallenge.setVisibility(View.VISIBLE);
        inputLayoutAnswer.setVisibility(View.VISIBLE);
        textChallengePrompt.setText(R.string.alarm_math_prompt);
        MaterialButton btnConfirm = findViewById(R.id.btn_confirm);

        generateAlarmMathProblem();

        btnConfirm.setOnClickListener(v -> checkAlarmMathAnswer());
        editAnswer.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) { checkAlarmMathAnswer(); return true; }
            return false;
        });
    }

    private void generateAlarmMathProblem() {
        int[] qa = randomMathProblem(Math.min(wrongCount / 2, 2));
        mathAnswer = qa[0];
        textMathProblem.setVisibility(View.VISIBLE);
        textMathProblem.setText(mathProblemString(qa));
        editAnswer.setText("");
        textError.setVisibility(View.INVISIBLE);
    }

    private void checkAlarmMathAnswer() {
        String raw = editAnswer.getText() == null ? "" : editAnswer.getText().toString().trim();
        if (raw.isEmpty()) return;
        try {
            if (Integer.parseInt(raw) == mathAnswer) {
                exitSleepMode();
            } else {
                wrongCount++;
                String msg = wrongCount < 3
                        ? getString(R.string.wrong_try_again)
                        : wrongCount < 6
                            ? getString(R.string.wrong_count, wrongCount)
                            : getString(R.string.wrong_go_sleep, wrongCount);
                textError.setText(msg);
                textError.setVisibility(View.VISIBLE);
                editAnswer.startAnimation(AnimationUtils.loadAnimation(this, R.anim.shake));
                editAnswer.setText("");
                editAnswer.postDelayed(this::generateAlarmMathProblem, 900);
            }
        } catch (NumberFormatException e) {
            editAnswer.setText("");
        }
    }

    /** Memory mode: switch to challenge section, show sequence, then ask user to type it. */
    private void showAlarmMemoryChallenge() {
        sectionTimed.setVisibility(View.GONE);
        sectionChallenge.setVisibility(View.VISIBLE);
        inputLayoutAnswer.setVisibility(View.GONE);
        MaterialButton btnConfirm = findViewById(R.id.btn_confirm);
        btnConfirm.setVisibility(View.GONE);

        startAlarmMemoryReveal(btnConfirm);
    }

    private void startAlarmMemoryReveal(MaterialButton btnConfirm) {
        memorySequence = randomDigitSequence(5);
        textChallengePrompt.setText(R.string.alarm_memory_prompt);
        // Format with spaces so digits are easy to read
        textMathProblem.setText(formatSequence(memorySequence));
        textMathProblem.setVisibility(View.VISIBLE);
        textError.setVisibility(View.INVISIBLE);
        inputLayoutAnswer.setVisibility(View.GONE);
        btnConfirm.setVisibility(View.GONE);
        btnRetryMemory.setVisibility(View.GONE);

        handler.postDelayed(() -> {
            textMathProblem.setVisibility(View.GONE);
            textChallengePrompt.setText(R.string.memory_enter_prompt);
            inputLayoutAnswer.setVisibility(View.VISIBLE);
            inputLayoutAnswer.setHint(getString(R.string.memory_hint));
            editAnswer.setInputType(InputType.TYPE_CLASS_NUMBER);
            editAnswer.setText("");
            editAnswer.requestFocus();
            btnConfirm.setVisibility(View.VISIBLE);
            btnRetryMemory.setVisibility(View.VISIBLE);

            btnConfirm.setOnClickListener(v -> checkAlarmMemoryAnswer(btnConfirm));
            btnRetryMemory.setOnClickListener(v -> {
                textError.setVisibility(View.INVISIBLE);
                startAlarmMemoryReveal(btnConfirm);
            });
            editAnswer.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    checkAlarmMemoryAnswer(btnConfirm); return true;
                }
                return false;
            });
        }, 4000);
    }

    private void checkAlarmMemoryAnswer(MaterialButton btnConfirm) {
        if (editAnswer.getText() == null) return;
        String typed = editAnswer.getText().toString().trim();
        if (typed.isEmpty()) return;

        if (typed.equals(memorySequence)) {
            exitSleepMode();
        } else {
            textError.setText(R.string.wrong_try_again);
            textError.setVisibility(View.VISIBLE);
            editAnswer.startAnimation(AnimationUtils.loadAnimation(this, R.anim.shake));
            editAnswer.setText("");
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String challengeMode() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_CHALLENGE, CHALLENGE_SIMPLE);
    }

    /**
     * Returns int[]{answer, a, b, op} where op: 0=mult,1=add,2=sub.
     * difficulty 0 = easy; 1 = chained; 2 = double multiply.
     */
    private int[] randomMathProblem(int difficulty) {
        Random rnd = new Random();
        switch (difficulty) {
            case 1: {
                int a = 3 + rnd.nextInt(7), b = 3 + rnd.nextInt(7);
                int c = 11 + rnd.nextInt(29), base = a * b;
                if (rnd.nextBoolean() || base <= c)
                    return new int[]{base + c, a, b, 10, c};  // op=10: (a×b)+c
                else
                    return new int[]{base - c, a, b, 11, c};  // op=11: (a×b)-c
            }
            case 2: {
                int a = 3 + rnd.nextInt(7), b = 3 + rnd.nextInt(7);
                int c = 3 + rnd.nextInt(6), d = 3 + rnd.nextInt(6);
                return new int[]{a * b + c * d, a, b, 20, c, d};  // op=20: (a×b)+(c×d)
            }
            default: {
                int type = rnd.nextInt(3);
                if (type == 0) {
                    int a = 12 + rnd.nextInt(29), b = 3 + rnd.nextInt(7);
                    return new int[]{a * b, a, b, 0};  // a×b
                } else if (type == 1) {
                    int a = 30 + rnd.nextInt(60), b = 20 + rnd.nextInt(40);
                    return new int[]{a + b, a, b, 1};  // a+b
                } else {
                    int b = 10 + rnd.nextInt(40), a = b + 20 + rnd.nextInt(40);
                    return new int[]{a - b, a, b, 2};  // a-b
                }
            }
        }
    }

    private String mathProblemString(int[] qa) {
        // qa = {answer, a, b, opCode, [c, [d]]}
        int opCode = qa[3];
        if (opCode == 0)  return qa[1] + " × " + qa[2] + " = ؟";
        if (opCode == 1)  return qa[1] + " + " + qa[2] + " = ؟";
        if (opCode == 2)  return qa[1] + " - " + qa[2] + " = ؟";
        if (opCode == 10) return "(" + qa[1] + " × " + qa[2] + ") + " + qa[4] + " = ؟";
        if (opCode == 11) return "(" + qa[1] + " × " + qa[2] + ") - " + qa[4] + " = ؟";
        if (opCode == 20) return "(" + qa[1] + " × " + qa[2] + ") + (" + qa[4] + " × " + qa[5] + ") = ؟";
        return "؟";
    }

    private String formatSequence(String seq) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < seq.length(); i++) {
            if (i > 0) sb.append("  ");
            sb.append(seq.charAt(i));
        }
        return sb.toString();
    }

    private String randomDigitSequence(int length) {
        Random rnd = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) sb.append(rnd.nextInt(10));
        return sb.toString();
    }

    private EditText buildAnswerInput() {
        EditText et = new EditText(this);
        et.setHint(R.string.answer_hint);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setGravity(Gravity.CENTER);
        et.setPadding(64, 24, 64, 24);
        return et;
    }

    private boolean checkInput(EditText et, int expected) {
        try { return Integer.parseInt(et.getText().toString().trim()) == expected; }
        catch (NumberFormatException e) { return false; }
    }

    // ─── Exit ────────────────────────────────────────────────────────────────

    private void exitSleepMode() {
        exitCalled = true;
        ActivityLog.log(this, "leaving sleep mode",
                "via=" + (wakeChallengeActive ? "wake_challenge" : "early_exit"));
        SleepOverlayGuard.hide(this);
        // Only stop WakeAlarmService if the alarm is actually active (wake time reached).
        // Calling stop() before wake time starts the service just to dismiss it, causing
        // a brief notification flash.
        if (getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(WakeAlarmService.KEY_WAKE_ALARM_ACTIVE, false)) {
            WakeAlarmService.stop(this);
        }

        // Stops the tunnel, puts the ringer back exactly as it was before bedtime
        // rather than forcing it to normal, and clears the sleep flags.
        SleepModeController.releaseSystemState(this, wakeChallengeActive ? "wake_challenge_passed"
                                                                        : "early_exit");
        if (!wakeChallengeActive) {
            // Only an early exit ends the night ahead of time; passing the wake
            // challenge happens at the wake time itself and needs no marker.
            SleepModeController.markCycleLeftEarly(this);
        }

        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(WakeAlarmService.KEY_WAKE_ALARM_ACTIVE, false)
                .apply();

        getSystemService(NotificationManager.class).cancel(2);
        finishAndRemoveTask();
    }
}
