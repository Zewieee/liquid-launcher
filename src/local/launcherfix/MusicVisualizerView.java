package local.launcherfix;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * Zero-permission, 60fps VSync-driven Liquid Glass music visualizer.
 *
 * Supports two harmonised visual profiles:
 * - MODE_DOCK: compact 4-bar backlit capsule waveform for DockMusicPill
 * - MODE_STAGE: borderless dual-layer silk aura ribbon + 28 symmetric floating glass capsules for MiniPlayer
 *
 * Uses asymmetric acoustic VU ballistics (fast velvet rise, silky liquid decay),
 * multi-harmonic incommensurate waves, smooth play/pause settle-to-rest, and
 * zero per-frame allocations during steady playback.
 */
final class MusicVisualizerView extends View {
    static final int MODE_DOCK = 0;
    static final int MODE_STAGE = 1;
    static final int DOCK_BARS = 4;
    static final int STAGE_BARS = 28;
    static final int DEFAULT_ACCENT = 0xffcde4ff;

    static final class Engine {
        final int mode;
        final int count;
        final float[] levels;
        final float[] targets;
        final float[] smoothed;
        final float[] rest;
        final float[] staticPose;
        final float[] window;
        final float[] bandFreqA;
        final float[] bandFreqB;
        final float[] bandPhaseA;
        final float[] bandPhaseB;

        float activity;
        double phaseSec;
        float seedOffset;
        float curR, curG, curB;
        float targetR, targetG, targetB;
        boolean colorInitialized;
        boolean playing;

        Engine(int mode) {
            this.mode = mode == MODE_STAGE ? MODE_STAGE : MODE_DOCK;
            this.count = this.mode == MODE_STAGE ? STAGE_BARS : DOCK_BARS;
            this.levels = new float[count];
            this.targets = new float[count];
            this.smoothed = new float[count];
            this.rest = new float[count];
            this.staticPose = new float[count];
            this.window = new float[count];
            this.bandFreqA = new float[count];
            this.bandFreqB = new float[count];
            this.bandPhaseA = new float[count];
            this.bandPhaseB = new float[count];

            if (this.mode == MODE_DOCK) {
                float[] r = {0.22f, 0.34f, 0.27f, 0.19f};
                float[] s = {0.50f, 0.88f, 0.66f, 0.44f};
                float[] w = {0.82f, 1.00f, 0.93f, 0.78f};
                float[] fa = {2.42f, 3.27f, 3.91f, 4.68f};
                float[] fb = {4.19f, 5.73f, 6.84f, 8.07f};
                float[] pa = {0.25f, 1.82f, 3.45f, 5.10f};
                float[] pb = {1.15f, 2.94f, 4.63f, 0.72f};
                for (int i = 0; i < count; i++) {
                    rest[i] = r[i];
                    staticPose[i] = s[i];
                    window[i] = w[i];
                    bandFreqA[i] = fa[i];
                    bandFreqB[i] = fb[i];
                    bandPhaseA[i] = pa[i];
                    bandPhaseB[i] = pb[i];
                    levels[i] = r[i];
                }
            } else {
                for (int i = 0; i < count; i++) {
                    double u = (i + 0.5) / count;
                    double bell = Math.pow(Math.max(0.0, Math.sin(Math.PI * u)), 0.74);
                    float win = (float) (0.20 + 0.80 * bell);
                    window[i] = win;
                    rest[i] = 0.09f + 0.085f * win;
                    double shape = 0.54 + 0.46 * Math.sin(u * 8.6 + 0.55) * Math.cos(u * 3.4 - 0.35);
                    staticPose[i] = clamp01((float) (0.16 + 0.64 * win * shape));
                    bandFreqA[i] = (float) (2.15 + 2.65 * u);
                    bandFreqB[i] = (float) (3.90 + 4.20 * (1.0 - u));
                    bandPhaseA[i] = (float) (i * 0.6180339887 * Math.PI);
                    bandPhaseB[i] = (float) (i * 1.13 + 0.7);
                    levels[i] = rest[i];
                }
            }
            setAccentColor(DEFAULT_ACCENT, true);
        }

        static int luminousAccent(int argb) {
            float[] hsv = new float[3];
            Color.colorToHSV(argb | 0xff000000, hsv);
            if (hsv[1] < 0.07f) {
                return DEFAULT_ACCENT;
            }
            float h = WallpaperTheme.calibrateHue(hsv[0]);
            float s = Math.max(0.22f, Math.min(0.46f, hsv[1] * 1.48f));
            float v = Math.max(0.95f, hsv[2]);
            return Color.HSVToColor(255, new float[]{h, s, v});
        }

        void setAccentColor(int argb, boolean immediate) {
            int clean = luminousAccent(argb);
            targetR = (clean >> 16) & 255;
            targetG = (clean >> 8) & 255;
            targetB = clean & 255;
            if (immediate || !colorInitialized) {
                curR = targetR;
                curG = targetG;
                curB = targetB;
                colorInitialized = true;
            }
        }

        void setTrackSeed(String seed) {
            if (seed == null || seed.isEmpty()) {
                seedOffset = 0f;
                return;
            }
            int hash = seed.hashCode() & 0x7fffffff;
            seedOffset = (hash % 1000) * 0.06283185f;
        }

        void snapState(boolean nextPlaying) {
            playing = nextPlaying;
            activity = nextPlaying ? 1f : 0f;
            curR = targetR;
            curG = targetG;
            curB = targetB;
            float[] pose = nextPlaying ? staticPose : rest;
            System.arraycopy(pose, 0, levels, 0, count);
        }

        boolean isColorMorphing() {
            return Math.abs(targetR - curR) >= 0.6f
                || Math.abs(targetG - curG) >= 0.6f
                || Math.abs(targetB - curB) >= 0.6f;
        }

        boolean isSettling() {
            if (activity > 0.004f || isColorMorphing()) return true;
            for (int i = 0; i < count; i++) {
                if (Math.abs(levels[i] - rest[i]) > 0.003f) return true;
            }
            return false;
        }

        /**
         * Advances the simulation by dtMs (clamped to [1ms, 50ms] for jank resilience).
         * Returns true if another animation frame is required.
         */
        boolean step(long dtMs, boolean nextPlaying) {
            long clamped = dtMs < 1L ? 1L : (dtMs > 50L ? 50L : dtMs);
            float dt = clamped * 0.001f;
            playing = nextPlaying;

            float actTarget = nextPlaying ? 1f : 0f;
            float actTau = nextPlaying ? 0.140f : 0.185f;
            float actAlpha = 1f - (float) Math.exp(-dt / actTau);
            activity += (actTarget - activity) * actAlpha;
            if (activity < 0f) activity = 0f;
            else if (activity > 1f) activity = 1f;

            float colAlpha = 1f - (float) Math.exp(-dt / 0.125f);
            curR += (targetR - curR) * colAlpha;
            curG += (targetG - curG) * colAlpha;
            curB += (targetB - curB) * colAlpha;
            if (!isColorMorphing()) {
                curR = targetR;
                curG = targetG;
                curB = targetB;
            }

            // Motion gently decelerates as playback pauses instead of abruptly halting.
            phaseSec += dt * (0.24 + 0.76 * activity);
            double t = phaseSec;

            // Slow musical phrasing + gentle acoustic groove envelope (never repetitive).
            double phrase = 0.77 + 0.23 * Math.sin(t * 0.83 + seedOffset) * Math.cos(t * 0.37 - seedOffset * 0.45);
            double beat = 0.5 + 0.5 * Math.sin(t * 2.48 + 0.42 * Math.sin(t * 0.71 + seedOffset));
            double groove = 0.80 + 0.20 * beat * beat;

            if (mode == MODE_DOCK) {
                for (int i = 0; i < count; i++) {
                    double w1 = Math.sin(t * bandFreqA[i] + bandPhaseA[i] + seedOffset);
                    double w2 = Math.sin(t * bandFreqB[i] + bandPhaseB[i] - seedOffset * 0.65);
                    double w3 = Math.cos(t * (bandFreqA[i] * 0.53) + i * 1.61803 + seedOffset * 0.35);
                    double raw = 0.50 + 0.27 * w1 + 0.15 * w2 + 0.08 * w3;
                    targets[i] = clamp01((float) (0.20 + 0.78 * raw * window[i] * phrase * groove));
                }
            } else {
                double focalCenter = 0.48 + 0.16 * Math.sin(t * 0.63 + seedOffset);
                for (int i = 0; i < count; i++) {
                    double u = (i + 0.5) / count;
                    double flowA = 0.5 + 0.5 * Math.sin(u * 4.6 - t * 2.30 + seedOffset);
                    double flowB = 0.5 + 0.5 * Math.sin(u * 7.3 + t * 1.88 - seedOffset * 0.55);
                    double shimmer = 0.5 + 0.5 * Math.sin(u * 12.8 - t * 4.05 + bandPhaseA[i] * 0.32);
                    double dist = (u - focalCenter) / 0.25;
                    double focal = Math.exp(-dist * dist);
                    double raw = (0.34 * flowA + 0.25 * flowB + 0.16 * shimmer + 0.25 * focal) * phrase * groove;
                    targets[i] = clamp01((float) (0.14 + 0.84 * raw * window[i]));
                }
                // 3-tap spatial coupling so adjacent capsules glide together like liquid.
                for (int i = 0; i < count; i++) {
                    float prev = targets[i > 0 ? i - 1 : i];
                    float cur = targets[i];
                    float next = targets[i + 1 < count ? i + 1 : i];
                    smoothed[i] = prev * 0.18f + cur * 0.64f + next * 0.18f;
                }
            }

            float smoothAct = activity * activity * (3f - 2f * activity);
            float riseAlpha = 1f - (float) Math.exp(-dt / 0.055f);
            float fallAlpha = 1f - (float) Math.exp(-dt / 0.135f);
            float[] src = mode == MODE_STAGE ? smoothed : targets;
            float maxRestDiff = 0f;
            for (int i = 0; i < count; i++) {
                float goal = rest[i] + (src[i] - rest[i]) * smoothAct;
                float diff = goal - levels[i];
                levels[i] = clamp01(levels[i] + diff * (diff > 0f ? riseAlpha : fallAlpha));
                float restDiff = Math.abs(levels[i] - rest[i]);
                if (restDiff > maxRestDiff) maxRestDiff = restDiff;
            }

            if (!nextPlaying && activity < 0.004f && maxRestDiff < 0.003f && !isColorMorphing()) {
                activity = 0f;
                curR = targetR;
                curG = targetG;
                curB = targetB;
                System.arraycopy(rest, 0, levels, 0, count);
                return false;
            }
            return true;
        }

        int currentAccentRgb() {
            int r = Math.max(0, Math.min(255, Math.round(curR)));
            int g = Math.max(0, Math.min(255, Math.round(curG)));
            int b = Math.max(0, Math.min(255, Math.round(curB)));
            return (r << 16) | (g << 8) | b;
        }

        float crestWaveAt(int index) {
            double u = (index + 0.5) / count;
            double env = Math.sin(Math.PI * u);
            double wave = Math.sin(u * 6.2 - phaseSec * 2.15 + seedOffset) * 0.62
                + Math.cos(u * 10.4 + phaseSec * 1.65 - seedOffset * 0.5) * 0.38;
            return (float) (wave * env);
        }

        private static float clamp01(float v) {
            return v < 0f ? 0f : (v > 1f ? 1f : v);
        }
    }

    static final class Renderer {
        final float dp;
        final Engine engine;
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Paint ribbonPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Paint crestPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path ribbonPath = new Path();
        private final Path crestPath = new Path();

        LinearGradient barShader;
        LinearGradient ribbonShader;
        int shaderRebuilds;
        private int cachedW = -1, cachedH = -1, cachedShaderRgb = -1;

        Renderer(float dp, Engine engine) {
            this.dp = dp;
            this.engine = engine;
            crestPaint.setStyle(Paint.Style.STROKE);
            crestPaint.setStrokeCap(Paint.Cap.ROUND);
            crestPaint.setStrokeJoin(Paint.Join.ROUND);
            crestPaint.setStrokeWidth(Math.max(1f, 1.15f * dp));
        }

        private static int blendWithWhite(int rgb, float whiteWeight) {
            int r = (rgb >> 16) & 255;
            int g = (rgb >> 8) & 255;
            int b = rgb & 255;
            int nr = Math.min(255, Math.round(r + (255 - r) * whiteWeight));
            int ng = Math.min(255, Math.round(g + (255 - g) * whiteWeight));
            int nb = Math.min(255, Math.round(b + (255 - b) * whiteWeight));
            return (nr << 16) | (ng << 8) | nb;
        }

        private void ensureShaders(int w, int h, int accentRgb) {
            // Quantise RGB to 4-step buckets so steady 60fps playback allocates zero Shaders.
            int quantRgb = accentRgb & 0x00fcfcfc;
            if (w == cachedW && h == cachedH && quantRgb == cachedShaderRgb && barShader != null) {
                return;
            }
            cachedW = w;
            cachedH = h;
            cachedShaderRgb = quantRgb;
            shaderRebuilds++;
            int softWhite = blendWithWhite(quantRgb, 0.56f);
            if (engine.mode == MODE_DOCK) {
                barShader = new LinearGradient(0f, 0f, 0f, h,
                    new int[]{0xffffffff, 0xf2000000 | softWhite, 0xd4000000 | quantRgb},
                    new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP);
                ribbonShader = null;
            } else {
                barShader = new LinearGradient(0f, 0f, 0f, h,
                    new int[]{
                        0xcc000000 | quantRgb,
                        0xee000000 | softWhite,
                        0xffffffff,
                        0xee000000 | softWhite,
                        0xcc000000 | quantRgb
                    },
                    new float[]{0f, 0.28f, 0.50f, 0.72f, 1f}, Shader.TileMode.CLAMP);
                ribbonShader = new LinearGradient(0f, 0f, 0f, h,
                    new int[]{
                        0x00000000 | quantRgb,
                        0x26000000 | quantRgb,
                        0x3a000000 | softWhite,
                        0x26000000 | quantRgb,
                        0x00000000 | quantRgb
                    },
                    new float[]{0f, 0.24f, 0.50f, 0.76f, 1f}, Shader.TileMode.CLAMP);
            }
        }

        void draw(Canvas canvas, int w, int h) {
            if (w <= 0 || h <= 0) return;
            int accentRgb = engine.currentAccentRgb();
            ensureShaders(w, h, accentRgb);
            if (engine.mode == MODE_DOCK) {
                drawDock(canvas, w, h, accentRgb);
            } else {
                drawStage(canvas, w, h, accentRgb);
            }
        }

        private void drawDock(Canvas canvas, int w, int h, int accentRgb) {
            int n = engine.count;
            float insetX = Math.min(1.0f * dp, w * 0.08f);
            float usableW = Math.max(1f, w - insetX * 2f);
            float barW = Math.max(1.70f * dp, Math.min(2.15f * dp, usableW * 0.165f));
            float totalBars = barW * n;
            float gap = Math.max(1.0f * dp, (usableW - totalBars) / Math.max(1, n - 1));
            float span = totalBars + gap * (n - 1);
            float startX = Math.max(0f, (w - span) * 0.5f);
            float rad = barW * 0.5f;
            float act = engine.activity;
            float maxH = Math.max(barW, h - 1.4f * dp);
            float bottom = h - 0.4f * dp;

            barPaint.setShader(barShader);
            for (int i = 0; i < n; i++) {
                float lvl = engine.levels[i];
                float bh = Math.max(barW, maxH * lvl);
                float left = startX + i * (barW + gap);
                float top = bottom - bh;

                if (act > 0.04f) {
                    float pad = 0.85f * dp;
                    int haloA = Math.min(255, Math.round(act * (22f + 46f * lvl)));
                    haloPaint.setColor((haloA << 24) | accentRgb);
                    rect.set(
                        Math.max(0f, left - pad),
                        Math.max(0f, top - pad),
                        Math.min(w, left + barW + pad),
                        Math.min(h, bottom + pad * 0.35f)
                    );
                    canvas.drawRoundRect(rect, rad + pad, rad + pad, haloPaint);
                }

                int barA = Math.min(255, Math.round(138f + act * (84f + 33f * lvl)));
                barPaint.setAlpha(barA);
                rect.set(left, top, left + barW, bottom);
                canvas.drawRoundRect(rect, rad, rad, barPaint);
            }
        }

        private void drawStage(Canvas canvas, int w, int h, int accentRgb) {
            int n = engine.count;
            float midY = h * 0.5f;
            float maxHalfH = Math.max(2f * dp, midY - 2.4f * dp);
            float act = engine.activity;
            float padX = 2.0f * dp;
            float usableW = Math.max(1f, w - padX * 2f);
            float pitch = usableW / n;

            // 1. Soft silk aura ribbon behind the capsules (tapered seamlessly at left & right edges).
            ribbonPath.rewind();
            ribbonPath.moveTo(0f, midY);
            float prevX = 0f, prevTop = midY;
            for (int i = 0; i < n; i++) {
                float cx = padX + (i + 0.5f) * pitch;
                float amp = maxHalfH * (0.20f + 0.72f * engine.levels[i]) * (0.32f + 0.68f * act);
                float topY = midY - amp;
                ribbonPath.quadTo(prevX, prevTop, (prevX + cx) * 0.5f, (prevTop + topY) * 0.5f);
                prevX = cx;
                prevTop = topY;
            }
            ribbonPath.quadTo(prevX, prevTop, w, midY);

            float prevBot = midY;
            prevX = w;
            for (int i = n - 1; i >= 0; i--) {
                float cx = padX + (i + 0.5f) * pitch;
                float amp = maxHalfH * (0.18f + 0.64f * engine.levels[i]) * (0.32f + 0.68f * act);
                float botY = midY + amp;
                ribbonPath.quadTo(prevX, prevBot, (prevX + cx) * 0.5f, (prevBot + botY) * 0.5f);
                prevX = cx;
                prevBot = botY;
            }
            ribbonPath.quadTo(prevX, prevBot, 0f, midY);
            ribbonPath.close();

            ribbonPaint.setShader(ribbonShader);
            ribbonPaint.setAlpha(Math.min(255, Math.round(74f + 181f * act)));
            canvas.drawPath(ribbonPath, ribbonPaint);

            // 2. Gossamer harmonic glass crest gliding softly through the ribbon.
            crestPath.rewind();
            crestPath.moveTo(0f, midY);
            float cPrevX = 0f, cPrevY = midY;
            float crestSpan = maxHalfH * (0.06f + 0.44f * act);
            for (int i = 0; i < n; i++) {
                float cx = padX + (i + 0.5f) * pitch;
                float cy = midY + engine.crestWaveAt(i) * crestSpan;
                crestPath.quadTo(cPrevX, cPrevY, (cPrevX + cx) * 0.5f, (cPrevY + cy) * 0.5f);
                cPrevX = cx;
                cPrevY = cy;
            }
            crestPath.quadTo(cPrevX, cPrevY, w, midY);
            int crestAlpha = Math.min(255, Math.round(32f + 76f * act));
            crestPaint.setColor((crestAlpha << 24) | blendWithWhite(accentRgb, 0.72f));
            canvas.drawPath(crestPath, crestPaint);

            // 3. Backlit floating crystal capsules with soft cover-tinted aura halo.
            float barW = Math.max(2.0f * dp, Math.min(3.05f * dp, pitch * 0.29f));
            float rad = barW * 0.5f;
            float haloPadX = 1.45f * dp;
            float haloPadY = 1.90f * dp;

            barPaint.setShader(barShader);
            for (int i = 0; i < n; i++) {
                float lvl = engine.levels[i];
                float cx = padX + (i + 0.5f) * pitch;
                float halfH = Math.max(rad, maxHalfH * lvl);
                float left = cx - rad;
                float right = cx + rad;
                float top = midY - halfH;
                float bottom = midY + halfH;

                if (act > 0.04f && lvl > 0.14f) {
                    int haloA = Math.min(255, Math.round(act * (14f + 44f * lvl)));
                    haloPaint.setColor((haloA << 24) | accentRgb);
                    rect.set(
                        Math.max(0f, left - haloPadX),
                        Math.max(0f, top - haloPadY),
                        Math.min(w, right + haloPadX),
                        Math.min(h, bottom + haloPadY)
                    );
                    canvas.drawRoundRect(rect, rad + haloPadX, rad + haloPadX, haloPaint);
                }

                int barA = Math.min(255, Math.round(92f + act * (74f + 89f * lvl)));
                barPaint.setAlpha(barA);
                rect.set(left, top, right, bottom);
                canvas.drawRoundRect(rect, rad, rad, barPaint);
            }
        }
    }

    final Engine engine;
    final Renderer renderer;

    private boolean desiredPlaying;
    private boolean allowAnimation;
    private boolean frameScheduled;
    private long lastFrameMs;

    private final Runnable frameTick = new Runnable() {
        @Override
        public void run() {
            frameScheduled = false;
            if (!canAnimateNow()) {
                lastFrameMs = 0L;
                engine.snapState(desiredPlaying);
                invalidate();
                return;
            }
            long now = SystemClock.uptimeMillis();
            long dt = lastFrameMs > 0L ? (now - lastFrameMs) : 16L;
            lastFrameMs = now;
            boolean keepGoing = engine.step(dt, desiredPlaying);
            invalidate();
            if (keepGoing) {
                scheduleNextFrame();
            } else {
                lastFrameMs = 0L;
            }
        }
    };

    MusicVisualizerView(Context context, float dp, int mode) {
        super(context);
        this.engine = new Engine(mode);
        this.renderer = new Renderer(dp, engine);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setTone(WallpaperTheme.Tone tone) {
        int argb = tone != null ? tone.accent : DEFAULT_ACCENT;
        boolean immediate = !canAnimateNow();
        engine.setAccentColor(argb, immediate);
        if (canAnimateNow() && engine.isColorMorphing()) {
            scheduleNextFrame();
        } else {
            invalidate();
        }
    }

    void setTrackSeed(String seed) {
        engine.setTrackSeed(seed);
    }

    /**
     * Updates playback and animation eligibility.
     * - When allowMotion is true and playing becomes false, glides smoothly to rest then stops.
     * - When allowMotion is false (reduced motion, unfocused, suspended, Ambient), stops immediately.
     */
    void setState(boolean playing, boolean allowMotion) {
        boolean prevPlaying = desiredPlaying;
        boolean prevMotion = allowAnimation;
        desiredPlaying = playing;
        allowAnimation = allowMotion;

        if (!canAnimateNow()) {
            stopFrameLoop();
            engine.snapState(playing);
            invalidate();
            return;
        }
        if (playing || engine.isSettling()) {
            scheduleNextFrame();
        } else if (prevPlaying != playing || prevMotion != allowMotion) {
            invalidate();
        }
    }

    boolean isFrameScheduled() {
        return frameScheduled;
    }

    private boolean canAnimateNow() {
        return allowAnimation && isAttachedToWindow() && getVisibility() == VISIBLE;
    }

    private void scheduleNextFrame() {
        if (frameScheduled || !canAnimateNow()) return;
        frameScheduled = true;
        if (lastFrameMs == 0L) lastFrameMs = SystemClock.uptimeMillis();
        postOnAnimation(frameTick);
    }

    private void stopFrameLoop() {
        if (frameScheduled) {
            removeCallbacks(frameTick);
            frameScheduled = false;
        }
        lastFrameMs = 0L;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (canAnimateNow() && (desiredPlaying || engine.isSettling())) {
            scheduleNextFrame();
        } else {
            engine.snapState(desiredPlaying);
            invalidate();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopFrameLoop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (!canAnimateNow()) {
            stopFrameLoop();
            engine.snapState(desiredPlaying);
        } else if (desiredPlaying || engine.isSettling()) {
            scheduleNextFrame();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        renderer.draw(canvas, getWidth(), getHeight());
    }
}
