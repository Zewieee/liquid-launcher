package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.view.View;

public final class WallPickers {
    public interface Host {
        void backdrop(Bitmap bitmap);
        void choose(int index);
        void hold(int index);
        boolean inUse(int index);
    }

    public interface Picker {
        int current();
        void setThumb(int index, Bitmap bitmap, int accentColor);
        int thumbW();
        int thumbH();
        View view();
        int viewW();
        int viewH();
    }
}
