package com.fanok.audiobooks.fragment;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.fanok.audiobooks.R;
import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;

/**
 * A whole number picked on a Material slider in the row itself, shown by the title. Touch drags the slider; a remote's
 * left and right keys on the row move it a step. The value is stored (and its change listener asked) once the finger
 * lifts or a moment after the last key, not at every step. The range comes from the seek bar preference's attributes:
 * {@code app:min}, {@code android:max} and {@code app:seekBarIncrement}.
 */
public class SliderPreference extends Preference {

    /** A remote's keys are applied this long after the last one. */
    private static final long KEY_APPLY_MS = 600;

    private final int min;
    private final int max;
    private final int step;
    private int value;
    /** What the slider shows; differs from {@link #value} until {@link #commit}. */
    private int shown;
    @Nullable
    private LabelFormatter formatter;
    @Nullable
    private TextView valueView;
    private boolean tracking;
    private final Runnable commit = this::commit;

    public SliderPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_slider);
        TypedArray a = context.obtainStyledAttributes(attrs, androidx.preference.R.styleable.SeekBarPreference);
        min = a.getInt(androidx.preference.R.styleable.SeekBarPreference_min, 0);
        max = Math.max(min + 1, a.getInt(androidx.preference.R.styleable.SeekBarPreference_android_max, 100));
        step = Math.max(1, a.getInt(androidx.preference.R.styleable.SeekBarPreference_seekBarIncrement, 1));
        a.recycle();
    }

    /** How the value reads by the title; the number itself without one. */
    public void setFormatter(@Nullable LabelFormatter formatter) {
        this.formatter = formatter;
        notifyChanged();
    }

    @Override
    protected Object onGetDefaultValue(@NonNull TypedArray a, int index) {
        return a.getInt(index, 0);
    }

    @Override
    protected void onSetInitialValue(@Nullable Object defaultValue) {
        value = shown = clamp(getPersistedInt(defaultValue == null ? min : (Integer) defaultValue));
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        valueView = (TextView) holder.findViewById(R.id.sliderValue);
        Slider slider = (Slider) holder.findViewById(R.id.slider);
        slider.clearOnChangeListeners();
        slider.clearOnSliderTouchListeners();
        slider.setValueFrom(min);
        slider.setValueTo(max);
        slider.setStepSize(step);
        slider.setValue(shown);
        slider.setEnabled(isEnabled());
        slider.setFocusable(false); // the slider makes itself focusable: the row takes the remote's keys
        slider.setContentDescription(getTitle());
        showValue();
        slider.addOnChangeListener((view, picked, fromUser) -> {
            shown = Math.round(picked);
            showValue();
            if (fromUser && !tracking) { // an accessibility action: stored as a remote's key is
                view.removeCallbacks(commit);
                view.postDelayed(commit, KEY_APPLY_MS);
            }
        });
        slider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider view) {
                tracking = true;
                view.removeCallbacks(commit);
            }

            @Override
            public void onStopTrackingTouch(@NonNull Slider view) {
                tracking = false;
                commit();
            }
        });
        holder.itemView.setOnKeyListener((row, code, event) -> {
            int by = code == KeyEvent.KEYCODE_DPAD_LEFT ? -step : code == KeyEvent.KEYCODE_DPAD_RIGHT ? step : 0;
            if (by == 0 || !isEnabled()) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                slider.setValue(clamp(shown + by)); // its listener shows the value
                slider.removeCallbacks(commit);
                slider.postDelayed(commit, KEY_APPLY_MS);
            }
            return true;
        });
    }

    private void showValue() {
        if (valueView != null) {
            valueView.setText(formatter == null ? String.valueOf(shown) : formatter.getFormattedValue(shown));
        }
    }

    /** {@code picked} within the range, on a step from its start: what the slider accepts. */
    private int clamp(int picked) {
        int bounded = Math.max(min, Math.min(max, picked));
        return Math.min(max, min + Math.round((bounded - min) / (float) step) * step);
    }

    private void commit() {
        if (shown != value && callChangeListener(shown)) {
            value = shown;
            persistInt(value);
        } else if (shown != value) {
            shown = value;
            notifyChanged();
        }
    }
}
