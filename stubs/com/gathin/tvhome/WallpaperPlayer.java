package com.gathin.tvhome;
import android.content.Context;
import android.graphics.Bitmap;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.util.concurrent.ExecutorService;
public final class WallpaperPlayer {
    public int backgroundColor;
    public boolean active, closed, loading, enabled, transitioning;
    public Bitmap current;
    public String displayed;
    public String displayedPath;
    public long selectionGeneration;
    public android.content.SharedPreferences preferences;
    public ExecutorService decoder;
    public ImageView image;
    public FrameLayout root;
    public java.util.ArrayList files;
    public WallpaperPlayer(Context context, FrameLayout root, Config config) {}
    public void update() {}
    public void show(Bitmap bitmap) {}
    public void showCaption(boolean visible) {}
    public java.util.List playable() { return null; }
}
