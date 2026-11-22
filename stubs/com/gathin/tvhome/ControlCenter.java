package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ControlCenter {
    public Activity activity;
    public ImageView backdrop;
    public ClearGlass.Plate backlightGlass;
    public View backlightSlider;
    public TextView backlightValue;
    public LinearLayout bottomRow;
    public boolean canSetBacklight;
    public View clockPill;
    public View header;
    public View returnTo;
    public static java.lang.ref.WeakReference shownPill;
    public static void stylePill() {}
    public Dialog dialog;
    public float dp;
    public View firstButton;
    public GradientDrawable networkDot;
    public TextView networkState;
    public boolean open;
    public LinearLayout panel;
    public View pictureModule;
    public TextView pictureValue;
    public FrameLayout screen;
    public Bitmap shot;
    public LinearLayout topRow;
    public boolean vi;
    public ClearGlass.Plate volumeGlass;
    public View volumeSlider;
    public TextView volumeValue;

    public ControlCenter(Activity activity) {}

    public void callBuild() {}
    public void callShow() {}
    public void callOpenInputs() {}
    public void callOpenNetwork() {}
    public void callOpenPicture() {}
    public void callOpenIcons() {}
    public void callOpenDock() {}
    public void callOpenWallpaper() {}
    public void callOpenSettings() {}
    public void callOpenLauncherSettings() {}
    public void callOpenTv(String cls, String action) {}
    public boolean callNudgeBacklight(int keyCode, KeyEvent event) { return false; }
    public boolean callNudgeVolume(int keyCode, KeyEvent event) { return false; }
    public void callShowBacklight(int val) {}
    public void callShowVolume() {}
    public void callReveal(boolean withShot) {}
    public void callCapture(boolean retry) {}
    public void callClose() {}
    public Typeface callFont(String name) { return null; }
    public String callNetwork() { return null; }
    public String callPictureMode() { return null; }
    public int callGlobal(String key, int defVal) { return defVal; }

    public static final class Glyph extends Drawable {
        public static final int ICON = 0;
        public static final int PICTURE = 1;
        public static final int DOCK = 2;
        public static final int NETWORK = 3;
        public static final int SUN = 4;
        public static final int GEAR = 5;
        public static final int SLIDERS = 6;
        public static final int INPUT = 7;
        public static final int WALLPAPER = 8;
        public static final int SOUND = 9;
        public Glyph(int kind, float dp, int sizeDp) {}
        @Override public void draw(Canvas canvas) {}
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter colorFilter) {}
        @Override public int getOpacity() { return -3; }
    }
}
