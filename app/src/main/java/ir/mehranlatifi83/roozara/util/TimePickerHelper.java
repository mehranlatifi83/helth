package ir.mehranlatifi83.roozara.util;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import ir.mehranlatifi83.roozara.R;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Time picker with two switchable modes:
 *  - Scroll mode (default): two NumberPicker spinners, no keyboard
 *  - Clock mode:            MaterialTimePicker circular clock face
 */
public class TimePickerHelper {

    public interface OnTimeSetListener {
        void onTimeSet(int hour, int minute);
    }

    /** Show the picker starting in scroll (NumberPicker) mode. */
    public static void show(FragmentManager fm, Context ctx, String title,
                            int initialHour, int initialMin,
                            OnTimeSetListener listener) {
        showScrollMode(fm, ctx, title, initialHour, initialMin, listener);
    }

    // ── Scroll mode (NumberPicker) ────────────────────────────────────────────

    private static void showScrollMode(FragmentManager fm, Context ctx, String title,
                                       int initialHour, int initialMin,
                                       OnTimeSetListener listener) {
        int pad = dp(ctx, 24);

        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout pickersRow = new LinearLayout(ctx);
        pickersRow.setOrientation(LinearLayout.HORIZONTAL);
        // Time notation has a fixed semantic order regardless of the app locale:
        // hour on the left, minute on the right. Keep only this numeric row LTR;
        // dialog titles, buttons, and translated labels retain the locale direction.
        pickersRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        pickersRow.setGravity(Gravity.CENTER);
        pickersRow.setPadding(pad, dp(ctx, 8), pad, 0);

        NumberPicker npHour = makeNumberPicker(ctx, 0, 23, initialHour);
        NumberPicker npMin  = makeNumberPicker(ctx, 0, 59, initialMin);

        LinearLayout hourColumn = labeledColumn(ctx, npHour, R.string.picker_hour_label);
        hourColumn.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView colon = new TextView(ctx);
        colon.setText(":");
        colon.setTextSize(28);
        colon.setPadding(dp(ctx, 12), 0, dp(ctx, 12), dp(ctx, 22));
        colon.setGravity(Gravity.CENTER);

        LinearLayout minuteColumn = labeledColumn(ctx, npMin, R.string.picker_minute_label);
        minuteColumn.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        pickersRow.addView(hourColumn);
        pickersRow.addView(colon);
        pickersRow.addView(minuteColumn);

        TextView toggleBtn = new TextView(ctx);
        toggleBtn.setText(R.string.picker_clock_mode);
        toggleBtn.setTextSize(13);
        toggleBtn.setGravity(Gravity.CENTER);
        toggleBtn.setPadding(0, dp(ctx, 4), 0, dp(ctx, 12));

        content.addView(pickersRow);
        content.addView(toggleBtn);

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(content)
                .setPositiveButton(android.R.string.ok, (d, w) ->
                        listener.onTimeSet(commit(npHour), commit(npMin)))
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        toggleBtn.setOnClickListener(v -> {
            dialog.dismiss();
            showClockMode(fm, ctx, title, commit(npHour), commit(npMin), listener);
        });

        dialog.show();
    }

    // ── Clock mode (MaterialTimePicker) ───────────────────────────────────────

    private static void showClockMode(FragmentManager fm, Context ctx, String title,
                                      int initialHour, int initialMin,
                                      OnTimeSetListener listener) {
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                .setHour(initialHour)
                .setMinute(initialMin)
                .setTitleText(title)
                .build();

        picker.addOnPositiveButtonClickListener(v ->
                listener.onTimeSet(picker.getHour(), picker.getMinute()));

        picker.show(fm, "clock_picker");

        // After the dialog view inflates, intercept the internal mode-toggle button
        // so tapping it returns to our scroll mode instead of the library's keyboard mode.
        // The 300ms delay is necessary because the fragment view is not yet attached at
        // show() time — the view tree only exists after the first layout pass.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            android.view.View root = picker.getView();
            if (root == null) return;
            // Library resources are merged into the app package at build time, so try
            // the app package first, then the material library package as fallback.
            int btnId = root.getResources().getIdentifier(
                    "material_timepicker_mode_button", "id", ctx.getPackageName());
            if (btnId == 0) {
                btnId = root.getResources().getIdentifier(
                        "material_timepicker_mode_button", "id",
                        "com.google.android.material");
            }
            android.view.View modeBtn = (btnId != 0) ? root.findViewById(btnId) : null;
            if (modeBtn == null) modeBtn = findModeButton(root);
            if (modeBtn == null) return;
            modeBtn.setOnClickListener(v -> {
                int h = picker.getHour();
                int m = picker.getMinute();
                picker.dismiss();
                showScrollMode(fm, ctx, title, h, m, listener);
            });
        }, 300);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Read a picker's value, committing anything still being typed first.
     *
     * NumberPicker only commits typed text on focus loss or the IME "done" action, and
     * tapping a dialog button does not reliably take focus away from its edit field.
     * The live-sync below normally keeps the value current, but it works through
     * reflection on a private platform field, which newer Android builds may refuse —
     * and on those devices a time the user typed and could see on screen would be read
     * back as the old one and silently discarded. clearFocus() forces the commit
     * whether or not the reflection succeeded.
     */
    private static int commit(NumberPicker picker) {
        picker.clearFocus();
        return picker.getValue();
    }

    private static NumberPicker makeNumberPicker(Context ctx, int min, int max, int value) {
        NumberPicker np = new NumberPicker(ctx);
        np.setMinValue(min);
        np.setMaxValue(max);
        np.setValue(value);
        np.setWrapSelectorWheel(true);
        np.setFormatter(i -> String.format(Locale.getDefault(), "%02d", i));
        enableLiveTypedSync(np);
        np.invalidate();
        return np;
    }

    /**
     * NumberPicker's built-in tap-to-type EditText only commits a typed digit (and
     * updates the faded prev/next rows) on focus loss or the IME "done" action, so the
     * wheel looks stale until Enter is pressed. There's no public API to drive the wheel
     * without going through setValue(), and setValue() unconditionally reformats the
     * EditText via the zero-padding formatter (fighting the user's in-progress typing
     * and jumping the cursor). So: call the real setValue() to get a correctly
     * recomputed wheel, then immediately restore the EditText's text/cursor to what the
     * user actually typed (unless the value had to be clamped, in which case show the
     * clamped number instead).
     */
    private static void enableLiveTypedSync(NumberPicker picker) {
        try {
            Field inputField = NumberPicker.class.getDeclaredField("mInputText");
            inputField.setAccessible(true);
            EditText input = (EditText) inputField.get(picker);
            if (input == null) return;

            // First digit typed replaces the current value instead of appending to it.
            input.setSelectAllOnFocus(true);

            AtomicBoolean selfUpdate = new AtomicBoolean(false);
            input.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    if (selfUpdate.get()) return;
                    String raw = s.toString();
                    if (raw.isEmpty()) return;
                    try {
                        int typed = Integer.parseInt(raw);
                        int clamped = Math.max(picker.getMinValue(),
                                Math.min(picker.getMaxValue(), typed));
                        int cursor = input.getSelectionStart();

                        selfUpdate.set(true);
                        picker.setValue(clamped);
                        String display = (clamped == typed) ? raw : String.valueOf(clamped);
                        input.setText(display);
                        input.setSelection(Math.min(cursor, display.length()));
                        selfUpdate.set(false);
                    } catch (NumberFormatException ignored) {
                        selfUpdate.set(false);
                    }
                }
            });
        } catch (Exception ignored) {
            // Reflection unavailable on this OEM/Android build — typing still works,
            // it just commits on focus loss / IME "done" instead of live, as before.
        }
    }

    /** Wraps a NumberPicker with a small caption below it so it's unambiguous which
     *  column is hours and which is minutes. */
    private static LinearLayout labeledColumn(Context ctx, NumberPicker picker, int labelRes) {
        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);

        TextView label = new TextView(ctx);
        label.setText(labelRes);
        label.setTextSize(12);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0.7f);
        label.setPadding(0, dp(ctx, 4), 0, 0);

        column.addView(picker);
        column.addView(label);
        return column;
    }

    /**
     * Traverses the picker's view tree to find the mode-toggle ImageButton.
     * It is the only ImageButton / CheckableImageButton not in the dialog button bar.
     */
    private static android.view.View findModeButton(android.view.View root) {
        if (!(root instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup group = (android.view.ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            android.view.View child = group.getChildAt(i);
            if (child instanceof android.widget.ImageButton
                    || child.getClass().getSimpleName().contains("CheckableImageButton")) {
                return child;
            }
            android.view.View found = findModeButton(child);
            if (found != null) return found;
        }
        return null;
    }

    private static int dp(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }
}
