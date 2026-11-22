package com.gathin.tvhome;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.graphics.Bitmap;
public class SkyView extends View {
    public Bitmap texture;
    public SkyView(Context context,AttributeSet attrs) {super(context,attrs);}
    public void render(boolean force) {}
}
