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
package com.android.internal.policy.impl.keyguard;

import android.animation.ObjectAnimator;
import android.app.SearchManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Message;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import static com.android.internal.util.aokp.AwesomeConstants.*;
import com.android.internal.util.aokp.AokpRibbonHelper;
import com.android.internal.util.aokp.LockScreenHelpers;
import com.android.internal.telephony.IccCardConstants.State;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.RotarySelector;
import com.android.internal.widget.SlidingTab;
import com.android.internal.widget.multiwaveview.GlowPadView;
import com.android.internal.widget.multiwaveview.GlowPadView.OnTriggerListener;
import com.android.internal.widget.multiwaveview.MultiWaveView;
import com.android.internal.widget.multiwaveview.TargetDrawable;
import com.android.internal.R;

import java.util.ArrayList;

public class KeyguardSelectorView extends LinearLayout implements KeyguardSecurityView {
    private static final boolean DEBUG = KeyguardHostView.DEBUG;
    private static final String TAG = "SecuritySelectorView";

    private final int TORCH_TIMEOUT = ViewConfiguration.getLongPressTimeout(); //longpress glowpad torch
    private final int TORCH_CHECK = 2000; //make sure torch turned off

    private static final int LOCK_STYLE_JB = 0;
    private static final int LOCK_STYLE_ICS = 1;
    private static final int LOCK_STYLE_GB = 2;
    private static final int LOCK_STYLE_ECLAIR = 3;

    private KeyguardSecurityCallback mCallback;
    private GlowPadView mGlowPadView;
    private LinearLayout mRibbon;
    private LinearLayout ribbonView;
    private ObjectAnimator mAnim;
    private View mFadeView;
    private boolean mIsBouncing;
    private LockPatternUtils mLockPatternUtils;
    private SecurityMessageDisplay mSecurityMessageDisplay;
    private Drawable mBouncerFrame;
    private Resources res;

    private int mGlowTorch;
    private boolean mGlowTorchOn;
    private boolean mGlowPadLock;
    private boolean mBoolLongPress;
    private int mTarget;
    private boolean mLongPress = false;
    private boolean mUnlockBroadcasted = false;
    private boolean mUsesCustomTargets;
    private int mUnlockPos;
    private String[] targetActivities = new String[8];
    private String[] longActivities = new String[8];
    private String[] customIcons = new String[8];
    private UnlockReceiver mUnlockReceiver;
    private IntentFilter filter;
    private boolean mReceiverRegistered = false;

    private boolean mCameraDisabled;
    private boolean mSearchDisabled;
    private boolean mSilentMode;
    private AudioManager mAudioManager;
    private final boolean mHasVibrator;

    private TextView mCarrier;
    private MultiWaveView mMultiWaveView;
    private SlidingTab mSlidingTabView;
    private RotarySelector mRotarySelectorView;

    // Get the style from settings
    private int mLockscreenStyle = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.LOCKSCREEN_STYLE, LOCK_STYLE_JB);

    private class H extends Handler {
        public void handleMessage(Message m) {
            switch (m.what) {
            }
        }
    }
    private H mHandler = new H();

    private void launchAction(String action) {
        AwesomeConstant AwesomeEnum = fromString(action);
        switch (AwesomeEnum) {
        case ACTION_UNLOCK:
            mCallback.userActivity(0);
            mCallback.dismiss(false);
            break;
        case ACTION_ASSIST:
            mCallback.userActivity(0);
            mCallback.dismiss(false);
            Intent assistIntent =
                ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                .getAssistIntent(mContext, UserHandle.USER_CURRENT);
                if (assistIntent != null) {
                    mActivityLauncher.launchActivity(assistIntent, false, true, null, null);
                } else {
                    Log.w(TAG, "Failed to get intent for assist activity");
                }
                break;
        case ACTION_CAMERA:
            mCallback.userActivity(0);
            mCallback.dismiss(false);
            mActivityLauncher.launchCamera(null, null);
            break;
        case ACTION_APP:
            mCallback.userActivity(0);
            mCallback.dismiss(false);
            Intent i = new Intent();
            i.setAction("com.android.systemui.aokp.LAUNCH_ACTION");
            i.putExtra("action", action);
            mContext.sendBroadcastAsUser(i, UserHandle.ALL);
            break;
        }
    }

    OnTriggerListener mOnTriggerListener = new OnTriggerListener() {

       final Runnable SetLongPress = new Runnable () {
            public void run() {
                if (!mLongPress) {
                    vibrate();
                    mLongPress = true;
                }
            }
        };

        public void onTrigger(View v, int target) {
            if (mReceiverRegistered) {
                mContext.unregisterReceiver(mUnlockReceiver);
                mReceiverRegistered = false;
            }
            if ((!mUsesCustomTargets) || (mTargetCounter() == 0 && mUnlockCounter() < 2)) {
                mCallback.userActivity(0);
                mCallback.dismiss(false);
            } else {
                if (!mLongPress) {
                    mHandler.removeCallbacks(SetLongPress);
                    launchAction(targetActivities[target]);
                }
            }
        }

        public void onReleased(View v, int handle) {
            fireTorch();
            if (!mIsBouncing) {
                doTransition(mFadeView, 1.0f);
            }
            if (!mGlowPadLock && mLongPress) {
                mGlowPadLock = true;
                if (mReceiverRegistered) {
                    mContext.unregisterReceiver(mUnlockReceiver);
                    mReceiverRegistered = false;
                }
                launchAction(longActivities[mTarget]);
            }
        }

        public void onGrabbed(View v, int handle) {
            mCallback.userActivity(0);
            doTransition(mFadeView, 0.0f);
            if (mGlowTorch == 1) {
                mHandler.removeCallbacks(checkTorch);
                mHandler.postDelayed(startTorch, TORCH_TIMEOUT);
            }
        }

        public void onGrabbedStateChange(View v, int handle) {
            mHandler.removeCallbacks(SetLongPress);
            mLongPress = false;
        }

        public void onTargetChange(View v, int target) {
            if (target == -1) {
                mHandler.removeCallbacks(SetLongPress);
                mLongPress = false;
            } else {
                fireTorch();
                if (mBoolLongPress && !TextUtils.isEmpty(longActivities[target]) && !longActivities[target].equals(AwesomeConstant.ACTION_NULL.value())) {
                    mTarget = target;
                    mHandler.postDelayed(SetLongPress, ViewConfiguration.getLongPressTimeout());
                }
            }
        }

        public void onFinishFinalAnimation() {

        }

    };

    SlidingTab.OnTriggerListener mTabTriggerListener = new SlidingTab.OnTriggerListener() {

        @Override
        public void onTrigger(View v, int whichHandle) {
            if (whichHandle == SlidingTab.OnTriggerListener.LEFT_HANDLE) {
                mCallback.userActivity(0);
                mCallback.dismiss(false);
            } else if (whichHandle == SlidingTab.OnTriggerListener.RIGHT_HANDLE) {
                toggleRingMode();
                updateResources();
                mCallback.userActivity(0);
            }
        }

        @Override
        public void onGrabbedStateChange(View v, int grabbedState) {
            if (grabbedState != SlidingTab.OnTriggerListener.NO_HANDLE) {
                mCallback.userActivity(0);
            }
        }
    };

    RotarySelector.OnDialTriggerListener mDialTriggerListener = new
            RotarySelector.OnDialTriggerListener() {

        @Override
        public void onDialTrigger(View v, int whichHandle) {
            if (whichHandle == RotarySelector.OnDialTriggerListener.LEFT_HANDLE) {
                mCallback.userActivity(0);
                mCallback.dismiss(false);
            } else if (whichHandle == RotarySelector.OnDialTriggerListener.RIGHT_HANDLE) {
                toggleRingMode();
                updateResources();
                mCallback.userActivity(0);
            }
        }

        @Override
        public void onGrabbedStateChange(View v, int grabbedState) { }
    };

    MultiWaveView.OnTriggerListener mWaveTriggerListener = new
            MultiWaveView.OnTriggerListener() {

        @Override
        public void onGrabbedStateChange(View v, int grabbedState) {
            // Don't poke the wake lock when returning to a state where the handle is
            // not grabbed since that can happen when the system (instead of the user)
            // cancels the grab.
            if (grabbedState == MultiWaveView.OnTriggerListener.CENTER_HANDLE) {
                mCallback.userActivity(0);
            }
        }

        @Override
        public void onTrigger(View v, int target) {
            if (target == 0 || target == 1) { // 0 = unlock/portrait, 1 = unlock/landscape
                mCallback.dismiss(false);
            } else if (target == 2 || target == 3) { // 2 = alt/portrait, 3 = alt/landscape
                if (!mCameraDisabled) {
                    // Start the Camera
                    mActivityLauncher.launchCamera(null, null);
                    mCallback.dismiss(false);
                } else {
                    toggleRingMode();
                    updateResources();
                    mCallback.userActivity(0);
                }
            }
        }

        @Override
        public void onGrabbed(View v, int handle) { }

        @Override
        public void onReleased(View v, int handle) {
            mMultiWaveView.ping();
        }

        @Override
        public void onFinishFinalAnimation() { }
    };

    KeyguardUpdateMonitorCallback mInfoCallback = new KeyguardUpdateMonitorCallback() {

        @Override
        public void onDevicePolicyManagerStateChanged() {
            updateTargets();
        }

        @Override
        public void onSimStateChanged(State simState) {
            updateTargets();
        }
    };

    private final KeyguardActivityLauncher mActivityLauncher = new KeyguardActivityLauncher() {

        @Override
        KeyguardSecurityCallback getCallback() {
            return mCallback;
        }

        @Override
        LockPatternUtils getLockPatternUtils() {
            return mLockPatternUtils;
        }

        @Override
        Context getContext() {
            return mContext;
        }};

    public KeyguardSelectorView(Context context) {
        this(context, null);
    }

    public KeyguardSelectorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mLockPatternUtils = new LockPatternUtils(getContext());
        Vibrator vibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        mHasVibrator = vibrator == null ? false : vibrator.hasVibrator();
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        res = getResources();
        ContentResolver cr = mContext.getContentResolver();
        mGlowPadView = (GlowPadView) findViewById(R.id.glow_pad_view);
        mGlowPadView.setOnTriggerListener(mOnTriggerListener);
        mMultiWaveView = (MultiWaveView) findViewById(R.id.multiwave_view);
        mMultiWaveView.setOnTriggerListener(mWaveTriggerListener);
        mSlidingTabView = (SlidingTab) findViewById(R.id.sliding_tab_view);
        mSlidingTabView.setOnTriggerListener(mTabTriggerListener);
        mRotarySelectorView = (RotarySelector) findViewById(R.id.rotary_selector_view);
        mRotarySelectorView.setOnDialTriggerListener(mDialTriggerListener);
        ribbonView = (LinearLayout) findViewById(R.id.keyguard_ribbon_and_battery);
        ribbonView.bringToFront();
        mRibbon = (LinearLayout) ribbonView.findViewById(R.id.ribbon);
        mRibbon.removeAllViews();
        mRibbon.addView(AokpRibbonHelper.getRibbon(mContext,
            Settings.System.getArrayList(cr,
                Settings.System.RIBBON_TARGETS_SHORT[AokpRibbonHelper.LOCKSCREEN]),
            Settings.System.getArrayList(cr,
                Settings.System.RIBBON_TARGETS_LONG[AokpRibbonHelper.LOCKSCREEN]),
            Settings.System.getArrayList(cr,
                Settings.System.RIBBON_TARGETS_ICONS[AokpRibbonHelper.LOCKSCREEN]),
            Settings.System.getBoolean(cr,
                Settings.System.ENABLE_RIBBON_TEXT[AokpRibbonHelper.LOCKSCREEN], true),
            Settings.System.getInt(cr,
                Settings.System.RIBBON_TEXT_COLOR[AokpRibbonHelper.LOCKSCREEN], -1),
            Settings.System.getInt(cr,
                Settings.System.RIBBON_ICON_SIZE[AokpRibbonHelper.LOCKSCREEN], 0),
            Settings.System.getInt(cr,
                Settings.System.RIBBON_ICON_SPACE[AokpRibbonHelper.LOCKSCREEN], 5),
            Settings.System.getBoolean(cr,
                Settings.System.RIBBON_ICON_VIBRATE[AokpRibbonHelper.LOCKSCREEN], true),
            Settings.System.getBoolean(cr,
                Settings.System.RIBBON_ICON_COLORIZE[AokpRibbonHelper.LOCKSCREEN], true), 0));
        updateTargets();

        switch (mLockscreenStyle) {
            case LOCK_STYLE_JB:
                mGlowPadView.setVisibility(View.VISIBLE);
                mMultiWaveView.setVisibility(View.GONE);
                mSlidingTabView.setVisibility(View.GONE);
                mRotarySelectorView.setVisibility(View.GONE);
                break;
            case LOCK_STYLE_ICS:
                mGlowPadView.setVisibility(View.GONE);
                mMultiWaveView.setVisibility(View.VISIBLE);
                mSlidingTabView.setVisibility(View.GONE);
                mRotarySelectorView.setVisibility(View.GONE);
                break;
            case LOCK_STYLE_GB:
                mGlowPadView.setVisibility(View.GONE);
                mMultiWaveView.setVisibility(View.GONE);
                mSlidingTabView.setVisibility(View.VISIBLE);
                mRotarySelectorView.setVisibility(View.GONE);

                mSlidingTabView.setHoldAfterTrigger(true, false);
                mSlidingTabView.setLeftHintText(R.string.lockscreen_unlock_label);
                mSlidingTabView.setLeftTabResources(
                        R.drawable.ic_jog_dial_unlock,
                        R.drawable.jog_tab_target_green,
                        R.drawable.jog_tab_bar_left_unlock,
                        R.drawable.jog_tab_left_unlock);
                break;
            case LOCK_STYLE_ECLAIR:
                mGlowPadView.setVisibility(View.GONE);
                mMultiWaveView.setVisibility(View.GONE);
                mSlidingTabView.setVisibility(View.GONE);
                mRotarySelectorView.setVisibility(View.VISIBLE);

                mRotarySelectorView.setLeftHandleResource(R.drawable.ic_jog_dial_unlock);
                break;
            default:
                Log.e(TAG, "Error: Unknown lockscreen style.");
        }
        mSilentMode = isSilentMode();

        mGlowTorch = Settings.System.getInt(cr,
                Settings.System.LOCKSCREEN_GLOW_TORCH, 0);
        mGlowTorchOn = false;

        mSecurityMessageDisplay = new KeyguardMessageArea.Helper(this);
        View bouncerFrameView = findViewById(R.id.keyguard_selector_view_frame);
        mBouncerFrame = bouncerFrameView.getBackground();
        mUnlockBroadcasted = false;
        filter = new IntentFilter();
        filter.addAction(UnlockReceiver.ACTION_UNLOCK_RECEIVER);
        if (mUnlockReceiver == null) {
            mUnlockReceiver = new UnlockReceiver();
        }
        mContext.registerReceiver(mUnlockReceiver, filter);
        mReceiverRegistered = true;
    }

    public void setCarrierArea(View carrierArea) {
        mFadeView = carrierArea;
        mCarrier = (TextView) mFadeView.findViewById(R.id.carrier_text);
    }

    public boolean isTargetPresent(int resId) {
        return mGlowPadView.getTargetPosition(resId) != -1;
    }

    private boolean isSilentMode() {
        return mAudioManager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL;
    }

    private void toggleRingMode() {
        // toggle silent mode
        mSilentMode = !mSilentMode;

        String message = mSilentMode ? getContext().getString(
                R.string.global_action_silent_mode_on_status) : getContext().getString(
                R.string.global_action_silent_mode_off_status);

        final int toastIcon = mSilentMode ? R.drawable.ic_lock_ringer_off
                : R.drawable.ic_lock_ringer_on;

        final int toastColor = mSilentMode ? getContext().getResources().getColor(
                R.color.keyguard_text_color_soundoff) : getContext().getResources().getColor(
                R.color.keyguard_text_color_soundon);
        toastMessage(mCarrier, message, toastColor, toastIcon);

        if (mSilentMode) {
            mAudioManager.setRingerMode(mHasVibrator
                ? AudioManager.RINGER_MODE_VIBRATE
                : AudioManager.RINGER_MODE_SILENT);
        } else {
            mAudioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        }
    }

    @Override
    public void showUsabilityHint() {
        mGlowPadView.ping();
        mMultiWaveView.ping();
    }

    public boolean isScreenPortrait() {
        return res.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    private void fireTorch() {
        mHandler.removeCallbacks(startTorch);
        if (mGlowTorch == 1 && mGlowTorchOn) {
            mGlowTorchOn = false;
            vibrate();
            torchOff();
            mHandler.postDelayed(checkTorch, TORCH_CHECK);
        }
    }

    private void torchOff() {
        Intent intent = new Intent("com.aokp.torch.INTENT_TORCH_OFF");
        intent.setComponent(ComponentName.unflattenFromString
                ("com.aokp.Torch/.TorchReceiver"));
        intent.setAction("com.aokp.torch.INTENT_TORCH_OFF");
        intent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        mContext.sendBroadcast(intent);
    }

    private void vibrate() {
        if (Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 1, UserHandle.USER_CURRENT) != 0) {
            android.os.Vibrator vib = (android.os.Vibrator)mContext.getSystemService(
                    Context.VIBRATOR_SERVICE);
            if (vib != null) {
                vib.vibrate(25);
            }
        }
    }

    final Runnable checkTorch = new Runnable () {
        public void run() {
            boolean torchActive = Settings.System.getBoolean(mContext.getContentResolver(),
                    Settings.System.TORCH_STATE, false);
            if (torchActive) {
                Log.w(TAG, "Second Torch Temination Required");
                torchOff();
            }
        }
    };

    final Runnable startTorch = new Runnable () {
        public void run() {
            boolean torchActive = Settings.System.getBoolean(mContext.getContentResolver(),
                    Settings.System.TORCH_STATE, false);
            if (!torchActive && !mGlowTorchOn) {
                mGlowTorchOn = true;
                vibrate();
                Intent intent = new Intent("com.aokp.torch.INTENT_TORCH_ON");
                intent.setComponent(ComponentName.unflattenFromString
                        ("com.aokp.Torch/.TorchReceiver"));
                intent.setAction("com.aokp.torch.INTENT_TORCH_ON");
                mContext.sendBroadcast(intent);
            }
        }
    };

    private void updateTargets() {
        int currentUserHandle = mLockPatternUtils.getCurrentUser();
        DevicePolicyManager dpm = mLockPatternUtils.getDevicePolicyManager();
        int disabledFeatures = dpm.getKeyguardDisabledFeatures(null, currentUserHandle);
        boolean secureCameraDisabled = mLockPatternUtils.isSecure()
                && (disabledFeatures & DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA) != 0;
        boolean cameraDisabledByAdmin = dpm.getCameraDisabled(null, currentUserHandle)
                || secureCameraDisabled;
        final KeyguardUpdateMonitor monitor = KeyguardUpdateMonitor.getInstance(getContext());
        boolean disabledBySimState = monitor.isSimLocked();
        boolean cameraTargetPresent =
            isTargetPresent(com.android.internal.R.drawable.ic_lockscreen_camera);
        boolean searchTargetPresent =
            isTargetPresent(com.android.internal.R.drawable.ic_action_assist_generic);

        if (cameraDisabledByAdmin) {
            Log.v(TAG, "Camera disabled by Device Policy");
        } else if (disabledBySimState) {
            Log.v(TAG, "Camera disabled by Sim State");
        }
        boolean currentUserSetup = 0 != Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE,
                0 /*default */,
                currentUserHandle);
        boolean searchActionAvailable =
                ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                .getAssistIntent(mContext, UserHandle.USER_CURRENT) != null;
        mCameraDisabled = cameraDisabledByAdmin || disabledBySimState || !cameraTargetPresent
                || !currentUserSetup;
        mSearchDisabled = disabledBySimState || !searchActionAvailable || !searchTargetPresent
                || !currentUserSetup;

        mLongPress = false;
        mGlowPadLock = false;
        mUsesCustomTargets = mUnlockCounter() != 0;
        ArrayList<TargetDrawable> storedDraw = new ArrayList<TargetDrawable>();

        for (int i = 0; i < 8; i++) {
            targetActivities[i] = Settings.System.getString(
                    mContext.getContentResolver(), Settings.System.LOCKSCREEN_TARGETS_SHORT[i]);
            longActivities[i] = Settings.System.getString(
                    mContext.getContentResolver(), Settings.System.LOCKSCREEN_TARGETS_LONG[i]);
            customIcons[i] = Settings.System.getString(
                    mContext.getContentResolver(), Settings.System.LOCKSCREEN_TARGETS_ICON[i]);
        }

        mBoolLongPress = Settings.System.getBoolean(
              mContext.getContentResolver(), Settings.System.LOCKSCREEN_TARGETS_LONGPRESS, false);

       if (!TextUtils.isEmpty(targetActivities[5]) || !TextUtils.isEmpty(targetActivities[6]) || !TextUtils.isEmpty(targetActivities[7])) {
           Resources res = getResources();
           LinearLayout glowPadContainer = (LinearLayout) findViewById(R.id.keyguard_glow_pad_container);
           if (glowPadContainer != null && isScreenPortrait()) {
               FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                   FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
               int pxBottom = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, res.getDisplayMetrics());
               params.setMargins(0, 0, 0, -pxBottom);
               glowPadContainer.setLayoutParams(params);
           }
       }

        // no targets? add just an unlock.
        if (!mUsesCustomTargets) {
            storedDraw.add(LockScreenHelpers.getTargetDrawable(mContext, AwesomeConstant.ACTION_UNLOCK.value()));
        } else if (mTargetCounter() == 0 && mUnlockCounter() < 2) {
            float offset = 0.0f;
            switch (mUnlockPos) {
            case 0:
                offset = 0.0f;
                break;
            case 1:
                offset = -45.0f;
                break;
            case 2:
                offset = -90.0f;
                break;
            case 3:
                offset = -135.0f;
                break;
            case 4:
                offset = 180.0f;
                break;
            case 5:
                offset = 135.0f;
                break;
            case 6:
                offset = 90.0f;
                break;
            case 7:
                offset = 45.0f;
                break;
            }
            mGlowPadView.setOffset(offset);
            storedDraw.add(LockScreenHelpers.getTargetDrawable(mContext, AwesomeConstant.ACTION_UNLOCK.value()));
        } else {
            mGlowPadView.setMagneticTargets(false);
            // Add The Target actions and Icons
            for (int i = 0; i < 8 ; i++) {
                if (!TextUtils.isEmpty(customIcons[i])) {
                    storedDraw.add(LockScreenHelpers.getCustomDrawable(mContext, customIcons[i]));
                } else {
                    storedDraw.add(LockScreenHelpers.getTargetDrawable(mContext, targetActivities[i]));
                }
            }
        }
        mGlowPadView.setTargetResources(storedDraw);
        updateResources();
    }

    private int mUnlockCounter() {
        int counter = 0;
        for (int i = 0; i < 8 ; i++) {
            if (!TextUtils.isEmpty(targetActivities[i])) {
                if (targetActivities[i].equals(AwesomeConstant.ACTION_UNLOCK.value())) {
                    mUnlockPos = i;
                    counter += 1;
                }
            }
        }
        return counter;
    }

    private int mTargetCounter() {
        int counter = 0;
        for (int i = 0; i < 8 ; i++) {
            if (!TextUtils.isEmpty(targetActivities[i])) {
                if (targetActivities[i].equals(AwesomeConstant.ACTION_UNLOCK.value()) ||
                    targetActivities[i].equals(AwesomeConstant.ACTION_NULL.value())) {
                // I just couldn't take the negative logic....
                } else {
                    counter += 1;
                }
            }
        }
        return counter;
    }

    public void updateResources() {
        if (mLockscreenStyle == LOCK_STYLE_GB) {
            boolean vibe = mSilentMode
                    && (mAudioManager.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE);

            mSlidingTabView.setRightTabResources(
                    mSilentMode ? ( vibe ? R.drawable.ic_jog_dial_vibrate_on
                                         : R.drawable.ic_jog_dial_sound_off )
                                : R.drawable.ic_jog_dial_sound_on,
                    mSilentMode ? R.drawable.jog_tab_target_yellow
                                : R.drawable.jog_tab_target_gray,
                    mSilentMode ? R.drawable.jog_tab_bar_right_sound_on
                                : R.drawable.jog_tab_bar_right_sound_off,
                    mSilentMode ? R.drawable.jog_tab_right_sound_on
                                : R.drawable.jog_tab_right_sound_off);
        } else if (mLockscreenStyle == LOCK_STYLE_ECLAIR) {
            boolean vibe = mSilentMode
                    && (mAudioManager.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE);

            int iconId = mSilentMode ? (vibe ? R.drawable.ic_jog_dial_vibrate_on
                    : R.drawable.ic_jog_dial_sound_off) : R.drawable.ic_jog_dial_sound_on;

            mRotarySelectorView.setRightHandleResource(iconId);
        } else if (mLockscreenStyle == LOCK_STYLE_ICS) {
            int resId;
            if (mCameraDisabled) {
                // Fall back to showing ring/silence if camera is disabled by DPM...
                resId = mSilentMode ? R.array.lockscreen_targets_when_silent
                    : R.array.lockscreen_targets_when_soundon;
            } else {
                resId = R.array.lockscreen_targets_with_camera;
            }
            mMultiWaveView.setTargetResources(resId);
        } else {
        // Update the search icon with drawable from the search .apk
            Intent intent = ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
           .getAssistIntent(mContext, UserHandle.USER_CURRENT);
            if (intent != null) {
                ComponentName component = intent.getComponent();
                boolean replaced = mGlowPadView.replaceTargetDrawablesIfPresent(component,
                        ASSIST_ICON_METADATA_NAME + "_google",
                        com.android.internal.R.drawable.ic_action_assist_generic);

                if (!replaced && !mGlowPadView.replaceTargetDrawablesIfPresent(component,
                            ASSIST_ICON_METADATA_NAME,
                                com.android.internal.R.drawable.ic_action_assist_generic)) {
                    Slog.w(TAG, "Couldn't grab icon from package " + component);
                }
            }
        }

        mGlowPadView.setEnableTarget(com.android.internal.R.drawable
                .ic_lockscreen_camera, !mCameraDisabled);
        mGlowPadView.setEnableTarget(com.android.internal.R.drawable
                .ic_action_assist_generic, !mSearchDisabled);
    }

    void doTransition(View view, float to) {
        if (mAnim != null) {
            mAnim.cancel();
        }
        mAnim = ObjectAnimator.ofFloat(view, "alpha", to);
        mAnim.start();
    }

    public void setKeyguardCallback(KeyguardSecurityCallback callback) {
        mCallback = callback;
    }

    public void setLockPatternUtils(LockPatternUtils utils) {
        mLockPatternUtils = utils;
    }

    @Override
    public void reset() {
        mGlowPadView.reset(false);
        mMultiWaveView.reset(false);
        mSlidingTabView.reset(false);
    }

    @Override
    public boolean needsInput() {
        return false;
    }

    @Override
    public void onPause() {
        KeyguardUpdateMonitor.getInstance(getContext()).removeCallback(mInfoCallback);
        if (mReceiverRegistered) {
            mContext.unregisterReceiver(mUnlockReceiver);
            mReceiverRegistered = false;
        }
    }

    @Override
    public void onResume(int reason) {
        KeyguardUpdateMonitor.getInstance(getContext()).registerCallback(mInfoCallback);
        if (!mReceiverRegistered) {
            if (mUnlockReceiver == null) {
               mUnlockReceiver = new UnlockReceiver();
            }
            mContext.registerReceiver(mUnlockReceiver, filter);
            mReceiverRegistered = true;
        }
    }

    @Override
    public KeyguardSecurityCallback getCallback() {
        return mCallback;
    }

    @Override
    public void showBouncer(int duration) {
        mIsBouncing = true;
        KeyguardSecurityViewHelper.
                showBouncer(mSecurityMessageDisplay, mFadeView, mBouncerFrame, duration);
    }

    @Override
    public void hideBouncer(int duration) {
        mIsBouncing = false;
        KeyguardSecurityViewHelper.
                hideBouncer(mSecurityMessageDisplay, mFadeView, mBouncerFrame, duration);
    }

    public class UnlockReceiver extends BroadcastReceiver {
        public static final String ACTION_UNLOCK_RECEIVER = "com.android.lockscreen.ACTION_UNLOCK_RECEIVER";
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(ACTION_UNLOCK_RECEIVER)) {
                if (!mUnlockBroadcasted) {
                    mUnlockBroadcasted = true;
                    mCallback.userActivity(0);
                    mCallback.dismiss(false);
                }
            }
            if (mReceiverRegistered) {
                mContext.unregisterReceiver(mUnlockReceiver);
                mReceiverRegistered = false;
            }
        }
    }

    /**
* Displays a message in a text view and then restores the previous text.
* @param textView The text view.
* @param text The text.
* @param color The color to apply to the text, or 0 if the existing color should be used.
* @param iconResourceId The left hand icon.
*/
    private void toastMessage(final TextView textView, final String text, final int color, final int iconResourceId) {
        if (mPendingR1 != null) {
            textView.removeCallbacks(mPendingR1);
            mPendingR1 = null;
        }
        if (mPendingR2 != null) {
            mPendingR2.run(); // fire immediately, restoring non-toasted appearance
            textView.removeCallbacks(mPendingR2);
            mPendingR2 = null;
        }

        final String oldText = textView.getText().toString();
        final ColorStateList oldColors = textView.getTextColors();

        mPendingR1 = new Runnable() {
            public void run() {
                textView.setText(text);
                if (color != 0) {
                    textView.setTextColor(color);
                }
                textView.setCompoundDrawablesWithIntrinsicBounds(0, iconResourceId, 0, 0);
            }
        };

        textView.postDelayed(mPendingR1, 0);
        mPendingR2 = new Runnable() {
            public void run() {
                textView.setText(oldText);
                textView.setTextColor(oldColors);
                textView.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
            }
        };
        textView.postDelayed(mPendingR2, 3500);
    }
    private Runnable mPendingR1;
    private Runnable mPendingR2;
}
