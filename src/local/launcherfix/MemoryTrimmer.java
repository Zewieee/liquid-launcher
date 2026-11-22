package local.launcherfix;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import com.gathin.tvhome.LiquidGlassRoot;
import com.gathin.tvhome.MainActivity;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.HashMap;

/** Coordinates memory shedding when UI is hidden or under system memory pressure. */
public final class MemoryTrimmer {
    private static final String TAG = "TvHome.MemoryTrimmer";
    private static volatile Handler sMain;
    private static boolean trimmed;
    private static Object sWmgInstance;
    private static Method sWmgTrimMemory;
    private static boolean sWmgResolved;
    private static Runnable sDeferredGpuPurge;

    private MemoryTrimmer() {}

    private static Handler mainHandler() {
        Handler h = sMain;
        if (h == null) {
            Looper l = Looper.getMainLooper();
            if (l != null) {
                h = new Handler(l);
                sMain = h;
            }
        }
        return h;
    }

    public static LiquidGlassRoot findInTree(View view) {
        if (view == null) return null;
        if (view instanceof LiquidGlassRoot) return (LiquidGlassRoot) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                LiquidGlassRoot res = findInTree(group.getChildAt(i));
                if (res != null) return res;
            }
        }
        return null;
    }

    public static void onResume(MainActivity activity) {
        Handler mh = mainHandler();
        if (sDeferredGpuPurge != null && mh != null) {
            mh.removeCallbacks(sDeferredGpuPurge);
            sDeferredGpuPurge = null;
        }
    }

    public static void trim(MainActivity activity, int level) {
        if (activity == null) return;

        // 1. Trim ImageCache LRU
        ImageCache.trim(level);

        // 2. Trim LiquidGlassRoot bitmaps and Shamsi pixel buffers
        LiquidGlassRoot glass = null;
        if (activity.root instanceof LiquidGlassRoot) {
            glass = (LiquidGlassRoot) activity.root;
        }
        if (glass == null) {
            View content = activity.findViewById(android.R.id.content);
            glass = findInTree(content);
        }
        if (glass != null) {
            trimGlass(glass, level);
        }

        // 3. Trim ControlCenter cached backdrop/panel if parked/closed
        ControlPanelHelper.trimMemory(level);

        // 4. Trim WallpaperThumbLoader LRU cache, SoundPool inactive packs, Romaji dictionary, lyrics/theme caches, and pixel buffers
        WallpaperThumbLoader.trimMemory(level);
        CrystalSoundEngine.trimMemory(level);
        AvatarStore.trim(level);
        JapaneseRomaji.trimMemory(level);
        FastPixelOps.trimMemory(level);
        PopupOptimizer.trimMemory(level);
        MusicBackdropView.trimMemory(level);
        if (level >= 20 || level == 10 || level == 15) {
            WallpaperTheme.trimCache();
        }
        LiveLyrics.trimMemory(level);
        SpotifyMetadata.trimMemory(level);

        if (!activity.started && (level >= 20 || level == 10 || level == 15)) {
            if (activity.heroArtCache != null) {
                activity.heroArtCache.clear();
            }
            activity.heroArtKey = null;
            flushGpuCaches(level >= 60 ? 80 : 40);
            if (level < 60) {
                scheduleDeferredGpuPurge(activity);
            }
        } else if (activity.started && level >= 60) {
            // Active Clean-RAM request while Home is visible: free unlocked scratch/textures without destroying active surface.
            flushGpuCaches(40);
        }

        trimmed = true;
    }

    private static void scheduleDeferredGpuPurge(MainActivity activity) {
        Handler mh = mainHandler();
        if (mh == null) return;
        if (sDeferredGpuPurge != null) {
            mh.removeCallbacks(sDeferredGpuPurge);
        }
        final WeakReference<MainActivity> ref = new WeakReference<>(activity);
        Runnable task = new Runnable() {
            @Override
            public void run() {
                if (sDeferredGpuPurge == this) sDeferredGpuPurge = null;
                MainActivity act = ref.get();
                if (act == null || act.started || act.isDestroyed() || act.isFinishing()) return;
                Window window = act.getWindow();
                View decor = window == null ? null : window.getDecorView();
                if (decor != null && decor.getWindowVisibility() != View.VISIBLE) {
                    // Window exit transition has completed and Home is GONE: safe to purge Skia's
                    // ~12-15 MB purgeable Image Texture cache without any exit flash.
                    flushGpuCaches(80);
                }
            }
        };
        sDeferredGpuPurge = task;
        mh.postDelayed(task, 1500L);
    }

    public static void onStop(final MainActivity activity) {
        if (activity == null) return;
        try {
            trim(activity, 20);
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void putHeroArt(HashMap cache, Object key, Object bitmap) {
        if (cache == null || key == null || bitmap == null) return;
        if (cache.size() >= 10) {
            cache.clear();
        }
        cache.put(key, bitmap);
    }

    public static void flushGpuCaches() {
        flushGpuCaches(20);
    }

    public static void flushGpuCaches(int trimLevel) {
        try {
            if (!sWmgResolved) {
                sWmgResolved = true;
                Class<?> wmgClass = Class.forName("android.view.WindowManagerGlobal");
                Method getInstance = wmgClass.getDeclaredMethod("getInstance");
                getInstance.setAccessible(true);
                sWmgInstance = getInstance.invoke(null);
                sWmgTrimMemory = wmgClass.getDeclaredMethod("trimMemory", int.class);
                sWmgTrimMemory.setAccessible(true);
            }
            if (sWmgInstance != null && sWmgTrimMemory != null) {
                sWmgTrimMemory.invoke(sWmgInstance, trimLevel);
            }
        } catch (Throwable ignored) {}
    }

    public static void trimGlass(LiquidGlassRoot glass, int level) {
        if (glass == null) return;
        if (level >= 20 || level == 10 || level == 15) {
            glass.trimMemory(level);
            FastPixelOps.trimMemory(level);
        }
    }

    public static boolean wasTrimmed() {
        return trimmed;
    }

    public static void reset() {
        trimmed = false;
    }
}
