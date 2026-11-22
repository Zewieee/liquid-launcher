package com.gathin.tvhome;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;
import android.widget.TextView;

public final class ClearGlass {
    public static ColorStateList textColors() { return null; }
    public static void shadow(TextView textView, float dp) {}
    public static Drawable panel(float radius, float dp) { return null; }

    public static final class Plate extends Drawable {
        public Plate(float radius, float dp, float level) {}
        public void setLevel(float level) {}
        @Override public void draw(Canvas canvas) {}
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter colorFilter) {}
        @Override public int getOpacity() { return -3; }
    }
}
