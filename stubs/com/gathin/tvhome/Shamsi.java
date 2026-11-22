package com.gathin.tvhome;
import android.content.Context;
import android.graphics.Bitmap;
import android.util.AtomicFile;
import java.io.IOException;
import java.util.Set;
public final class Shamsi {
    public static Bitmap sourceBitmap;
    public static int sourceGeneration;
    public static int sourceW;
    public static int sourceH;
    public static int nextCache;
    public static int[] sourcePixels;
    public static int[] resultPixels;
    public static final LiquidLensKernel$Geometry[] CACHE = new LiquidLensKernel$Geometry[8];
    public static float clamp(float f, float min, float max) { return Math.max(min, Math.min(max, f)); }
    public static int clamp(float f) { return Math.max(0, Math.min(255, Math.round(f))); }
    public static int contrast(int c, int r, int g, int b) { return 0; }
    public static int mix(int a, int b, float t) { return 0; }
    public static android.graphics.drawable.Drawable transform(Context context,android.graphics.drawable.Drawable image,int color,String material){return null;}
    public static Set readStrict(AtomicFile file)throws IOException{return null;}
    public static AtomicFile store(Context context,boolean removed){return null;}
    public static java.io.File pick(java.util.List list, String str, String str2, java.util.Random random) { return null; }
}
