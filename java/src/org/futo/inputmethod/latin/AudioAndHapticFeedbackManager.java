/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.futo.inputmethod.latin;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.media.AudioManager;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.View;

import org.futo.inputmethod.latin.common.Constants;
import org.futo.inputmethod.latin.settings.SettingsValues;

/**
 * This class gathers audio feedback and haptic feedback functions.
 *
 * It offers a consistent and simple interface that allows LatinIME to forget about the
 * complexity of settings and the like.
 */
public final class AudioAndHapticFeedbackManager {
    private AudioManager mAudioManager;
    private Vibrator mVibrator;

    private SettingsValues mSettingsValues;
    private boolean mSoundOn;

    private static final AudioAndHapticFeedbackManager sInstance =
            new AudioAndHapticFeedbackManager();

    public static AudioAndHapticFeedbackManager getInstance() {
        return sInstance;
    }

    private AudioAndHapticFeedbackManager() {
        // Intentional empty constructor for singleton.
    }

    public static void init(final Context context) {
        sInstance.initInternal(context);
    }

    private void initInternal(final Context context) {
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    public void performHapticAndAudioFeedback(final int code,
            final View viewToPerformHapticFeedbackOn) {
        performHapticFeedback(viewToPerformHapticFeedbackOn, false);
        performAudioFeedback(code);
    }

    /**
     * The light tick played for each cursor step while the spacebar is used as a trackpad. Only
     * when key-press vibration is on.
     */
    public void performTickFeedback(final View view) {
        if (mSettingsValues == null || !mSettingsValues.mVibrateOn || view == null) return;
        view.performHapticFeedback(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
                ? HapticFeedbackConstants.TEXT_HANDLE_MOVE
                : HapticFeedbackConstants.CLOCK_TICK);
    }

    public boolean hasVibrator() {
        return mVibrator != null && mVibrator.hasVibrator();
    }

    public void vibrate(final long milliseconds) {
        if (mVibrator == null) {
            return;
        }
        mVibrator.vibrate(milliseconds);
    }

    private boolean reevaluateIfSoundIsOn() {
        if (mSettingsValues == null || !mSettingsValues.mSoundOn || mAudioManager == null) {
            return false;
        }
        return mAudioManager.getRingerMode() == AudioManager.RINGER_MODE_NORMAL;
    }

    public void performAudioFeedback(final int code) {
        // if mAudioManager is null, we can't play a sound anyway, so return
        if (mAudioManager == null) {
            return;
        }
        if (!mSoundOn) {
            return;
        }
        final int sound;
        switch (code) {
        case Constants.CODE_DELETE:
            sound = AudioManager.FX_KEYPRESS_DELETE;
            break;
        case Constants.CODE_ENTER:
            sound = AudioManager.FX_KEYPRESS_RETURN;
            break;
        case Constants.CODE_SPACE:
            sound = AudioManager.FX_KEYPRESS_SPACEBAR;
            break;
        default:
            sound = AudioManager.FX_KEYPRESS_STANDARD;
            break;
        }
        mAudioManager.playSoundEffect(sound, mSettingsValues.mKeypressSoundVolume);
    }

    public void performHapticFeedback(final View viewToPerformHapticFeedbackOn, final boolean repeatKey) {
        if (!mSettingsValues.mVibrateOn) {
            return;
        }
        if (vibrateWithCustomStrength(repeatKey)) {
            return;
        }
        if (mSettingsValues.mKeypressVibrationDuration >= 0) {
            vibrate(mSettingsValues.mKeypressVibrationDuration / (repeatKey ? 2 : 1));
            return;
        }
        // Go ahead with the system default
        if (viewToPerformHapticFeedbackOn != null) {
            viewToPerformHapticFeedbackOn.performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    // Duration used with a custom strength when the duration setting is left at "Default".
    private static final int CUSTOM_STRENGTH_DEFAULT_DURATION_MS = 10;

    /**
     * Vibrates at the strength chosen in settings, if one is set and the vibration motor can vary
     * its strength (API 26+). Returns false when the caller should use the normal vibration.
     */
    private boolean vibrateWithCustomStrength(final boolean repeatKey) {
        final int strength = mSettingsValues.mKeypressVibrationStrength;
        if (strength <= 0 || mVibrator == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || !mVibrator.hasAmplitudeControl()) {
            return false;
        }

        int milliseconds = mSettingsValues.mKeypressVibrationDuration >= 0
                ? mSettingsValues.mKeypressVibrationDuration
                : CUSTOM_STRENGTH_DEFAULT_DURATION_MS;
        if (repeatKey) milliseconds /= 2;
        if (milliseconds <= 0) {
            // A duration of 0 ms means no vibration.
            return true;
        }

        final int amplitude = Math.max(1, Math.min(255, Math.round(strength * 255f / 100f)));
        mVibrator.vibrate(VibrationEffect.createOneShot(milliseconds, amplitude));
        return true;
    }

    public void onSettingsChanged(final SettingsValues settingsValues) {
        mSettingsValues = settingsValues;
        mSoundOn = reevaluateIfSoundIsOn();
    }

    public void onRingerModeChanged() {
        mSoundOn = reevaluateIfSoundIsOn();
    }
}
