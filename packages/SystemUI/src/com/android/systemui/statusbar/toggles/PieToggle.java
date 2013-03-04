
package com.android.systemui.statusbar.toggles;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings;
import android.view.View;

import com.android.systemui.R;

public class PieToggle extends StatefulToggle {

    @Override
    protected void init(Context c, int style) {
        super.init(c, style);
        scheduleViewUpdate();
    }

    @Override
    protected void doEnable() {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.PIE_CONTROLS, 1);
        Settings.System.putBoolean(mContext.getContentResolver(),
                Settings.System.NAV_HIDE_ENABLE, true);
        Settings.System.putBoolean(mContext.getContentResolver(),
                Settings.System.NAVIGATION_BAR_SHOW_NOW, false);
    }

    @Override
    protected void doDisable() {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.PIE_CONTROLS, 0);
        Settings.System.putBoolean(mContext.getContentResolver(),
                Settings.System.NAV_HIDE_ENABLE, false);
        Settings.System.putBoolean(mContext.getContentResolver(),
                Settings.System.NAVIGATION_BAR_SHOW_NOW, true);
    }

    @Override
    public boolean onLongClick(View v) {
        Intent intent = new Intent("android.intent.action.MAIN");
        intent.setClassName("com.aokp.romcontrol", "com.aokp.romcontrol.Settings$PieActivity");
        intent.addCategory("android.intent.category.LAUNCHER");
        startActivity(intent);
        return super.onLongClick(v);
    }

    @Override
    protected void updateView() {
        boolean enabled = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.PIE_CONTROLS, 0) == 1;
        
        setEnabledState(enabled);
        setIcon(enabled ? R.drawable.ic_qs_pie_on : R.drawable.ic_qs_pie_off);
        setLabel(enabled ? R.string.quick_settings_pie_on_label
                : R.string.quick_settings_pie_off_label);
        super.updateView();
    }

}
