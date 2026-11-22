package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.view.View;
import java.io.File;

public final class WallpaperPopup {
    public Activity activity;
    public float dp;
    public View effectMenu;
    public View root;
    public boolean vi;

    public static void show(Activity activity) {}
    public static void showPrepared(Activity activity) {}
    public static Bitmap blur(Bitmap bitmap) { return bitmap; }
    private void close(Runnable runnable) {}
    public void closeFromHelper(Runnable runnable) {}
    public void choosePrepared(Entry entry) {}
    public boolean readyForChoice() {return false;}
    public void cancelPending() {}

    public static final class Entry {
        public int kind;
        public File file;
        public String label;

        public Entry(int kind, File file, String label) {
            this.kind = kind;
            this.file = file;
            this.label = label;
        }
    }
}
