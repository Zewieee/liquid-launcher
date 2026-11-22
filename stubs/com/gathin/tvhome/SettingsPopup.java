package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.widget.TextView;

public final class SettingsPopup {
    public static int[] DOCK_OPACITIES;
    public TextView[] columnChips;
    public TextView[] dockChips;
    public boolean animations;
    public boolean advanced;
    public Activity activity;
    public TextView advancedRow;
    public TextView animationsRow;
    public SharedPreferences choices;
    public Dialog dialog;
    public float dp;
    public boolean vi;
    public TextView wallpaperRow;
    public static void show(Activity activity) {}
}
