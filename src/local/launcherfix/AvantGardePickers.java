package local.launcherfix;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import com.gathin.tvhome.WallPickers;
import java.util.Arrays;

public final class AvantGardePickers {
    public static final String KEY_SLICES = "slices";
    public static final String KEY_GEOMETRIC = "geometric";
    public static final String KEY_SHATTERED_PRISM = "shattered-prism";
    public static final String KEY_VINYL_HOLOGRAM = "vinyl-hologram";
    public static final String KEY_LIQUID_METABALL = "liquid-metaball";
    public static final String KEY_QUANTUM_HELIX = "quantum-helix";

    public static final String[] ALL_KEYS = new String[]{
        KEY_SLICES,
        KEY_GEOMETRIC,
        KEY_SHATTERED_PRISM,
        KEY_VINYL_HOLOGRAM,
        KEY_LIQUID_METABALL,
        KEY_QUANTUM_HELIX
    };

    public static final int MODE_SHATTERED_PRISM = 1;
    public static final int MODE_VINYL_HOLOGRAM = 2;
    public static final int MODE_LIQUID_METABALL = 3;
    public static final int MODE_QUANTUM_HELIX = 4;

    private AvantGardePickers() {
    }

    public static String label(String key, boolean vi) {
        if (KEY_GEOMETRIC.equals(key)) {
            return vi ? "L\u1ee5c gi\u00e1c" : "Geometric";
        }
        if (KEY_SHATTERED_PRISM.equals(key)) {
            return vi ? "K\u00ednh v\u1ea1n hoa" : "Shattered prism";
        }
        if (KEY_VINYL_HOLOGRAM.equals(key)) {
            return vi ? "\u0110\u0129a than Hologram" : "Vinyl hologram";
        }
        if (KEY_LIQUID_METABALL.equals(key)) {
            return vi ? "Gi\u1ecdt th\u1ee7y ng\u00e2n" : "Liquid metaball";
        }
        if (KEY_QUANTUM_HELIX.equals(key)) {
            return vi ? "Xo\u1eafn k\u00e9p 3D" : "Quantum helix";
        }
        return vi ? "L\u00e1t c\u1eaft" : "Slices";
    }

    public static int modeForKey(String key) {
        if (KEY_SHATTERED_PRISM.equals(key)) {
            return MODE_SHATTERED_PRISM;
        }
        if (KEY_VINYL_HOLOGRAM.equals(key)) {
            return MODE_VINYL_HOLOGRAM;
        }
        if (KEY_LIQUID_METABALL.equals(key)) {
            return MODE_LIQUID_METABALL;
        }
        if (KEY_QUANTUM_HELIX.equals(key)) {
            return MODE_QUANTUM_HELIX;
        }
        return 0;
    }

    public static WallPickers.Picker make(
            String key,
            Context context,
            WallPickers.Host host,
            int count,
            int current,
            int screenW,
            int screenH) {
        int mode = modeForKey(key);
        if (mode == 0 || context == null || host == null) {
            return null;
        }
        return new StageView(context, host, mode, count, current, screenW, screenH);
    }

    static int clampIndex(int idx, int n) {
        if (n <= 0) {
            return 0;
        }
        if (idx < 0) {
            return 0;
        }
        if (idx >= n) {
            return n - 1;
        }
        return idx;
    }

    static int wrapIndex(int slot, int n) {
        if (n <= 0) {
            return 0;
        }
        return Math.floorMod(slot, n);
    }

    static float smooth01(float x) {
        if (x <= 0.0f) {
            return 0.0f;
        }
        if (x >= 1.0f) {
            return 1.0f;
        }
        return x * x * (3.0f - 2.0f * x);
    }

    static float slotSelection(int slot, float pos) {
        float d = Math.abs(slot - pos);
        return smooth01(1.0f - d);
    }

    static int blendColor(int c1, int c2, float t) {
        if (t <= 0.0f) {
            return c1;
        }
        if (t >= 1.0f) {
            return c2;
        }
        int a1 = (c1 >>> 24) & 0xFF;
        int r1 = (c1 >>> 16) & 0xFF;
        int g1 = (c1 >>> 8) & 0xFF;
        int b1 = c1 & 0xFF;
        int a2 = (c2 >>> 24) & 0xFF;
        int r2 = (c2 >>> 16) & 0xFF;
        int g2 = (c2 >>> 8) & 0xFF;
        int b2 = c2 & 0xFF;
        int a = Math.round(a1 + (a2 - a1) * t);
        int r = Math.round(r1 + (r2 - r1) * t);
        int g = Math.round(g1 + (g2 - g1) * t);
        int b = Math.round(b1 + (b2 - b1) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    static int withAlpha(int color, int alpha) {
        int a = alpha < 0 ? 0 : (alpha > 255 ? 255 : alpha);
        return (color & 0x00FFFFFF) | (a << 24);
    }

    static boolean isOkKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    /**
     * 100% GPU-native hwui primitive renderer (`drawRoundRect`, `drawCircle`, `drawArc`, `drawLine` +
     * hardware matrix transforms). Zero `canvas.drawPath` calls and zero fullscreen `host.backdrop`
     * triggers, matching the locked-60fps responsiveness of `SliceStrip` ("Lát cắt").
     */
    public static final class StageView extends View implements WallPickers.Picker {
        private static final int MAX_VISIBLE_NODES = 11;

        final WallPickers.Host host;
        final int mode;
        final int n;
        final float dp;
        final int vw;
        final int vh;

        int target;
        int cur;
        float pos;
        float posVel;
        float spinAngle;
        float spinTarget;
        float spinVel;
        float focusValue = 1.0f;
        float focusTarget = 1.0f;
        float focusVel;
        long lastFrameMs;

        final Bitmap[] thumbs;
        final BitmapShader[] shaders;
        final int[] accents;
        final float[] fadeVal;
        final float[] fadeVel;

        final Matrix shaderMatrix = new Matrix();
        final Paint imgPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF tmpRect = new RectF();
        final RectF cardRect = new RectF();

        // Pre-allocated buffers for 3D Quantum Helix depth sorting (Mode 4)
        final int[] drawSlots = new int[MAX_VISIBLE_NODES];
        final float[] nodeDepth = new float[MAX_VISIBLE_NODES];

        boolean okDown;
        boolean okHeld;
        final Runnable holdRunnable = new Runnable() {
            @Override
            public void run() {
                if (okDown && !okHeld && n > 0) {
                    okHeld = true;
                    host.hold(cur);
                }
            }
        };

        public StageView(
                Context context,
                WallPickers.Host host,
                int mode,
                int count,
                int current,
                int screenW,
                int screenH) {
            super(context);
            this.host = host;
            this.mode = mode;
            this.n = Math.max(0, count);
            this.dp = context.getResources().getDisplayMetrics().density;
            this.cur = clampIndex(current, this.n);
            this.target = this.cur;
            this.pos = this.cur;

            int maxW = screenW - Math.round(48.0f * dp);
            int maxH = screenH - Math.round(148.0f * dp);
            this.vw = Math.max(Math.round(320.0f * dp), Math.min(Math.round(screenW * 0.92f), maxW));
            this.vh = Math.max(Math.round(220.0f * dp), Math.min(Math.round(screenH * 0.68f), maxH));

            int cap = Math.max(1, this.n);
            this.thumbs = new Bitmap[cap];
            this.shaders = new BitmapShader[cap];
            this.accents = new int[cap];
            this.fadeVal = new float[cap];
            this.fadeVel = new float[cap];
            Arrays.fill(this.accents, 0xFF38BDF8);

            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            strokePaint.setStrokeJoin(Paint.Join.ROUND);
            fillPaint.setStyle(Paint.Style.FILL);
            glowPaint.setStyle(Paint.Style.STROKE);
            glowPaint.setStrokeCap(Paint.Cap.ROUND);
            glowPaint.setStrokeJoin(Paint.Join.ROUND);

            setFocusable(true);
            setFocusableInTouchMode(true);
            setClickable(true);
        }

        @Override
        public View view() {
            return this;
        }

        @Override
        public int viewW() {
            return vw;
        }

        @Override
        public int viewH() {
            return vh;
        }

        @Override
        public int thumbW() {
            return 480;
        }

        @Override
        public int thumbH() {
            return 270;
        }

        @Override
        public int current() {
            return cur;
        }

        @Override
        public void setThumb(int index, Bitmap bitmap, int accentColor) {
            if (index < 0 || index >= n) {
                return;
            }
            thumbs[index] = bitmap;
            if (accentColor != 0) {
                accents[index] = accentColor | 0xFF000000;
            }
            if (bitmap != null && !bitmap.isRecycled()) {
                shaders[index] = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                if (fadeVal[index] < 0.45f) {
                    fadeVal[index] = 0.45f;
                }
            } else {
                shaders[index] = null;
            }
            postInvalidateOnAnimation();
        }

        public void clearThumb(int index) {
            if (index < 0 || index >= n) {
                return;
            }
            thumbs[index] = null;
            shaders[index] = null;
            fadeVal[index] = 0.0f;
            fadeVel[index] = 0.0f;
            postInvalidateOnAnimation();
        }

        boolean stepMove(int keyCode) {
            if (n <= 0) {
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                moveBy(-1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                moveBy(1);
                return true;
            }
            return false;
        }

        void moveBy(int delta) {
            if (n <= 0 || delta == 0) {
                return;
            }
            if (n == 1) {
                target = 0;
                cur = 0;
                return;
            }
            target += delta;
            spinTarget += delta * 120.0f;
            cur = wrapIndex(target, n);
            WallpaperThumbLoader.onPickerMoved(this);
            postInvalidateOnAnimation();
        }

        @Override
        protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previouslyFocusedRect) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
            focusTarget = gainFocus ? 1.0f : 0.35f;
            if (!gainFocus) {
                okDown = false;
                okHeld = false;
                removeCallbacks(holdRunnable);
            }
            postInvalidateOnAnimation();
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || keyCode == KeyEvent.KEYCODE_DPAD_UP
                    || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                return stepMove(keyCode) || super.onKeyDown(keyCode, event);
            }
            if (isOkKey(keyCode)) {
                if (event.getRepeatCount() == 0) {
                    okDown = true;
                    okHeld = false;
                    removeCallbacks(holdRunnable);
                    postDelayed(holdRunnable, 420L);
                }
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        @Override
        public boolean onKeyUp(int keyCode, KeyEvent event) {
            if (isOkKey(keyCode)) {
                removeCallbacks(holdRunnable);
                boolean triggerChoose = okDown && !okHeld;
                okDown = false;
                okHeld = false;
                if (triggerChoose && n > 0) {
                    host.choose(cur);
                }
                return true;
            }
            return super.onKeyUp(keyCode, event);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (n <= 0) {
                return false;
            }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                requestFocus();
                float x = event.getX();
                if (x < vw * 0.30f) {
                    moveBy(-1);
                } else if (x > vw * 0.70f) {
                    moveBy(1);
                } else {
                    host.choose(cur);
                }
                return true;
            }
            return super.onTouchEvent(event);
        }

        /**
         * Critically-damped sub-stepped spring integration (matching `SliceStrip` 250ms feel).
         * Stops invalidating as soon as springs settle -> 0% idle CPU/GPU.
         */
        private boolean stepPhysics(long nowMs) {
            float dt = (lastFrameMs == 0L) ? 0.016f : (nowMs - lastFrameMs) * 0.001f;
            lastFrameMs = nowMs;
            if (dt < 0.0f) {
                dt = 0.0f;
            } else if (dt > 0.050f) {
                dt = 0.050f;
            }

            boolean moving = false;
            float rem = dt;
            // omega = 6.64 / 0.24s = 27.6f -> k = 765, c = 55.3 (critically damped, zero overshoot lag)
            final float kPos = 765.0f;
            final float cPos = 55.3f;
            final float kSpin = 420.0f;
            final float cSpin = 41.0f;
            while (rem > 0.0f) {
                float step = Math.min(rem, 0.004166667f);
                float aPos = -kPos * (pos - target) - cPos * posVel;
                posVel += aPos * step;
                pos += posVel * step;

                float aSpin = -kSpin * (spinAngle - spinTarget) - cSpin * spinVel;
                spinVel += aSpin * step;
                spinAngle += spinVel * step;

                float aFoc = -kPos * (focusValue - focusTarget) - cPos * focusVel;
                focusVel += aFoc * step;
                focusValue += focusVel * step;
                rem -= step;
            }

            if (Math.abs(pos - target) > 0.001f || Math.abs(posVel) > 0.01f) {
                moving = true;
            } else {
                pos = target;
                posVel = 0.0f;
            }

            if (Math.abs(spinAngle - spinTarget) > 0.15f || Math.abs(spinVel) > 0.5f) {
                moving = true;
            } else {
                spinAngle = spinTarget;
                spinVel = 0.0f;
            }

            if (Math.abs(focusValue - focusTarget) > 0.002f || Math.abs(focusVel) > 0.01f) {
                moving = true;
            } else {
                focusValue = focusTarget;
                focusVel = 0.0f;
            }

            for (int i = 0; i < n; i++) {
                float fdTarget = (thumbs[i] != null) ? 1.0f : 0.0f;
                float fdDiff = fdTarget - fadeVal[i];
                if (Math.abs(fdDiff) > 0.004f || Math.abs(fadeVel[i]) > 0.01f) {
                    fadeVel[i] += (fdDiff * 480.0f - fadeVel[i] * 44.0f) * dt;
                    fadeVal[i] += fadeVel[i] * dt;
                    if (fadeVal[i] < 0.0f) {
                        fadeVal[i] = 0.0f;
                    } else if (fadeVal[i] > 1.0f) {
                        fadeVal[i] = 1.0f;
                    }
                    moving = true;
                } else {
                    fadeVal[i] = fdTarget;
                    fadeVel[i] = 0.0f;
                }
            }

            if (!moving) {
                lastFrameMs = 0L;
            }
            return moving;
        }

        private void setupBitmapShaderFit(
                int index,
                float left,
                float top,
                float width,
                float height) {
            Bitmap bmp = thumbs[index];
            BitmapShader shader = shaders[index];
            if (bmp == null || shader == null || bmp.isRecycled() || width <= 1.0f || height <= 1.0f) {
                imgPaint.setShader(null);
                return;
            }
            int bw = bmp.getWidth();
            int bh = bmp.getHeight();
            if (bw <= 0 || bh <= 0) {
                imgPaint.setShader(null);
                return;
            }
            float scale = Math.max(width / bw, height / bh);
            float dx = left + (width - bw * scale) * 0.5f;
            float dy = top + (height - bh * scale) * 0.5f;
            shaderMatrix.reset();
            shaderMatrix.setScale(scale, scale);
            shaderMatrix.postTranslate(dx, dy);
            shader.setLocalMatrix(shaderMatrix);
            imgPaint.setShader(shader);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (n <= 0) {
                return;
            }
            boolean animateNext = stepPhysics(SystemClock.uptimeMillis());

            switch (mode) {
                case MODE_SHATTERED_PRISM:
                    drawShatteredPrism(canvas);
                    break;
                case MODE_VINYL_HOLOGRAM:
                    drawVinylHologram(canvas);
                    break;
                case MODE_LIQUID_METABALL:
                    drawLiquidMetaball(canvas);
                    break;
                case MODE_QUANTUM_HELIX:
                default:
                    drawQuantumHelix(canvas);
                    break;
            }

            if (animateNext) {
                postInvalidateOnAnimation();
            }
        }

        // =====================================================================
        // MODE 1: SHATTERED PRISM ("Kính vạn hoa đa diện") — 100% GPU Primitives
        // =====================================================================
        private void drawShatteredPrism(Canvas canvas) {
            int centerSlot = Math.round(pos);
            float cx = vw * 0.5f;
            float cy = vh * 0.5f;
            float heroW = Math.min(vw * 0.50f, vh * 0.72f * (16.0f / 9.0f));
            float heroH = heroW * (9.0f / 16.0f);
            float stepX = heroW * 0.44f;

            // Draw outer crystal shards (-4..+4) inward to centerSlot (0 drawPath calls!)
            for (int dist = 4; dist >= 0; dist--) {
                int sides = (dist == 0) ? 1 : 2;
                for (int side = 0; side < sides; side++) {
                    int slot = (dist == 0) ? centerSlot : (side == 0 ? centerSlot - dist : centerSlot + dist);
                    int idx = wrapIndex(slot, n);
                    float rel = slot - pos;
                    float absRel = Math.abs(rel);
                    float s = slotSelection(slot, pos);
                    float fd = fadeVal[idx];
                    int accent = accents[idx];

                    // Kaleidoscopic 2D constellation placement around the central crystal
                    int band = Math.floorMod(slot, 3);
                    float ySign = (band == 0) ? -1.0f : (band == 1 ? 1.0f : (((slot & 1) == 0) ? -0.45f : 0.45f));
                    float shardX = cx + rel * stepX;
                    float shardY = cy + ySign * (vh * 0.19f) * (1.0f - s);

                    float scale = (0.44f - Math.min(0.16f, absRel * 0.035f)) * (1.0f - s) + s * 1.0f;
                    float w = heroW * scale;
                    float h = heroH * scale;
                    float tiltDeg = ((slot & 1) == 0 ? 1.0f : -1.0f) * Math.min(18.0f, absRel * 7.5f) * (1.0f - s);
                    float skewX = ((band == 0) ? 0.10f : (band == 1 ? -0.10f : 0.04f)) * (1.0f - s);
                    float corner = (12.0f + 8.0f * s) * dp * scale;

                    canvas.save();
                    canvas.translate(shardX, shardY);
                    if (Math.abs(tiltDeg) > 0.05f) {
                        canvas.rotate(tiltDeg);
                    }


                    float halfW = w * 0.5f;
                    float halfH = h * 0.5f;
                    cardRect.set(-halfW, -halfH, halfW, halfH);

                    // Prismatic outer aura on active crystal
                    if (s > 0.05f) {
                        tmpRect.set(-halfW - 4.0f * dp, -halfH - 4.0f * dp, halfW + 4.0f * dp, halfH + 4.0f * dp);
                        glowPaint.setStrokeWidth((4.5f + 3.5f * s) * dp);
                        glowPaint.setColor(withAlpha(accent, Math.round(95.0f * s * focusValue)));
                        canvas.drawRoundRect(tmpRect, corner + 3.0f * dp, corner + 3.0f * dp, glowPaint);
                    }

                    fillPaint.setColor(0xE80F172A);
                    canvas.drawRoundRect(cardRect, corner, corner, fillPaint);

                    if (thumbs[idx] != null && fd > 0.01f) {
                        setupBitmapShaderFit(idx, -halfW, -halfH, w, h);
                        int alpha = Math.round(255.0f * fd * (0.62f + 0.38f * s));
                        imgPaint.setAlpha(alpha);
                        canvas.drawRoundRect(cardRect, corner, corner, imgPaint);
                    }

                    // Diagonal crystal facet sheen lines across neighbor shards
                    strokePaint.setStrokeWidth(1.2f * dp);
                    strokePaint.setColor(withAlpha(0xFFFFFFFF, Math.round(52.0f * (1.0f - 0.5f * s))));
                    canvas.drawLine(-halfW * 0.65f, -halfH, halfW * 0.35f, halfH, strokePaint);

                    strokePaint.setStrokeWidth((1.4f + 1.8f * s) * dp);
                    strokePaint.setColor(blendColor(0x8094A3B8, withAlpha(accent, 0xFF), s));
                    canvas.drawRoundRect(cardRect, corner, corner, strokePaint);

                    drawInUseAndIndexPill(canvas, idx, 0.0f, halfH - 15.0f * dp, s, accent);
                    canvas.restore();
                }
            }
        }

        // =====================================================================
        // MODE 2: HOLOGRAPHIC VINYL RECORD ("Đĩa than Hologram") — 100% GPU Primitives
        // =====================================================================
        private void drawVinylHologram(Canvas canvas) {
            int centerSlot = Math.round(pos);
            float cy = vh * 0.50f;
            float baseSleeveW = Math.min(vw * 0.44f, vh * 0.68f * (16.0f / 9.0f));
            float baseSleeveH = baseSleeveW * (9.0f / 16.0f);
            float activeCenterX = vw * 0.42f;

            for (int dist = 4; dist >= 0; dist--) {
                int slotCount = (dist == 0) ? 1 : 2;
                for (int side = 0; side < slotCount; side++) {
                    int slot = (dist == 0) ? centerSlot : (side == 0 ? centerSlot - dist : centerSlot + dist);
                    int idx = wrapIndex(slot, n);
                    float rel = slot - pos;
                    float absRel = Math.abs(rel);
                    float s = slotSelection(slot, pos);
                    float scale = 1.0f - Math.min(0.38f, absRel * 0.11f) + s * 0.04f;
                    float sw = baseSleeveW * scale;
                    float sh = baseSleeveH * scale;

                    float vinylShift = (rel > 0.15f) ? (baseSleeveW * 0.24f * Math.min(1.0f, rel)) : 0.0f;
                    float sx = activeCenterX + rel * (baseSleeveW * 0.34f) + vinylShift;
                    float sy = cy + absRel * 7.0f * dp;
                    int accent = accents[idx];

                    if (s > 0.02f) {
                        drawSpinningVinylDisc(canvas, idx, sx + sw * (0.14f + 0.36f * s), sy, sh * 0.50f, s, accent);
                    }

                    float left = sx - sw * 0.5f;
                    float top = sy - sh * 0.5f;
                    float corner = 16.0f * dp * scale;
                    cardRect.set(left, top, left + sw, top + sh);

                    if (s > 0.05f) {
                        fillPaint.setColor(withAlpha(accent, Math.round(48.0f * s)));
                        tmpRect.set(left - 4.0f * dp, top + 5.0f * dp, left + sw + 4.0f * dp, top + sh + 7.0f * dp);
                        canvas.drawRoundRect(tmpRect, corner + 3.0f * dp, corner + 3.0f * dp, fillPaint);
                    }

                    fillPaint.setColor(0xEA0F172A);
                    canvas.drawRoundRect(cardRect, corner, corner, fillPaint);

                    if (thumbs[idx] != null && fadeVal[idx] > 0.01f) {
                        setupBitmapShaderFit(idx, left, top, sw, sh);
                        int alpha = Math.round(255.0f * fadeVal[idx] * (0.60f + 0.40f * s));
                        imgPaint.setAlpha(alpha);
                        canvas.drawRoundRect(cardRect, corner, corner, imgPaint);
                    }

                    strokePaint.setStrokeWidth((1.3f + 1.6f * s) * dp);
                    strokePaint.setColor(blendColor(0x7794A3B8, withAlpha(accent, 0xFF), s));
                    canvas.drawRoundRect(cardRect, corner, corner, strokePaint);

                    strokePaint.setStrokeWidth(1.2f * dp);
                    strokePaint.setColor(0x44FFFFFF);
                    canvas.drawLine(left + 12.0f * dp, top + 6.0f * dp, left + 12.0f * dp, top + sh - 6.0f * dp, strokePaint);

                    drawInUseAndIndexPill(canvas, idx, sx, top + sh - 16.0f * dp, s, accent);
                }
            }
        }

        private void drawSpinningVinylDisc(
                Canvas canvas,
                int idx,
                float discCx,
                float discCy,
                float discR,
                float s,
                int accent) {
            int alphaScale = Math.round(255.0f * Math.min(1.0f, s * 1.15f));
            if (alphaScale <= 4) {
                return;
            }

            glowPaint.setStrokeWidth(5.0f * dp);
            glowPaint.setColor(withAlpha(accent, (alphaScale * 85) / 255));
            canvas.drawCircle(discCx, discCy, discR + 2.0f * dp, glowPaint);

            fillPaint.setColor(withAlpha(0xFF090D16, (alphaScale * 245) / 255));
            canvas.drawCircle(discCx, discCy, discR, fillPaint);

            float spinDeg = spinAngle % 360.0f;
            for (int g = 1; g <= 5; g++) {
                float frac = 0.44f + g * 0.10f;
                float r = discR * frac;
                strokePaint.setStrokeWidth(((g & 1) == 0 ? 1.4f : 0.9f) * dp);
                int grooveColor = (g == 3)
                        ? withAlpha(accent, (alphaScale * 110) / 255)
                        : withAlpha(0xFF64748B, (alphaScale * 70) / 255);
                strokePaint.setColor(grooveColor);
                canvas.drawCircle(discCx, discCy, r, strokePaint);

                tmpRect.set(discCx - r, discCy - r, discCx + r, discCy + r);
                strokePaint.setColor(withAlpha(0xFFFFFFFF, (alphaScale * 80) / 255));
                canvas.drawArc(tmpRect, spinDeg + g * 28.0f, 36.0f, false, strokePaint);
                canvas.drawArc(tmpRect, spinDeg + 180.0f + g * 28.0f, 36.0f, false, strokePaint);
            }

            float labelR = discR * 0.36f;
            if (thumbs[idx] != null && shaders[idx] != null && !thumbs[idx].isRecycled()) {
                Bitmap bmp = thumbs[idx];
                float scale = (labelR * 2.1f) / Math.min(bmp.getWidth(), bmp.getHeight());
                shaderMatrix.reset();
                shaderMatrix.postTranslate(-bmp.getWidth() * 0.5f, -bmp.getHeight() * 0.5f);
                shaderMatrix.postScale(scale, scale);
                shaderMatrix.postRotate(spinDeg);
                shaderMatrix.postTranslate(discCx, discCy);
                shaders[idx].setLocalMatrix(shaderMatrix);
                imgPaint.setShader(shaders[idx]);
                imgPaint.setAlpha(alphaScale);
                canvas.drawCircle(discCx, discCy, labelR, imgPaint);
            } else {
                fillPaint.setColor(withAlpha(accent, (alphaScale * 180) / 255));
                canvas.drawCircle(discCx, discCy, labelR, fillPaint);
            }

            strokePaint.setStrokeWidth(2.0f * dp);
            strokePaint.setColor(withAlpha(accent, alphaScale));
            canvas.drawCircle(discCx, discCy, labelR, strokePaint);

            fillPaint.setColor(withAlpha(0xFF090D16, alphaScale));
            canvas.drawCircle(discCx, discCy, discR * 0.075f, fillPaint);
            strokePaint.setStrokeWidth(1.4f * dp);
            strokePaint.setColor(withAlpha(0xFFE2E8F0, alphaScale));
            canvas.drawCircle(discCx, discCy, discR * 0.075f, strokePaint);

            strokePaint.setStrokeWidth(2.2f * dp);
            strokePaint.setColor(withAlpha(accent, alphaScale));
            canvas.drawCircle(discCx, discCy, discR, strokePaint);

            float pivotX = discCx + discR * 0.88f;
            float pivotY = discCy - discR * 0.78f;
            float stylusX = discCx + discR * 0.56f;
            float stylusY = discCy - discR * 0.12f;
            strokePaint.setStrokeWidth(2.6f * dp);
            strokePaint.setColor(withAlpha(0xFFE2E8F0, (alphaScale * 210) / 255));
            canvas.drawLine(pivotX, pivotY, stylusX, stylusY, strokePaint);
            fillPaint.setColor(withAlpha(accent, alphaScale));
            canvas.drawCircle(pivotX, pivotY, 5.5f * dp, fillPaint);
            canvas.drawCircle(stylusX, stylusY, 4.0f * dp, fillPaint);
        }

        // =====================================================================
        // MODE 3: LIQUID METABALLS ("Giọt thủy ngân SDF") — 100% GPU Primitives
        // =====================================================================
        private void drawLiquidMetaball(Canvas canvas) {
            int centerSlot = Math.round(pos);
            float cx = vw * 0.5f;
            float cy = vh * 0.5f;
            float heroW = Math.min(vw * 0.48f, vh * 0.70f * (16.0f / 9.0f));
            float heroH = heroW * (9.0f / 16.0f);
            float spacing = heroW * 0.56f;

            float frac = Math.abs(pos - Math.round(pos));
            float velStretch = Math.min(0.22f, Math.abs(posVel) * 0.018f + frac * 0.18f);

            // First pass: GPU-native liquid metaball bridge capsules between adjacent droplets
            for (int slot = centerSlot - 3; slot < centerSlot + 3; slot++) {
                float relA = slot - pos;
                float relB = (slot + 1) - pos;
                if (Math.abs(relA) < 1.25f || Math.abs(relB) < 1.25f) {
                    float sA = slotSelection(slot, pos);
                    float sB = slotSelection(slot + 1, pos);
                    float pull = Math.max(sA, sB) * (0.25f + velStretch * 3.2f);
                    if (pull > 0.06f) {
                        int idxA = wrapIndex(slot, n);
                        int idxB = wrapIndex(slot + 1, n);
                        float xA = cx + relA * spacing;
                        float xB = cx + relB * spacing;
                        float midX = (xA + xB) * 0.5f;
                        float bridgeW = (xB - xA) * 0.68f;
                        float bridgeH = heroH * (0.14f + 0.18f * Math.min(1.0f, pull));
                        int bridgeCol = blendColor(accents[idxA], accents[idxB], 0.5f);
                        fillPaint.setColor(withAlpha(bridgeCol, Math.round(145.0f * Math.min(1.0f, pull))));
                        tmpRect.set(midX - bridgeW * 0.5f, cy - bridgeH * 0.5f, midX + bridgeW * 0.5f, cy + bridgeH * 0.5f);
                        canvas.drawRoundRect(tmpRect, bridgeH * 0.5f, bridgeH * 0.5f, fillPaint);
                    }
                }
            }

            // Second pass: GPU-native droplets morphing from oval mercury capsules (s=0) to 16:9 liquid lens (s=1)
            for (int dist = 4; dist >= 0; dist--) {
                int slotCount = (dist == 0) ? 1 : 2;
                for (int side = 0; side < slotCount; side++) {
                    int slot = (dist == 0) ? centerSlot : (side == 0 ? centerSlot - dist : centerSlot + dist);
                    int idx = wrapIndex(slot, n);
                    float rel = slot - pos;
                    float s = slotSelection(slot, pos);
                    int accent = accents[idx];

                    float dropCx = cx + rel * spacing;
                    float dropCy = cy;
                    float cardScale = 0.46f + 0.54f * s;
                    float w = heroW * cardScale;
                    float h = heroH * cardScale;

                    // Subtle velocity squash on neighbor droplets only; active card (s=1) stays exact 16:9
                    float neighborStretch = velStretch * (1.0f - s);
                    float drawW = w * (1.0f + neighborStretch * 0.35f);
                    float drawH = h * (1.0f - neighborStretch * 0.20f);
                    float left = dropCx - drawW * 0.5f;
                    float top = dropCy - drawH * 0.5f;

                    // Corner radius morphs from full oval droplet (drawH * 0.5f) to 22dp 16:9 frame (s = 1)
                    float ovalCorner = drawH * 0.48f;
                    float frameCorner = 22.0f * dp;
                    float corner = ovalCorner + (frameCorner - ovalCorner) * (s * s);

                    cardRect.set(left, top, left + drawW, top + drawH);

                    if (s > 0.05f) {
                        tmpRect.set(left - 4.0f * dp, top - 4.0f * dp, left + drawW + 4.0f * dp, top + drawH + 4.0f * dp);
                        glowPaint.setStrokeWidth((5.0f + 3.5f * s) * dp);
                        glowPaint.setColor(withAlpha(accent, Math.round(92.0f * s)));
                        canvas.drawRoundRect(tmpRect, corner + 3.0f * dp, corner + 3.0f * dp, glowPaint);
                    }

                    fillPaint.setColor(0xE80F172A);
                    canvas.drawRoundRect(cardRect, corner, corner, fillPaint);

                    if (thumbs[idx] != null && fadeVal[idx] > 0.01f) {
                        setupBitmapShaderFit(idx, left, top, drawW, drawH);
                        int alpha = Math.round(255.0f * fadeVal[idx] * (0.62f + 0.38f * s));
                        imgPaint.setAlpha(alpha);
                        canvas.drawRoundRect(cardRect, corner, corner, imgPaint);
                    }

                    strokePaint.setStrokeWidth((1.5f + 1.8f * s) * dp);
                    strokePaint.setColor(blendColor(0x8894A3B8, withAlpha(accent, 0xFF), s));
                    canvas.drawRoundRect(cardRect, corner, corner, strokePaint);

                    // Top-left liquid meniscus specular highlight
                    tmpRect.set(left + drawW * 0.08f, top + drawH * 0.08f, left + drawW * 0.92f, top + drawH * 0.92f);
                    strokePaint.setStrokeWidth((2.0f + 1.0f * s) * dp);
                    strokePaint.setColor(withAlpha(0xFFFFFFFF, Math.round(80.0f + 70.0f * s)));
                    canvas.drawArc(tmpRect, 198.0f, 44.0f, false, strokePaint);

                    drawInUseAndIndexPill(canvas, idx, dropCx, top + drawH - 16.0f * dp, s, accent);
                }
            }
        }

        // =====================================================================
        // MODE 4: 3D QUANTUM DOUBLE-HELIX ("Chuỗi xoắn kép DNA 3D") — 100% GPU Primitives
        // =====================================================================
        private void drawQuantumHelix(Canvas canvas) {
            int centerSlot = Math.round(pos);
            float cx = vw * 0.5f;
            float cy = vh * 0.5f;
            float ampY = vh * 0.24f;
            float stepX = vw * 0.145f;
            int activeAccent = accents[wrapIndex(centerSlot, n)];
            float phaseRad = (spinAngle * (float) Math.PI) / 180.0f;

            // GPU-native double-helix backbone segments & synapse rungs (0 drawPath calls!)
            int samples = 24;
            float spanL = vw * 0.05f;
            float spanR = vw * 0.95f;
            float prevX = 0.0f;
            float prevYA = 0.0f;
            float prevYB = 0.0f;
            for (int s = 0; s <= samples; s++) {
                float t = s / (float) samples;
                float x = spanL + (spanR - spanL) * t;
                float relX = (x - cx) / stepX;
                float theta = relX * 0.95f + phaseRad * 0.35f;
                float centerPinch = 1.0f - (float) Math.exp(-(relX * relX) * 0.55f) * 0.65f;
                float yA = cy + (float) Math.sin(theta) * ampY * centerPinch;
                float yB = cy - (float) Math.sin(theta) * ampY * centerPinch;
                if (s > 0) {
                    strokePaint.setStrokeWidth(2.0f * dp);
                    strokePaint.setColor(withAlpha(activeAccent, 110));
                    canvas.drawLine(prevX, prevYA, x, yA, strokePaint);
                    strokePaint.setColor(withAlpha(0xFF38BDF8, 92));
                    canvas.drawLine(prevX, prevYB, x, yB, strokePaint);
                }
                if ((s & 1) == 0) {
                    strokePaint.setStrokeWidth(1.2f * dp);
                    strokePaint.setColor(withAlpha(activeAccent, 52));
                    canvas.drawLine(x, yA, x, yB, strokePaint);
                }
                prevX = x;
                prevYA = yA;
                prevYB = yB;
            }

            int count = 0;
            for (int slot = centerSlot - 4; slot <= centerSlot + 4 && count < MAX_VISIBLE_NODES; slot++) {
                float rel = slot - pos;
                float s = slotSelection(slot, pos);
                float theta = rel * 0.95f + ((slot & 1) == 0 ? 0.0f : (float) Math.PI);
                float z = (float) Math.cos(theta) * (1.0f - s) + s * 1.25f;
                drawSlots[count] = slot;
                nodeDepth[count] = z;
                count++;
            }

            for (int i = 1; i < count; i++) {
                int keySlot = drawSlots[i];
                float keyZ = nodeDepth[i];
                int j = i - 1;
                while (j >= 0 && nodeDepth[j] > keyZ) {
                    drawSlots[j + 1] = drawSlots[j];
                    nodeDepth[j + 1] = nodeDepth[j];
                    j--;
                }
                drawSlots[j + 1] = keySlot;
                nodeDepth[j + 1] = keyZ;
            }

            float heroW = Math.min(vw * 0.48f, vh * 0.70f * (16.0f / 9.0f));
            float heroH = heroW * (9.0f / 16.0f);

            for (int k = 0; k < count; k++) {
                int slot = drawSlots[k];
                int idx = wrapIndex(slot, n);
                float rel = slot - pos;
                float s = slotSelection(slot, pos);
                int accent = accents[idx];

                float theta = rel * 0.95f + ((slot & 1) == 0 ? 0.0f : (float) Math.PI);
                float depth01 = ((float) Math.cos(theta) + 1.0f) * 0.5f;
                float scale = (0.42f + 0.28f * depth01) * (1.0f - s) + s * 1.0f;

                float nodeX = cx + rel * stepX;
                float nodeY = cy + (float) Math.sin(theta) * ampY * (1.0f - s);
                float cw = heroW * scale;
                float ch = heroH * scale;
                float left = nodeX - cw * 0.5f;
                float top = nodeY - ch * 0.5f;
                float corner = (14.0f + 8.0f * s) * dp * scale;

                if (s > 0.1f) {
                    tmpRect.set(
                            nodeX - cw * 0.56f,
                            nodeY - ch * 0.60f,
                            nodeX + cw * 0.56f,
                            nodeY + ch * 0.60f);
                    glowPaint.setStrokeWidth(2.4f * dp);
                    glowPaint.setColor(withAlpha(accent, Math.round(125.0f * s)));
                    float orbitAng = spinAngle % 360.0f;
                    canvas.drawArc(tmpRect, orbitAng, 110.0f, false, glowPaint);
                    canvas.drawArc(tmpRect, orbitAng + 180.0f, 110.0f, false, glowPaint);
                }

                cardRect.set(left, top, left + cw, top + ch);
                fillPaint.setColor(0xEA0F172A);
                canvas.drawRoundRect(cardRect, corner, corner, fillPaint);

                if (thumbs[idx] != null && fadeVal[idx] > 0.01f) {
                    setupBitmapShaderFit(idx, left, top, cw, ch);
                    float depthAlpha = (0.55f + 0.35f * depth01) * (1.0f - s) + s;
                    int alpha = Math.round(255.0f * fadeVal[idx] * depthAlpha);
                    imgPaint.setAlpha(alpha);
                    canvas.drawRoundRect(cardRect, corner, corner, imgPaint);
                }

                strokePaint.setStrokeWidth((1.2f + 2.0f * s) * dp);
                strokePaint.setColor(blendColor(0x7794A3B8, withAlpha(accent, 0xFF), s));
                canvas.drawRoundRect(cardRect, corner, corner, strokePaint);

                drawInUseAndIndexPill(canvas, idx, nodeX, top + ch - 16.0f * dp, s, accent);
            }
        }

        private void drawInUseAndIndexPill(Canvas canvas, int idx, float cx, float cy, float s, int accent) {
            boolean inUse = host.inUse(idx);
            if (!inUse && s < 0.5f) {
                return;
            }
            float dotR = (4.5f + 2.0f * s) * dp;
            if (inUse) {
                fillPaint.setColor(0xFF22C55E);
                canvas.drawCircle(cx, cy, dotR, fillPaint);
                strokePaint.setStrokeWidth(1.5f * dp);
                strokePaint.setColor(0xFFFFFFFF);
                canvas.drawCircle(cx, cy, dotR, strokePaint);
            } else {
                fillPaint.setColor(withAlpha(accent, Math.round(210.0f * s)));
                canvas.drawCircle(cx, cy, dotR * 0.75f, fillPaint);
            }
        }
    }
}
