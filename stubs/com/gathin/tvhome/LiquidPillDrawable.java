package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;
import android.view.View;

public final class LiquidPillDrawable extends Drawable {
    public LiquidPillDrawable(View view) {}
    @Override public void draw(Canvas canvas) {}
    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter colorFilter) {}
    @Override public int getOpacity() { return -3; }
}
