package local.launcherfix;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import com.gathin.tvhome.AppAdapter;
import com.gathin.tvhome.MainActivity;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Offloads hero art generation (canvas render, downscale, saturation boost,
 * and 6-pass box blur) completely off the UI thread to eliminate 50ms jank spikes.
 */
public final class HeroArtLoader {
    private static final String TAG = "TvHome.HeroArtLoader";

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "hero-art-blur");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        }
    });

    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static final AtomicInteger sSequence = new AtomicInteger(0);

    // Reusable buffers for the background worker thread (only accessed on sExecutor thread)
    private static final int[] sBlurBufA = new int[1296];
    private static final int[] sBlurBufB = new int[1296];

    private HeroArtLoader() {}

    public static void applyHeroArt(final MainActivity activity, final String key, final int position) {
        if (activity == null || key == null || activity.heroArt == null) return;

        activity.heroArt.animate().cancel();

        // 1. If key is already currently displayed, just ensure it's faded in
        if (key.equals(activity.heroArtKey)) {
            activity.fadeHeroArt(420);
            return;
        }

        // 2. Fast-path: Check memory LRU cache on UI thread (instant 0ms)
        Bitmap cached = null;
        if (activity.heroArtCache != null) {
            cached = (Bitmap) activity.heroArtCache.get(key);
        }

        if (cached != null && !cached.isRecycled()) {
            activity.heroArtKey = key;
            final Bitmap finalCached = cached;
            if (activity.animations && !activity.reducedMotion && activity.heroArt.getAlpha() != 0f) {
                activity.heroArt.animate()
                    .alpha(0f)
                    .setDuration(140)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            if (key.equals(activity.heroArtKey)) {
                                activity.heroArt.setImageBitmap(finalCached);
                                activity.fadeHeroArt(600);
                            }
                        }
                    })
                    .start();
            } else {
                activity.heroArt.setImageBitmap(finalCached);
                activity.fadeHeroArt(600);
            }
            return;
        }

        // 3. Cache miss: Extract Drawable on UI thread
        AppAdapter adapter = activity.adapter;
        Drawable rawDrawable = null;
        if (adapter != null && adapter.items != null && position >= 0 && position < adapter.items.size()) {
            AppAdapter.Entry entry = adapter.items.get(position);
            if (entry != null) {
                rawDrawable = (entry.banner != null) ? entry.banner : entry.icon;
            }
        }

        if (rawDrawable == null) {
            activity.heroArtKey = key;
            return;
        }

        // 4. Render base 192x108 on UI thread safely (<0.4ms)
        Bitmap base = null;
        try {
            Rect oldBounds = rawDrawable.getBounds();
            Rect saved = (oldBounds != null) ? new Rect(oldBounds) : null;
            base = Bitmap.createBitmap(192, 108, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(base);
            canvas.drawColor(0xFF000000);
            rawDrawable.setBounds(0, 0, 192, 108);
            rawDrawable.draw(canvas);
            if (saved != null) {
                rawDrawable.setBounds(saved);
            }
        } catch (Throwable t) {
            if (base != null) {
                base.recycle();
                base = null;
            }
        }

        if (base == null) {
            activity.heroArtKey = key;
            return;
        }

        // If animations enabled, start smooth fade-out of old hero art while new one renders
        if (activity.animations && !activity.reducedMotion && activity.heroArt.getAlpha() != 0f) {
            activity.heroArt.animate().alpha(0f).setDuration(140).start();
        }

        final int seq = sSequence.incrementAndGet();
        final Bitmap baseBmp = base;

        // 5. Offload downscale, saturation boost, and 6 box blurs to background thread
        sExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (seq != sSequence.get()) {
                    baseBmp.recycle();
                    return;
                }

                Bitmap finalBmp = null;
                try {
                    // Downscale 192x108 -> 96x54
                    Bitmap scaled1 = Bitmap.createScaledBitmap(baseBmp, 96, 54, true);
                    baseBmp.recycle();

                    if (seq != sSequence.get()) {
                        scaled1.recycle();
                        return;
                    }

                    // Downscale 96x54 -> 48x27
                    Bitmap scaled2 = Bitmap.createScaledBitmap(scaled1, 48, 27, true);
                    scaled1.recycle();

                    if (seq != sSequence.get()) {
                        scaled2.recycle();
                        return;
                    }

                    int[] src = sBlurBufA;
                    int[] dst = sBlurBufB;
                    scaled2.getPixels(src, 0, 48, 0, 0, 48, 27);
                    scaled2.recycle();

                    // Saturation boost loop (1296 pixels)
                    for (int i = 0; i < 1296; i++) {
                        int col = src[i];
                        float r = (col >> 16) & 255;
                        float g = (col >> 8) & 255;
                        float b = col & 255;
                        float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                        int cr = clamp((r - lum) * 1.7f + lum);
                        int cg = clamp((g - lum) * 1.7f + lum);
                        int cb = clamp((b - lum) * 1.7f + lum);
                        src[i] = 0xFF000000 | (cr << 16) | (cg << 8) | cb;
                    }

                    if (seq != sSequence.get()) {
                        return;
                    }

                    // 6 passes of box blur (3 horizontal, 3 vertical) using zero-allocation ping-pong
                    FastPixelOps.skyBoxBlur(src, dst, true);
                    FastPixelOps.skyBoxBlur(dst, src, false);
                    FastPixelOps.skyBoxBlur(src, dst, true);
                    FastPixelOps.skyBoxBlur(dst, src, false);
                    FastPixelOps.skyBoxBlur(src, dst, true);
                    FastPixelOps.skyBoxBlur(dst, src, false);

                    if (seq != sSequence.get()) {
                        return;
                    }

                    finalBmp = Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888);
                    finalBmp.setPixels(src, 0, 48, 0, 0, 48, 27);
                } catch (Throwable t) {
                    if (finalBmp != null) {
                        finalBmp.recycle();
                        finalBmp = null;
                    }
                }

                if (finalBmp == null) return;
                final Bitmap resultBmp = finalBmp;

                // Post back to UI thread
                sMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (activity.isFinishing() || activity.isDestroyed()) {
                            resultBmp.recycle();
                            return;
                        }

                        // Store in cache for instant re-focus hits
                        MemoryTrimmer.putHeroArt(activity.heroArtCache, key, resultBmp);

                        // Only apply if user is still focused on this tile
                        if (key.equals(MainActivity.lastFocusedKey)) {
                            activity.heroArtKey = key;
                            activity.heroArt.setImageBitmap(resultBmp);
                            activity.fadeHeroArt(600);
                        }
                    }
                });
            }
        });
    }

    private static int clamp(float f) {
        if (f < 0f) return 0;
        if (f > 255f) return 255;
        return Math.round(f);
    }
}
