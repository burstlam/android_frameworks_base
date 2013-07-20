
package com.android.systemui.statusbar.toggles;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings;
import android.view.View;

import com.android.systemui.R;
import com.android.internal.util.aokp.SysHelpers;

public class SlimPieToggle extends StatefulToggle {

    SettingsObserver mSettingsObserver;

    @Override
    public void init(Context c, int style) {
        super.init(c, style);
        mSettingsObserver = new SettingsObserver(new Handler());
        scheduleViewUpdate();
    }

    @Override
    protected void doEnable() {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SPIE_CONTROLS, 2);
        Settings.System.putBoolean(mContext.getContentResolver(),
                Settings.System.NAV_HIDE_ENABLE, true);
    }

    @Override
    protected void doDisable() {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SPIE_CONTROLS, 0);
        Settings.System.putBoolean(mContext.getContentResolver(),
                Settings.System.NAV_HIDE_ENABLE, false);
    }

    @Override
    protected void updateView() {
        boolean enabled = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.SPIE_CONTROLS, 0) == 2;
        
        setEnabledState(enabled);
        setIcon(enabled ? R.drawable.ic_qs_pie_on : R.drawable.ic_qs_pie_off);
        setLabel(enabled ? R.string.quick_settings_slim_pie_on_label
                : R.string.quick_settings_slim_pie_off_label);
        super.updateView();
    }

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
            observe();
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.SPIE_CONTROLS), false,
                    this);
        }

        @Override
        public void onChange(boolean selfChange) {
            SysHelpers.restartSystemUI();
        }
    }

}
