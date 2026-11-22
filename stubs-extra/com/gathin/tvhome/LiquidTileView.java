package com.gathin.tvhome;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;
public final class LiquidTileView extends FrameLayout {
    public LiquidGlassRoot.Surface surface;
    public LiquidTileView(Context context, AttributeSet attrs) {super(context,attrs);}
    @Override public void onDetachedFromWindow() {super.onDetachedFromWindow();}
}
