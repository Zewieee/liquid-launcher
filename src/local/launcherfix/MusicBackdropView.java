package local.launcherfix;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import java.lang.ref.WeakReference;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asynchronous off-UI-thread music backdrop compositor (fix.126):
 * - 21-stop C2 quintic smootherstep cover-to-atmosphere dissolve across 62.5% width (zero Mach-band seam)
 * - Luminous, vibrant color-matched atmosphere with rational soft-knee luma shoulder & ambient bloom
 * - 1-track revision cache for 0ms instant sharp backdrop on reopen (evicted on Home stop/trim)
 * - Silky 520ms cubic-bezier crossfade for both track changes and idle-to-cover transitions
 */
final class MusicBackdropView extends View {
    // Keep the full-resolution popup cover close to its 1024px source width; the old 960px
    // canvas was visibly upscaled on the 816dp TV card. One cached 1280x720 ARGB frame is
    // about 3.5MiB; the existing trim/crossfade cleanup still bounds retained frames.
    private static final int MAX_COMPOSED_W = 1280;
    private static final int ATMO_W = 160;
    private static final int ATMO_H = 90;
    private static final int DISSOLVE_STOPS = 21;

    private static final int[] DISSOLVE_COLORS = new int[DISSOLVE_STOPS];
    private static final float[] DISSOLVE_POSITIONS = new float[DISSOLVE_STOPS];
    private static final Interpolator SILKY_CROSSFADE = new PathInterpolator(0.33f, 0f, 0.20f, 1f);

    static {
        for (int i = 0; i < DISSOLVE_STOPS; i++) {
            float t = i / (float) (DISSOLVE_STOPS - 1);
            // Quintic smootherstep: zero 1st and 2nd derivatives at both t=0 and t=1 (eliminates Mach bands)
            float s = t * t * t * (t * (t * 6f - 15f) + 10f);
            float alphaFrac = (float) Math.pow(Math.max(0f, 1f - s), 1.15);
            int a = Math.max(0, Math.min(255, Math.round(255f * alphaFrac)));
            DISSOLVE_COLORS[i] = (a << 24) | 0x00ffffff;
            DISSOLVE_POSITIONS[i] = t;
        }
        DISSOLVE_COLORS[0] = 0xffffffff;
        DISSOLVE_COLORS[DISSOLVE_STOPS - 1] = 0x00ffffff;
    }

    private static volatile Handler sMain;
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

    private static final ThreadPoolExecutor WORKER = createWorker();
    private static ThreadPoolExecutor createWorker() {
        ThreadPoolExecutor exec = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), task -> {
                Thread t = new Thread(() -> {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    task.run();
                }, "music-backdrop");
                t.setDaemon(true);
                return t;
            });
        exec.allowCoreThreadTimeOut(true);
        return exec;
    }

    static boolean workerAllowsCoreTimeout() {
        return WORKER.allowsCoreThreadTimeOut();
    }

    // Worker-thread-only reusable scratch buffers (160x90), lazily allocated & released on trimMemory.
    private static volatile int[] sWorkerPx;
    private static volatile int[] sWorkerTmp;
    private static volatile float[] sWorkerH;
    private static volatile float[] sWorkerS;
    private static volatile float[] sWorkerMax;

    // UI-thread 1-track full-resolution composed backdrop cache (evicted on Home stop/trim)
    private static WeakReference<MusicBackdropView> sActiveView = new WeakReference<>(null);
    private static String sCachedRevision = "";
    private static Bitmap sCachedComposed;
    private static WallpaperTheme.Tone sCachedTone;

    static boolean hasCachedBackdrop(String revision) {
        return revision != null && !revision.isEmpty()
            && revision.equals(sCachedRevision)
            && sCachedComposed != null && !sCachedComposed.isRecycled();
    }

    static WallpaperTheme.Tone cachedToneFor(String revision) {
        return hasCachedBackdrop(revision) ? sCachedTone : null;
    }

    private static boolean isHeldByActiveView(Bitmap bmp) {
        if (bmp == null) return false;
        MusicBackdropView v = sActiveView.get();
        return v != null && (bmp == v.composed || bmp == v.previousComposed || bmp == v.deferredComposed);
    }

    private static void recycleIfUnused(Bitmap bmp) {
        if (bmp != null && bmp != sCachedComposed && !isHeldByActiveView(bmp) && !bmp.isRecycled()) {
            bmp.recycle();
        }
    }

    private static void putCacheMain(String revision, Bitmap built, WallpaperTheme.Tone tone) {
        if (revision == null || revision.isEmpty() || built == null || built.isRecycled()) return;
        Bitmap old = sCachedComposed;
        sCachedRevision = revision;
        sCachedComposed = built;
        sCachedTone = tone;
        if (old != null && old != built) {
            recycleIfUnused(old);
        }
    }

    private static void evictCacheMain() {
        Bitmap old = sCachedComposed;
        sCachedRevision = "";
        sCachedComposed = null;
        sCachedTone = null;
        if (old != null) {
            recycleIfUnused(old);
        }
    }

    public static void trimMemory(int level) {
        if (level >= 20 || level == 10 || level == 15) {
            sWorkerPx = null;
            sWorkerTmp = null;
            sWorkerH = null;
            sWorkerS = null;
            sWorkerMax = null;
            if (Looper.myLooper() == Looper.getMainLooper()) {
                evictCacheMain();
            } else {
                Handler mh = mainHandler();
                if (mh != null) {
                    mh.post(MusicBackdropView::evictCacheMain);
                } else {
                    sCachedRevision = "";
                    sCachedComposed = null;
                    sCachedTone = null;
                }
            }
        }
    }

    static int[] dissolveColors() {
        return DISSOLVE_COLORS.clone();
    }

    private final float dp;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint emblemPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path emblemPath = new Path();
    private final Rect srcRect = new Rect(), dstRect = new Rect();
    private final AtomicInteger requestSeq = new AtomicInteger();

    private WallpaperTheme.Tone tone;
    private String sourceRevision = "";
    private boolean sourceFullRes;
    private Bitmap sourceArt;
    private int sourceGen = -1;
    private int sourceW = -1, sourceH = -1;
    private Bitmap composed;
    private int composedGen = -1;
    private String composedRevision = "";
    private boolean composedFullRes;
    private Bitmap previousComposed;
    private Bitmap deferredComposed;
    private String deferredRevision = "";
    private boolean deferredFullRes;
    private boolean deferredAnimate;
    private int composedW, composedH;
    private int pendingTargetW, pendingTargetH, pendingGen = -1;
    private boolean animateNextChange;
    private boolean entranceActive;
    private boolean hasDrawnIdle;
    private boolean fadingFromIdle;
    private boolean released;
    private ValueAnimator crossfadeAnimator;
    private float crossfadeAlpha = 1f;

    // Cached shaders for zero-allocation drawIdleGlass
    private int cachedIdleW = -1, cachedIdleH = -1;
    private WallpaperTheme.Tone cachedIdleTone;
    private LinearGradient cachedIdleLinear;
    private RadialGradient cachedIdleRadial;
    private LinearGradient cachedIdleSheen;

    MusicBackdropView(Context context, float dp, WallpaperTheme.Tone initialTone) {
        super(context);
        this.dp = dp;
        this.tone = initialTone;
        sActiveView = new WeakReference<>(this);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setEntranceActive(boolean active) {
        if (entranceActive == active) return;
        entranceActive = active;
        if (!active && deferredComposed != null && !released) {
            Bitmap ready = deferredComposed;
            String rev = deferredRevision;
            boolean full = deferredFullRes;
            boolean anim = deferredAnimate;
            deferredComposed = null;
            deferredRevision = "";
            deferredFullRes = false;
            deferredAnimate = false;
            composedRevision = rev;
            composedFullRes = full;
            commitComposed(ready, anim);
            if (full && !rev.isEmpty()) {
                putCacheMain(rev, ready, tone);
            }
        }
    }

    void setArtwork(Bitmap art, WallpaperTheme.Tone fallbackTone) {
        setArtwork("", art, fallbackTone, ValueAnimator.areAnimatorsEnabled(), true);
    }

    void setArtwork(Bitmap art, WallpaperTheme.Tone fallbackTone, boolean allowCrossfade) {
        setArtwork("", art, fallbackTone, allowCrossfade, true);
    }

    void setArtwork(String revision, Bitmap art, WallpaperTheme.Tone fallbackTone,
                    boolean allowCrossfade, boolean fullRes) {
        String safeRev = revision == null ? "" : revision;
        if (fallbackTone != null) tone = fallbackTone;

        // Instant 0ms hit if we already cached the sharp full-resolution backdrop for this exact track revision!
        if (!safeRev.isEmpty() && hasCachedBackdrop(safeRev)) {
            Bitmap cached = sCachedComposed;
            WallpaperTheme.Tone cachedT = sCachedTone;
            if (cachedT != null) tone = cachedT;
            requestSeq.incrementAndGet();
            pendingGen = -1;
            recycleDeferred();
            sourceRevision = safeRev;
            sourceFullRes = true;
            if (art != null && !art.isRecycled()) {
                sourceArt = art;
                sourceGen = art.getGenerationId();
                sourceW = art.getWidth();
                sourceH = art.getHeight();
            }
            if (composed != cached) {
                composedRevision = safeRev;
                composedFullRes = true;
                composedGen = sourceGen;
                commitComposed(cached, allowCrossfade && (composed != null || hasDrawnIdle));
            }
            return;
        }

        Bitmap validArt = art != null && !art.isRecycled() ? art : null;
        int nextGen = validArt != null ? validArt.getGenerationId() : -1;
        int nextW = validArt != null ? validArt.getWidth() : -1;
        int nextH = validArt != null ? validArt.getHeight() : -1;
        if (validArt == sourceArt && nextGen == sourceGen && nextW == sourceW && nextH == sourceH
                && safeRev.equals(sourceRevision) && fullRes == sourceFullRes) {
            return;
        }
        // Never downgrade an already-displayed sharp backdrop to a low-res pill thumbnail of the same track
        if (!safeRev.isEmpty() && safeRev.equals(composedRevision) && composedFullRes && !fullRes
                && composed != null && !composed.isRecycled()) {
            return;
        }

        boolean hadDisplayedVisual = (composed != null && !composed.isRecycled()) || hasDrawnIdle;
        sourceRevision = safeRev;
        sourceFullRes = fullRes;
        sourceArt = validArt;
        sourceGen = nextGen;
        sourceW = nextW;
        sourceH = nextH;

        if (validArt == null) {
            requestSeq.incrementAndGet();
            pendingGen = -1;
            recycleDeferred();
            cancelCrossfade();
            if (composed != null) {
                Bitmap old = composed;
                composed = null;
                composedGen = -1;
                composedRevision = "";
                composedFullRes = false;
                composedW = composedH = 0;
                recycleIfUnused(old);
            }
            invalidate();
            return;
        }

        animateNextChange = allowCrossfade && hadDisplayedVisual && getWidth() > 0 && getHeight() > 0;
        scheduleComposeIfReady();
        if (composed == null) {
            invalidate();
        }
    }

    private void recycleDeferred() {
        if (deferredComposed != null) {
            Bitmap old = deferredComposed;
            deferredComposed = null;
            recycleIfUnused(old);
        }
        deferredRevision = "";
        deferredFullRes = false;
        deferredAnimate = false;
    }

    private void cancelCrossfade() {
        if (crossfadeAnimator != null) {
            crossfadeAnimator.cancel();
            crossfadeAnimator = null;
        }
        crossfadeAlpha = 1f;
        fadingFromIdle = false;
        if (previousComposed != null) {
            Bitmap old = previousComposed;
            previousComposed = null;
            recycleIfUnused(old);
        }
    }

    private void startCrossfade(long durationMs) {
        if (crossfadeAnimator != null) crossfadeAnimator.cancel();
        crossfadeAlpha = 0f;
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(durationMs);
        anim.setInterpolator(SILKY_CROSSFADE);
        anim.addUpdateListener(a -> {
            crossfadeAlpha = (Float) a.getAnimatedValue();
            invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (crossfadeAnimator == animation) {
                    crossfadeAnimator = null;
                    crossfadeAlpha = 1f;
                    fadingFromIdle = false;
                    if (previousComposed != null) {
                        Bitmap old = previousComposed;
                        previousComposed = null;
                        recycleIfUnused(old);
                    }
                    invalidate();
                }
            }
        });
        crossfadeAnimator = anim;
        anim.start();
    }

    void release() {
        released = true;
        entranceActive = false;
        hasDrawnIdle = false;
        requestSeq.incrementAndGet();
        pendingGen = -1;
        recycleDeferred();
        cancelCrossfade();
        sourceArt = null;
        sourceRevision = "";
        sourceFullRes = false;
        sourceGen = -1;
        sourceW = sourceH = -1;
        if (composed != null) {
            Bitmap old = composed;
            composed = null;
            recycleIfUnused(old);
        }
        composedGen = -1;
        composedRevision = "";
        composedFullRes = false;
        composedW = composedH = 0;
        if (sActiveView.get() == this) {
            sActiveView = new WeakReference<>(null);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        released = false;
        sActiveView = new WeakReference<>(this);
        scheduleComposeIfReady();
    }

    @Override
    protected void onDetachedFromWindow() {
        requestSeq.incrementAndGet();
        pendingGen = -1;
        entranceActive = false;
        hasDrawnIdle = false;
        recycleDeferred();
        cancelCrossfade();
        super.onDetachedFromWindow();
        if (composed != null) {
            Bitmap old = composed;
            composed = null;
            composedGen = -1;
            composedRevision = "";
            composedFullRes = false;
            composedW = composedH = 0;
            recycleIfUnused(old);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cancelCrossfade();
        scheduleComposeIfReady();
    }

    private void scheduleComposeIfReady() {
        if (released || sourceArt == null || sourceArt.isRecycled()) return;
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) {
            w = Math.round(816f * dp);
            h = Math.round(458f * dp);
        }
        final int targetW = Math.max(4, Math.min(w, MAX_COMPOSED_W));
        final int targetH = Math.max(4, Math.round((float) h * targetW / Math.max(1, w)));
        final Bitmap art = sourceArt;
        final int artGen = sourceGen;
        final String rev = sourceRevision;
        final boolean fullRes = sourceFullRes;
        if (composed != null && !composed.isRecycled() && composedGen == artGen
                && composedW == targetW && composedH == targetH && deferredComposed == null) {
            return;
        }
        if (pendingGen == artGen && pendingTargetW == targetW && pendingTargetH == targetH) {
            return;
        }
        pendingGen = artGen;
        pendingTargetW = targetW;
        pendingTargetH = targetH;
        final boolean shouldCrossfade = animateNextChange;
        animateNextChange = false;
        final int seq = requestSeq.incrementAndGet();

        WORKER.execute(() -> {
            if (seq != requestSeq.get() || art.isRecycled()) return;
            final Bitmap built = buildComposedOffThread(art, targetW, targetH, seq, requestSeq);
            if (built == null) return;
            Handler mh = mainHandler();
            if (mh == null) {
                if (!built.isRecycled()) built.recycle();
                return;
            }
            mh.post(() -> {
                if (released || seq != requestSeq.get()) {
                    if (!built.isRecycled()) built.recycle();
                    return;
                }
                pendingGen = -1;
                if (entranceActive && composed != null && !composed.isRecycled()) {
                    recycleDeferred();
                    deferredComposed = built;
                    deferredRevision = rev;
                    deferredFullRes = fullRes;
                    composedGen = artGen;
                    deferredAnimate = true;
                    return;
                }
                composedGen = artGen;
                composedRevision = rev;
                composedFullRes = fullRes;
                boolean fadeFromShownIdle = composed == null && hasDrawnIdle && !entranceActive;
                commitComposed(built, shouldCrossfade || fadeFromShownIdle);
                if (fullRes && !rev.isEmpty()) {
                    putCacheMain(rev, built, tone);
                }
            });
        });
    }

    private void commitComposed(Bitmap built, boolean allowCrossfade) {
        if (built == null || built.isRecycled()) return;
        int targetW = built.getWidth();
        int targetH = built.getHeight();
        boolean canAnim = allowCrossfade && ValueAnimator.areAnimatorsEnabled();
        if (canAnim && composed != null && !composed.isRecycled()
                && composed.getWidth() == targetW && composed.getHeight() == targetH) {
            if (previousComposed != null && previousComposed != composed) {
                Bitmap oldPrev = previousComposed;
                previousComposed = null;
                recycleIfUnused(oldPrev);
            }
            fadingFromIdle = false;
            previousComposed = composed;
            composed = built;
            composedW = targetW;
            composedH = targetH;
            hasDrawnIdle = false;
            startCrossfade(520L);
        } else if (canAnim && composed == null && hasDrawnIdle) {
            if (previousComposed != null) {
                Bitmap oldPrev = previousComposed;
                previousComposed = null;
                recycleIfUnused(oldPrev);
            }
            fadingFromIdle = true;
            composed = built;
            composedW = targetW;
            composedH = targetH;
            hasDrawnIdle = false;
            startCrossfade(440L);
        } else {
            cancelCrossfade();
            if (composed != null && composed != built) {
                Bitmap old = composed;
                composed = null;
                recycleIfUnused(old);
            }
            composed = built;
            composedW = targetW;
            composedH = targetH;
            hasDrawnIdle = false;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if ((sourceArt == null || sourceArt.isRecycled()) && (composed == null || composed.isRecycled())) {
            cancelCrossfade();
            hasDrawnIdle = true;
            drawIdleGlass(canvas, w, h);
            return;
        }
        if (composed != null && !composed.isRecycled()) {
            dstRect.set(0, 0, w, h);
            if (previousComposed != null && !previousComposed.isRecycled() && crossfadeAlpha < 0.999f) {
                srcRect.set(0, 0, previousComposed.getWidth(), previousComposed.getHeight());
                canvas.drawBitmap(previousComposed, srcRect, dstRect, bitmapPaint);
                srcRect.set(0, 0, composed.getWidth(), composed.getHeight());
                fadePaint.setAlpha(Math.max(0, Math.min(255, Math.round(255f * crossfadeAlpha))));
                canvas.drawBitmap(composed, srcRect, dstRect, fadePaint);
            } else if (fadingFromIdle && crossfadeAlpha < 0.999f) {
                drawIdleGlass(canvas, w, h);
                srcRect.set(0, 0, composed.getWidth(), composed.getHeight());
                fadePaint.setAlpha(Math.max(0, Math.min(255, Math.round(255f * crossfadeAlpha))));
                canvas.drawBitmap(composed, srcRect, dstRect, fadePaint);
            } else {
                srcRect.set(0, 0, composed.getWidth(), composed.getHeight());
                canvas.drawBitmap(composed, srcRect, dstRect, bitmapPaint);
            }
        } else {
            hasDrawnIdle = true;
            drawIdleGlass(canvas, w, h);
        }
    }

    static Bitmap buildComposedOffThread(Bitmap sourceArt, int w, int h, int seq, AtomicInteger activeSeq) {
        if (sourceArt == null || sourceArt.isRecycled() || w < 4 || h < 4) return null;
        Bitmap result = null;
        try {
            result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas out = new Canvas(result);
            Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            Paint dstInPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            dstInPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));

            float[] domHsv = new float[]{215f, 0.16f, 0.48f};
            Bitmap atmosphere = buildAtmosphereOffThread(sourceArt, domHsv, dstInPaint);
            if (atmosphere == null) {
                result.recycle();
                return null;
            }
            try {
                out.drawBitmap(atmosphere,
                    new Rect(0, 0, atmosphere.getWidth(), atmosphere.getHeight()),
                    new Rect(0, 0, w, h), bmpPaint);
            } finally {
                atmosphere.recycle();
            }

            if (activeSeq != null && seq != activeSeq.get()) {
                result.recycle();
                return null;
            }

            // 1. Wide 61.5% cover canvas with 21-stop C2 quintic smootherstep dissolve
            int artRight = Math.max(1, Math.round(w * 0.615f));
            Bitmap artLayer = Bitmap.createBitmap(artRight, h, Bitmap.Config.ARGB_8888);
            try {
                Canvas artCanvas = new Canvas(artLayer);
                int aw = sourceArt.getWidth(), ah = sourceArt.getHeight();
                if (aw < 1 || ah < 1 || sourceArt.isRecycled()) {
                    result.recycle();
                    return null;
                }
                float scale = Math.max((float) artRight / aw, (float) h / ah);
                float dx = (artRight - aw * scale) * 0.5f;
                float dy = (h - ah * scale) * 0.38f;
                Matrix artMatrix = new Matrix();
                artMatrix.postScale(scale, scale);
                artMatrix.postTranslate(dx, dy);
                artCanvas.drawBitmap(sourceArt, artMatrix, bmpPaint);

                float fadeStart = artRight * 0.25f;
                dstInPaint.setShader(new LinearGradient(fadeStart, 0f, artRight, 0f,
                    DISSOLVE_COLORS, DISSOLVE_POSITIONS, Shader.TileMode.CLAMP));
                artCanvas.drawRect(fadeStart, 0f, artRight, h, dstInPaint);
                dstInPaint.setShader(null);

                out.drawBitmap(artLayer, 0f, 0f, bmpPaint);
            } finally {
                artLayer.recycle();
            }

            // 2. Calm smoked-velvet right-stage veil so bright/pastel covers never glare behind white lyrics
            int darkRgb = hsvToRgbInt(domHsv[0], Math.min(0.45f, domHsv[1] * 0.95f), 0.06f) & 0x00ffffff;
            shaderPaint.setShader(new LinearGradient(w * 0.44f, 0f, w, 0f,
                new int[]{0x00000000, 0x14000000 | darkRgb, 0x2a000000 | darkRgb},
                new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
            out.drawRect(w * 0.44f, 0f, w, h, shaderPaint);

            // 3. Balanced smoked-jewel bottom scrim for clean HUD/title legibility
            shaderPaint.setShader(new LinearGradient(0f, h * 0.56f, 0f, h,
                new int[]{
                    0x00000000,
                    0x26000000 | darkRgb,
                    0x62000000 | darkRgb,
                    0xa2000000 | darkRgb
                },
                new float[]{0f, 0.36f, 0.72f, 1f}, Shader.TileMode.CLAMP));
            out.drawRect(0f, h * 0.56f, w, h, shaderPaint);

            // 4. Subtle top crystal whisper (never milky)
            shaderPaint.setShader(new LinearGradient(0f, 0f, 0f, h * 0.10f,
                new int[]{0x0affffff, 0x00ffffff},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            out.drawRect(0f, 0f, w, h * 0.10f, shaderPaint);
            shaderPaint.setShader(null);
            return result;
        } catch (RuntimeException | OutOfMemoryError error) {
            if (result != null && !result.isRecycled()) result.recycle();
            return null;
        }
    }

    private static Bitmap buildAtmosphereOffThread(Bitmap art, float[] outDomHsv, Paint dstInPaint) {
        if (art == null || art.isRecycled()) return null;
        Bitmap small = Bitmap.createBitmap(ATMO_W, ATMO_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(small);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        int aw = art.getWidth(), ah = art.getHeight();
        if (aw < 1 || ah < 1) {
            small.recycle();
            return null;
        }
        c.drawBitmap(art, new Rect(0, 0, aw, ah), new Rect(0, 0, ATMO_W, ATMO_H), p);

        Bitmap rightSample = Bitmap.createBitmap(ATMO_W, ATMO_H, Bitmap.Config.ARGB_8888);
        try {
            Canvas rc = new Canvas(rightSample);
            int srcLeft = Math.max(0, Math.round(aw * 0.38f));
            rc.drawBitmap(art, new Rect(srcLeft, 0, aw, ah), new Rect(0, 0, ATMO_W, ATMO_H), p);
            dstInPaint.setShader(new LinearGradient(ATMO_W * 0.22f, 0f, ATMO_W * 0.78f, 0f,
                new int[]{0x00ffffff, 0x38ffffff, 0x88ffffff, 0xd0ffffff, 0xf0ffffff},
                new float[]{0f, 0.25f, 0.52f, 0.78f, 1f}, Shader.TileMode.CLAMP));
            rc.drawRect(0f, 0f, ATMO_W, ATMO_H, dstInPaint);
            dstInPaint.setShader(null);
            c.drawBitmap(rightSample, 0f, 0f, p);
        } finally {
            rightSample.recycle();
        }

        boxBlurAndToneMapWorker(small, 21, 3, outDomHsv);
        return small;
    }

    /** Runs strictly on WORKER (or isolated test with local buffers): zero per-call heap allocations. */
    static void boxBlurAndToneMapWorker(Bitmap small, int radius, int passes, float[] outDomHsv) {
        int w = small.getWidth(), h = small.getHeight();
        int len = w * h;
        int[] px, tmp;
        if (len == ATMO_W * ATMO_H && Thread.currentThread().getName().equals("music-backdrop")) {
            px = sWorkerPx;
            if (px == null) { px = new int[len]; sWorkerPx = px; }
            tmp = sWorkerTmp;
            if (tmp == null) { tmp = new int[len]; sWorkerTmp = tmp; }
        } else {
            px = new int[len];
            tmp = new int[len];
        }

        small.getPixels(px, 0, w, 0, 0, w, h);
        if (w >= 3 && h >= 3 && radius >= 1) {
            int div = radius * 2 + 1;
            for (int pass = 0; pass < passes; pass++) {
                for (int y = 0; y < h; y++) {
                    int row = y * w, rSum = 0, gSum = 0, bSum = 0;
                    for (int i = -radius; i <= radius; i++) {
                        int c = px[row + Math.max(0, Math.min(w - 1, i))];
                        rSum += (c >> 16) & 255; gSum += (c >> 8) & 255; bSum += c & 255;
                    }
                    for (int x = 0; x < w; x++) {
                        tmp[row + x] = 0xff000000 | ((rSum / div) << 16) | ((gSum / div) << 8) | (bSum / div);
                        int outIdx = Math.max(0, x - radius);
                        int inIdx = Math.min(w - 1, x + radius + 1);
                        int cOut = px[row + outIdx], cIn = px[row + inIdx];
                        rSum += ((cIn >> 16) & 255) - ((cOut >> 16) & 255);
                        gSum += ((cIn >> 8) & 255) - ((cOut >> 8) & 255);
                        bSum += (cIn & 255) - (cOut & 255);
                    }
                }
                for (int x = 0; x < w; x++) {
                    int rSum = 0, gSum = 0, bSum = 0;
                    for (int i = -radius; i <= radius; i++) {
                        int c = tmp[Math.max(0, Math.min(h - 1, i)) * w + x];
                        rSum += (c >> 16) & 255; gSum += (c >> 8) & 255; bSum += c & 255;
                    }
                    for (int y = 0; y < h; y++) {
                        px[y * w + x] = 0xff000000 | ((rSum / div) << 16) | ((gSum / div) << 8) | (bSum / div);
                        int outIdx = Math.max(0, y - radius);
                        int inIdx = Math.min(h - 1, y + radius + 1);
                        int cOut = tmp[outIdx * w + x], cIn = tmp[inIdx * w + x];
                        rSum += ((cIn >> 16) & 255) - ((cOut >> 16) & 255);
                        gSum += ((cIn >> 8) & 255) - ((cOut >> 8) & 255);
                        bSum += (cIn & 255) - (cOut & 255);
                    }
                }
            }
        }

        toneMapPixelsFast(px, outDomHsv);
        small.setPixels(px, 0, w, 0, 0, w, h);
    }

    static void toneMapPixelsFast(int[] px, float[] outDomHsv) {
        int n = px.length;
        float[] hArr, sArr, mArr;
        if (n == ATMO_W * ATMO_H && Thread.currentThread().getName().equals("music-backdrop")) {
            hArr = sWorkerH; if (hArr == null || hArr.length < n) { hArr = new float[n]; sWorkerH = hArr; }
            sArr = sWorkerS; if (sArr == null || sArr.length < n) { sArr = new float[n]; sWorkerS = sArr; }
            mArr = sWorkerMax; if (mArr == null || mArr.length < n) { mArr = new float[n]; sWorkerMax = mArr; }
        } else {
            hArr = new float[n];
            sArr = new float[n];
            mArr = new float[n];
        }

        double sumCos = 0, sumSin = 0, sumS = 0, weightSum = 0;
        final float inv255 = 1f / 255f;

        // Single RGB -> HSV pass: caches (h, s, max) for all pixels while sampling stride-3 circular hue mean.
        for (int i = 0, sampleCountdown = 0; i < n; i++) {
            int c = px[i];
            float rf = ((c >> 16) & 255) * inv255;
            float gf = ((c >> 8) & 255) * inv255;
            float bf = (c & 255) * inv255;
            float max = rf > gf ? (rf > bf ? rf : bf) : (gf > bf ? gf : bf);
            float min = rf < gf ? (rf < bf ? rf : bf) : (gf < bf ? gf : bf);
            float delta = max - min;
            float s = max <= 0f ? 0f : (delta / max);
            float h;
            if (delta <= 1e-6f) {
                h = 0f;
            } else if (max == rf) {
                h = 60f * (((gf - bf) / delta) % 6f);
            } else if (max == gf) {
                h = 60f * (((bf - rf) / delta) + 2f);
            } else {
                h = 60f * (((rf - gf) / delta) + 4f);
            }
            if (h < 0f) h += 360f;
            hArr[i] = h;
            sArr[i] = s;
            mArr[i] = max;

            if (sampleCountdown == 0) {
                sampleCountdown = 2;
                if (max >= 0.04f && max <= 0.97f) {
                    double wt = 0.15 + s * 0.85;
                    double rad = h * (Math.PI / 180.0);
                    sumCos += Math.cos(rad) * wt;
                    sumSin += Math.sin(rad) * wt;
                    sumS += s * wt;
                    weightSum += wt;
                }
            } else {
                sampleCountdown--;
            }
        }

        float domH = 212f, domS = 0.14f;
        if (weightSum > 0.001) {
            domH = (float) ((Math.toDegrees(Math.atan2(sumSin, sumCos)) + 360.0) % 360.0);
            domS = (float) (sumS / weightSum);
            if (domS < 0.08f) {
                domH = WallpaperTheme.calibrateHue(domH);
            }
        }
        outDomHsv[0] = domH;
        outDomHsv[1] = domS;

        for (int i = 0; i < n; i++) {
            float h = hArr[i];
            float s = sArr[i];
            float max = mArr[i];

            if (s < 0.035f && domS > 0.05f) {
                h = domH;
                s = Math.min(0.22f, domS * 0.65f);
            } else if (s < 0.035f) {
                h = 215f;
                s = 0.08f;
            } else {
                s = Math.min(0.68f, s * 1.24f + 0.04f);
            }

            // Calm, velvety brightness curve: richer than pitch-black, never glaring on bright/pastel covers
            float v = Math.max(0.13f, Math.min(0.47f, max * 0.68f + 0.04f));

            int mapped = hsvToRgbInt(h, s, v);
            int r = (mapped >> 16) & 255, g = (mapped >> 8) & 255, b = mapped & 255;
            float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            // Smooth rational soft-knee ceiling (~96..110) keeps pastel/white covers smoked & lyrics crisp
            if (luma > 96f) {
                float excess = luma - 96f;
                float compressed = 96f + 16f * (excess / (excess + 30f));
                float scale = compressed / luma;
                r = Math.round(r * scale);
                g = Math.round(g * scale);
                b = Math.round(b * scale);
            }
            px[i] = 0xff000000 | (r << 16) | (g << 8) | b;
        }
    }

    static int hsvToRgbInt(float h, float s, float v) {
        if (s <= 0f) {
            int gray = Math.max(0, Math.min(255, Math.round(v * 255f)));
            return 0xff000000 | (gray << 16) | (gray << 8) | gray;
        }
        float hp = (h / 60f) % 6f;
        if (hp < 0f) hp += 6f;
        int sector = (int) hp;
        float f = hp - sector;
        float p = v * (1f - s);
        float q = v * (1f - s * f);
        float t = v * (1f - s * (1f - f));
        float r1, g1, b1;
        switch (sector) {
            case 0: r1 = v; g1 = t; b1 = p; break;
            case 1: r1 = q; g1 = v; b1 = p; break;
            case 2: r1 = p; g1 = v; b1 = t; break;
            case 3: r1 = p; g1 = q; b1 = v; break;
            case 4: r1 = t; g1 = p; b1 = v; break;
            default: r1 = v; g1 = p; b1 = q; break;
        }
        int r = Math.max(0, Math.min(255, Math.round(r1 * 255f)));
        int g = Math.max(0, Math.min(255, Math.round(g1 * 255f)));
        int b = Math.max(0, Math.min(255, Math.round(b1 * 255f)));
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    private void drawIdleGlass(Canvas canvas, int w, int h) {
        WallpaperTheme.Tone t = tone != null ? tone : WallpaperTheme.sample(null);
        float cx = w * 0.28f, cy = h * 0.42f, rad = Math.max(1f, Math.min(w, h) * 0.58f);
        if (cachedIdleW != w || cachedIdleH != h || cachedIdleTone != t
                || cachedIdleLinear == null || cachedIdleRadial == null || cachedIdleSheen == null) {
            cachedIdleW = w;
            cachedIdleH = h;
            cachedIdleTone = t;
            int topSmoked = hsvToRgbInt(t.hue, 0.22f, 0.32f) & 0x00ffffff;
            int midSmoked = hsvToRgbInt(t.hue, 0.30f, 0.20f) & 0x00ffffff;
            int botSmoked = hsvToRgbInt(t.hue, 0.38f, 0.11f) & 0x00ffffff;
            cachedIdleLinear = new LinearGradient(0f, 0f, w, h,
                new int[]{topSmoked | 0xd2000000, midSmoked | 0xdc000000, botSmoked | 0xe8000000},
                new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP);
            int accentRgb = t.accent & 0x00ffffff;
            cachedIdleRadial = new RadialGradient(cx, cy, rad,
                new int[]{accentRgb | 0x38000000, accentRgb | 0x16000000, 0x00000000},
                new float[]{0f, 0.56f, 1f}, Shader.TileMode.CLAMP);
            cachedIdleSheen = new LinearGradient(0f, 0f, 0f, h * 0.12f,
                new int[]{0x0cffffff, 0x00ffffff},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP);
        }
        fillPaint.setShader(cachedIdleLinear);
        canvas.drawRect(0f, 0f, w, h, fillPaint);
        fillPaint.setShader(cachedIdleRadial);
        canvas.drawRect(0f, 0f, w, h, fillPaint);
        fillPaint.setShader(cachedIdleSheen);
        canvas.drawRect(0f, 0f, w, h * 0.16f, fillPaint);
        fillPaint.setShader(null);

        float s = 82f * dp;
        canvas.save();
        canvas.translate(cx - s * 0.5f, cy - s * 0.56f);
        canvas.scale(s / 24f, s / 24f);
        emblemPaint.setColor(0x36ffffff);
        emblemPath.reset();
        emblemPath.moveTo(8f, 6f); emblemPath.lineTo(19f, 3f);
        emblemPath.lineTo(19f, 7f); emblemPath.lineTo(8f, 10f); emblemPath.close();
        canvas.drawPath(emblemPath, emblemPaint);
        canvas.drawRect(8f, 7f, 10f, 18f, emblemPaint);
        canvas.drawRect(17f, 5f, 19f, 15f, emblemPaint);
        canvas.drawOval(3f, 16f, 10f, 21f, emblemPaint);
        canvas.drawOval(12f, 13f, 19f, 18f, emblemPaint);
        canvas.restore();
    }
}
