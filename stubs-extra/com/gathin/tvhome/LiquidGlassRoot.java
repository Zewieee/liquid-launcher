package com.gathin.tvhome;
import android.content.Context;
import android.util.AttributeSet;
import android.animation.ValueAnimator;
import android.widget.FrameLayout;
import java.util.concurrent.ExecutorService;
import java.util.ArrayList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.View;
public final class LiquidGlassRoot extends FrameLayout {
    public boolean motionEnabled;
    public boolean busy;
    public long refreshUntil;
    public float pulse;
    public ValueAnimator pulseAnimator;
    public ExecutorService worker;
    public long lastBackdrop;
    public long lastCapture;
    public Bitmap scene;
    public Bitmap content;
    public Canvas sceneCanvas;
    public Canvas contentCanvas;
    public ArrayList<Surface> surfaces;
    public LiquidGlassRoot(Context context, AttributeSet attrs) {super(context,attrs);}
    public static LiquidGlassRoot find(View view) {return null;}
    public static void backdropChanged(View view) {}
    public static void configureMotion(android.view.View view, Config config) {}
    public void lens(Canvas canvas, View owner, Surface surface, RectF bounds,float radius,boolean content) {}
    public static void releaseSurface(Surface surface) {}
    public void releaseAllSurfaces() {}
    public void trimMemory(int level) {}
    public static Job createJob(Surface surface) {return null;}
    public void deliverJob(Job job) {}
    public static final class Surface {
        public int epoch;
        public View owner;
        public LiquidGlassRoot root;
        public Bitmap front,spare;
        public boolean dirty;
        public boolean content;
        public float press;
    }
    public static final class Job {public Surface surface; public int epoch; public Bitmap output;}
}
