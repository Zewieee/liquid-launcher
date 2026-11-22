package local.launcherfix;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.LinearInterpolator;
import java.util.ArrayList;

/**
 * Mochy-inspired 60fps lyric renderer:
 * - Per-token aligned ruby romaji underneath every Japanese word across all visible lines
 * - Soft feathered gradient word sweep (no harsh vertical clip edge)
 * - Soft luminous oval spotlight hugging the currently pronounced word + romaji
 * - Continuous C1 per-word float/lift wave during active word sync
 * - Staggered ripple cascade + smooth scale/opacity morph on line transitions
 */
final class RubyLyricsView extends View {
    static final class Cell {
        final float x, cellW, jpW, roW;
        final int cpStart, cpEnd;
        final String surface, romaji;
        final boolean blank;
        Cell(float x, float cellW, float jpW, float roW, int cpStart, int cpEnd, String surface, String romaji) {
            this.x = x; this.cellW = cellW; this.jpW = jpW; this.roW = roW;
            this.cpStart = cpStart; this.cpEnd = cpEnd;
            this.surface = surface; this.romaji = romaji;
            this.blank = surface == null || surface.trim().isEmpty();
        }
    }

    static final class VisualRow {
        final Cell[] cells;
        final float top, jpBaseline, roBaseline, bottom, width;
        final boolean hasRomaji;
        VisualRow(Cell[] cells, float top, float jpBaseline, float roBaseline, float bottom, float width, boolean hasRomaji) {
            this.cells = cells; this.top = top; this.jpBaseline = jpBaseline;
            this.roBaseline = roBaseline; this.bottom = bottom;
            this.width = width; this.hasRomaji = hasRomaji;
        }
    }

    static final class LineBlock {
        final VisualRow[] rows;
        final float height, maxRowWidth;
        LineBlock(VisualRow[] rows, float height, float maxRowWidth) {
            this.rows = rows; this.height = height; this.maxRowWidth = maxRowWidth;
        }
    }

    private static final class Slice {
        final String surface, romaji;
        final int cpStart, cpEnd;
        final boolean newline;
        Slice(String surface, String romaji, int cpStart, int cpEnd, boolean newline) {
            this.surface = surface; this.romaji = romaji;
            this.cpStart = cpStart; this.cpEnd = cpEnd; this.newline = newline;
        }
    }

    private final float dp, sp;
    private final boolean vi;
    private final Paint activeJpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint activeGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint activeSweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint activeRoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint activeRoSweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint inactiveJpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint inactiveRoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint spotlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

    private final LinearGradient jpSweepShader;
    private final LinearGradient roSweepShader;
    private final RadialGradient spotlightShader;
    private final Matrix sweepMatrix = new Matrix();
    private final Matrix spotlightMatrix = new Matrix();
    private final RectF ovalBounds = new RectF();

    private Typeface appliedBold, appliedRegular;
    private long appliedFaceEpoch = -1L;
    private Lyrics lyrics;
    private String status = "waiting";
    private int currentIndex = Integer.MIN_VALUE;
    private double coverage = Double.NaN;
    private boolean hudAwake = true;
    private LineBlock[] activeBlocks;
    private LineBlock[] inactiveBlocks;
    private int cachedWidth;
    private String specialText;
    private int specialWidth = -1, specialMaxRows = -1;
    private LineBlock specialBlock;

    private ValueAnimator cascadeAnimator;
    private boolean cascading;
    private int transitionFromIndex = Integer.MIN_VALUE;
    private float animT = 1f;

    RubyLyricsView(Context context, float dp, boolean vi) {
        super(context);
        this.dp = dp;
        this.sp = context.getResources().getDisplayMetrics().scaledDensity;
        this.vi = vi;

        float activeJpPx = 24.5f * sp;
        float activeRoPx = 10.2f * sp;
        activeJpPaint.setTextSize(activeJpPx);
        activeGlowPaint.setTextSize(activeJpPx);
        activeGlowPaint.setShadowLayer(7.0f * dp, 0f, 0f, 0x58ffffff);
        activeSweepPaint.setTextSize(activeJpPx);
        activeSweepPaint.setShadowLayer(5.5f * dp, 0f, 0f, 0x3cffffff);

        activeRoPaint.setTextSize(activeRoPx);
        activeRoSweepPaint.setTextSize(activeRoPx);

        inactiveJpPaint.setTextSize(19.0f * sp);
        inactiveRoPaint.setTextSize(8.8f * sp);

        // Zero-allocation reusable shaders transformed via Matrix per active word.
        jpSweepShader = new LinearGradient(0f, 0f, 1f, 0f,
            new int[]{0xffffffff, 0xffffffff, 0xaaffffff, 0x58ffffff, 0x58ffffff},
            new float[]{0f, 0.22f, 0.50f, 0.78f, 1f}, Shader.TileMode.CLAMP);
        activeSweepPaint.setShader(jpSweepShader);

        roSweepShader = new LinearGradient(0f, 0f, 1f, 0f,
            new int[]{0xf2ffffff, 0xf2ffffff, 0x98ffffff, 0x48ffffff, 0x48ffffff},
            new float[]{0f, 0.20f, 0.50f, 0.80f, 1f}, Shader.TileMode.CLAMP);
        activeRoSweepPaint.setShader(roSweepShader);

        spotlightShader = new RadialGradient(0f, 0f, 1f,
            new int[]{0x28ffffff, 0x14ffffff, 0x05ffffff, 0x00ffffff},
            new float[]{0f, 0.46f, 0.78f, 1f}, Shader.TileMode.CLAMP);
        spotlightPaint.setShader(spotlightShader);

        refreshTypeface();
    }

    void refreshTypeface() {
        long epoch = FontChoice.faceEpoch();
        if (appliedFaceEpoch == epoch && appliedBold != null && appliedRegular != null) return;
        Typeface bold = FontChoice.style(getContext(), true);
        Typeface regular = FontChoice.style(getContext(), false);
        appliedFaceEpoch = FontChoice.faceEpoch();
        if (bold == appliedBold && regular == appliedRegular) return;
        appliedBold = bold;
        appliedRegular = regular;
        activeJpPaint.setTypeface(bold);
        activeGlowPaint.setTypeface(bold);
        activeSweepPaint.setTypeface(bold);
        inactiveJpPaint.setTypeface(bold);
        activeRoPaint.setTypeface(regular);
        activeRoSweepPaint.setTypeface(regular);
        inactiveRoPaint.setTypeface(regular);
        activeBlocks = null;
        inactiveBlocks = null;
        specialBlock = null;
        invalidate();
    }

    private LineBlock getSpecialBlock(String text, int w, int maxRows) {
        if (specialBlock != null && specialWidth == w && specialMaxRows == maxRows
                && text.equals(specialText)) {
            return specialBlock;
        }
        specialText = text;
        specialWidth = w;
        specialMaxRows = maxRows;
        specialBlock = measureBlock(text, null, activeJpPaint, activeRoPaint,
            5f * dp, 2f * dp, 7f * dp, w, maxRows, text);
        return specialBlock;
    }

    void setHudAwake(boolean awake) {
        if (hudAwake == awake) return;
        hudAwake = awake;
        invalidate();
    }

    void setLyrics(Lyrics data, String nextStatus) {
        boolean changed = lyrics != data || !String.valueOf(status).equals(String.valueOf(nextStatus));
        if (lyrics != data) {
            lyrics = data;
            activeBlocks = null;
            inactiveBlocks = null;
            resetMotion();
            currentIndex = Integer.MIN_VALUE;
            coverage = Double.NaN;
        }
        status = nextStatus == null ? "waiting" : nextStatus;
        if (changed) invalidate();
    }

    /**
     * Incremental live-line update from the playing app (DISPLAY_SUBTITLE):
     * preserves smooth cascade glide across appended/shifted lines and allows
     * async Japanese ruby enrichment without interrupting an active transition.
     */
    void updateLiveLyrics(Lyrics data, int activeIndex, boolean animate) {
        if (data == null || data.size() == 0 || activeIndex < 0 || activeIndex >= data.size()) {
            setLyrics(null, status);
            return;
        }
        lyrics = data;
        status = "ready";
        activeBlocks = null;
        inactiveBlocks = null;
        coverage = Double.NaN;
        if (animate && activeIndex > 0 && getWidth() > 0) {
            currentIndex = activeIndex;
            startCascade(activeIndex - 1);
        } else {
            if (!cascading) currentIndex = activeIndex;
            else if (activeIndex != currentIndex) {
                currentIndex = activeIndex;
                resetMotion();
            }
        }
        invalidate();
    }

    boolean updatePlayback(int nextIndex, long position, double nextCoverage, boolean animate) {
        boolean stepped = false;
        if (nextIndex != currentIndex) {
            int prev = currentIndex;
            currentIndex = nextIndex;
            if (animate && lyrics != null && prev >= -1 && nextIndex == prev + 1 && getWidth() > 0) {
                startCascade(prev);
                stepped = true;
            } else {
                resetMotion();
            }
            coverage = nextCoverage;
            invalidate();
            return stepped;
        }
        boolean wasTimed = !Double.isNaN(coverage) && !Double.isInfinite(coverage);
        boolean nowTimed = !Double.isNaN(nextCoverage) && !Double.isInfinite(nextCoverage);
        if (wasTimed != nowTimed || (nowTimed && Math.abs(nextCoverage - coverage) >= 0.001)) {
            coverage = nextCoverage;
            invalidate();
        }
        return false;
    }

    void resetMotion() {
        if (cascadeAnimator != null) {
            cascadeAnimator.cancel();
            cascadeAnimator = null;
        }
        if (cascading || animT != 1f) {
            cascading = false;
            transitionFromIndex = Integer.MIN_VALUE;
            animT = 1f;
            invalidate();
        }
    }

    private void startCascade(int fromIndex) {
        if (cascadeAnimator != null) cascadeAnimator.cancel();
        cascading = true;
        transitionFromIndex = fromIndex;
        animT = 0f;
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(480L);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            animT = (Float) a.getAnimatedValue();
            invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (cascadeAnimator == animation) {
                    cascading = false;
                    animT = 1f;
                    cascadeAnimator = null;
                    invalidate();
                }
            }
        });
        cascadeAnimator = anim;
        anim.start();
    }

    /** Staggered per-slot cubic-out progress in [0..1] without bounce or overshoot. */
    static float cascadeProgress(float rawT, int slot) {
        if (rawT <= 0f) return 0f;
        if (rawT >= 1f) return 1f;
        float start, end;
        if (slot <= -1) { start = 0.00f; end = 0.68f; }
        else if (slot == 0) { start = 0.05f; end = 0.76f; }
        else if (slot == 1) { start = 0.12f; end = 0.82f; }
        else if (slot == 2) { start = 0.19f; end = 0.88f; }
        else if (slot == 3) { start = 0.26f; end = 0.94f; }
        else { start = 0.32f; end = 1.00f; }
        float u = Math.max(0f, Math.min(1f, (rawT - start) / (end - start)));
        float inv = 1f - u;
        return 1f - inv * inv * inv;
    }

    /** Continuous C1 word float fraction: 0 unsung -> 1.0 peak pronunciation -> 0.78 settled sung. */
    static float wordLiftFraction(double covered, int cpStart, int cpEnd) {
        if (Double.isNaN(covered) || Double.isInfinite(covered) || covered <= cpStart) return 0f;
        if (covered >= cpEnd) return 0.78f;
        float u = (float) ((covered - cpStart) / Math.max(1, cpEnd - cpStart));
        float base = smoothstep(0f, 0.55f, u) * 0.78f;
        float crest = 0.22f * (float) Math.sin(Math.PI * u);
        return Math.max(0f, Math.min(1.05f, base + crest));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != cachedWidth) {
            activeBlocks = null;
            inactiveBlocks = null;
            cachedWidth = w;
        }
    }

    private void ensureCache(int width) {
        if (lyrics == null || width <= 0) return;
        if (cachedWidth != width || activeBlocks == null || activeBlocks.length != lyrics.size()) {
            cachedWidth = width;
            activeBlocks = new LineBlock[lyrics.size()];
            inactiveBlocks = new LineBlock[lyrics.size()];
        }
    }

    private LineBlock getBlock(int index, boolean active, int width) {
        ensureCache(width);
        LineBlock[] pool = active ? activeBlocks : inactiveBlocks;
        if (pool == null || index < 0 || index >= pool.length) return null;
        LineBlock cached = pool[index];
        if (cached != null) return cached;
        String text = lyrics.at(index);
        Lyrics.Ruby[] rubies = lyrics.rubiesAt(index);
        LineBlock built = measureBlock(
            text,
            rubies,
            active ? activeJpPaint : inactiveJpPaint,
            active ? activeRoPaint : inactiveRoPaint,
            5f * dp,
            active ? 2.5f * dp : 2.0f * dp,
            active ? 8.5f * dp : 6.5f * dp,
            width,
            4,
            text.isEmpty() ? "♪" : null
        );
        pool[index] = built;
        return built;
    }

    private static int slotJpAlpha(int slot) {
        if (slot <= -2) return 0x22;
        if (slot == -1) return 0x52;
        if (slot == 0) return 0xff;
        if (slot == 1) return 0xb0;
        if (slot == 2) return 0x90;
        if (slot == 3) return 0x6a;
        if (slot == 4) return 0x40;
        return 0x20;
    }

    private static int slotRoAlpha(int slot) {
        if (slot <= -2) return 0x18;
        if (slot == -1) return 0x3e;
        if (slot == 0) return 0xee;
        if (slot == 1) return 0x8c;
        if (slot == 2) return 0x70;
        if (slot == 3) return 0x52;
        if (slot == 4) return 0x30;
        return 0x18;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 8 || h <= 8) return;
        refreshTypeface();

        float focalY = Math.max(64f * dp, h * 0.195f);
        float topFade = 48f * dp;
        float bottomClear = hudAwake ? 36f * dp : 10f * dp;
        float bottomFade = 56f * dp;

        if (lyrics == null || !lyrics.isSynced() || lyrics.size() == 0) {
            String message = statusMessage();
            LineBlock empty = getSpecialBlock(message, w, 3);
            drawBlock(canvas, empty, focalY + 34f * dp, 1f, Double.NaN, false, 0xb8, 0x80, h, topFade, bottomClear, bottomFade);
            return;
        }

        ensureCache(w);
        float gapActive = 22f * dp, gapInactive = 19f * dp;

        boolean inCascade = cascading && animT < 1f && currentIndex >= 0
            && transitionFromIndex == currentIndex - 1 && currentIndex < lyrics.size();

        if (!inCascade) {
            drawRestingStage(canvas, w, h, focalY, gapActive, gapInactive, topFade, bottomClear, bottomFade);
        } else {
            drawCascadingStage(canvas, w, h, focalY, gapActive, gapInactive, topFade, bottomClear, bottomFade);
        }
    }

    private void drawRestingStage(Canvas canvas, int w, int h, float focalY,
                                  float gapActive, float gapInactive,
                                  float topFade, float bottomClear, float bottomFade) {
        boolean centerHasCue = currentIndex >= 0 && currentIndex < lyrics.size();
        LineBlock centerBlock;
        if (centerHasCue) {
            centerBlock = getBlock(currentIndex, true, w);
        } else {
            String intro = currentIndex == Integer.MIN_VALUE + 1
                ? (vi ? "Đang chờ vị trí phát" : "Waiting for playback position") : "♪";
            centerBlock = getSpecialBlock(intro, w, 2);
        }

        drawBlock(canvas, centerBlock, focalY, 1f, centerHasCue ? coverage : Double.NaN,
            centerHasCue, centerHasCue ? 0xff : 0x96, slotRoAlpha(0), h, topFade, bottomClear, bottomFade);

        int baseIdx = currentIndex < 0 ? -1 : currentIndex;
        float upY = focalY - gapInactive;
        for (int step = 1; step <= 2; step++) {
            int idx = baseIdx - step;
            if (idx < 0 || idx >= lyrics.size()) break;
            LineBlock block = getBlock(idx, false, w);
            upY -= block.height;
            if (upY + block.height < -8f * dp) break;
            drawBlock(canvas, block, upY, 1f, Double.NaN, false,
                slotJpAlpha(-step), slotRoAlpha(-step), h, topFade, bottomClear, bottomFade);
            upY -= gapInactive;
        }

        float downY = focalY + centerBlock.height + gapActive;
        int firstUpcoming = currentIndex < 0 ? 0 : currentIndex + 1;
        for (int step = 1; step <= 6; step++) {
            int idx = firstUpcoming + (step - 1);
            if (idx < 0 || idx >= lyrics.size()) break;
            if (downY > h - bottomClear + 6f * dp) break;
            LineBlock block = getBlock(idx, false, w);
            drawBlock(canvas, block, downY, 1f, Double.NaN, false,
                slotJpAlpha(step), slotRoAlpha(step), h, topFade, bottomClear, bottomFade);
            downY += block.height + gapInactive;
        }
    }

    /**
     * Exact per-slot staggered interpolation from transitionFromIndex (currentIndex - 1) to currentIndex:
     * zero frame-0 discontinuity, ripple stagger across upcoming rows, and smooth scale/alpha morph.
     */
    private void drawCascadingStage(Canvas canvas, int w, int h, float focalY,
                                    float gapActive, float gapInactive,
                                    float topFade, float bottomClear, float bottomFade) {
        int prevIdx = transitionFromIndex; // == currentIndex - 1
        float prevActiveH;
        if (prevIdx >= 0) {
            prevActiveH = getBlock(prevIdx, true, w).height;
        } else {
            prevActiveH = getSpecialBlock("♪", w, 2).height;
        }
        LineBlock currActive = getBlock(currentIndex, true, w);

        // 1. Outgoing past lines (slot -2 and slot -1).
        if (prevIdx >= 0) {
            LineBlock outBlock = getBlock(prevIdx, false, w);
            float prevY1 = focalY;
            float currY1 = focalY - gapInactive - outBlock.height;
            if (prevIdx - 1 >= 0) {
                LineBlock olderBlock = getBlock(prevIdx - 1, false, w);
                float prevY2 = focalY - gapInactive - olderBlock.height;
                float currY2 = currY1 - gapInactive - olderBlock.height;
                float e2 = cascadeProgress(animT, -2);
                float y2 = prevY2 + (currY2 - prevY2) * e2;
                int jpA2 = Math.round(slotJpAlpha(-1) + (slotJpAlpha(-2) - slotJpAlpha(-1)) * e2);
                int roA2 = Math.round(slotRoAlpha(-1) + (slotRoAlpha(-2) - slotRoAlpha(-1)) * e2);
                if (y2 + olderBlock.height > -8f * dp) {
                    drawBlock(canvas, olderBlock, y2, 1f, Double.NaN, false, jpA2, roA2, h, topFade, bottomClear, bottomFade);
                }
            }
            float e1 = cascadeProgress(animT, -1);
            float y1 = prevY1 + (currY1 - prevY1) * e1;
            float maxSafeScale = outBlock.maxRowWidth > 1f ? Math.min(1.24f, w / outBlock.maxRowWidth) : 1.24f;
            float scale1 = maxSafeScale + (1f - maxSafeScale) * e1;
            int jpA1 = Math.round(0xff + (slotJpAlpha(-1) - 0xff) * e1);
            int roA1 = Math.round(slotRoAlpha(0) + (slotRoAlpha(-1) - slotRoAlpha(0)) * e1);
            drawBlock(canvas, outBlock, y1, scale1, Double.NaN, false, jpA1, roA1, h, topFade, bottomClear, bottomFade);
        }

        // 2. Incoming active line (slot 0): rises from prevUpcomingY into focalY while scaling 0.79 -> 1.00.
        float prevY0 = focalY + prevActiveH + gapActive;
        float e0 = cascadeProgress(animT, 0);
        float y0 = prevY0 + (focalY - prevY0) * e0;
        float scale0 = 0.79f + 0.21f * e0;
        int jpA0 = Math.round(slotJpAlpha(1) + (0xff - slotJpAlpha(1)) * e0);
        int roA0 = Math.round(slotRoAlpha(1) + (slotRoAlpha(0) - slotRoAlpha(1)) * e0);
        drawBlock(canvas, currActive, y0, scale0, coverage, true, jpA0, roA0, h, topFade, bottomClear, bottomFade);

        // 3. Staggered ripple cascade for upcoming lines (slot 1 .. 6).
        float prevDownY = prevY0 + getBlock(currentIndex, false, w).height + gapInactive;
        float currDownY = focalY + currActive.height + gapActive;
        for (int slot = 1; slot <= 6; slot++) {
            int idx = currentIndex + slot;
            if (idx < 0 || idx >= lyrics.size()) break;
            LineBlock block = getBlock(idx, false, w);
            float es = cascadeProgress(animT, slot);
            float ys = prevDownY + (currDownY - prevDownY) * es;
            if (ys <= h - bottomClear + 10f * dp) {
                int fromJp = slotJpAlpha(slot + 1), toJp = slotJpAlpha(slot);
                int fromRo = slotRoAlpha(slot + 1), toRo = slotRoAlpha(slot);
                int jpAs = Math.round(fromJp + (toJp - fromJp) * es);
                int roAs = Math.round(fromRo + (toRo - fromRo) * es);
                drawBlock(canvas, block, ys, 1f, Double.NaN, false, jpAs, roAs, h, topFade, bottomClear, bottomFade);
            }
            prevDownY += block.height + gapInactive;
            currDownY += block.height + gapInactive;
            if (currDownY > h - bottomClear + 16f * dp && prevDownY > h - bottomClear + 16f * dp) break;
        }
    }

    private String statusMessage() {
        if ("loading".equals(status)) return vi ? "Đang tìm lời…" : "Finding lyrics…";
        if ("instrumental".equals(status)) return vi ? "Nhạc không lời" : "Instrumental";
        if ("unavailable".equals(status)) return vi ? "Chưa tải được lời bài hát" : "Lyrics unavailable";
        if ("missing".equals(status)) return vi ? "Chưa có lời đồng bộ" : "No synced lyrics found";
        return vi ? "Phát một bài để xem lời" : "Play a track to see lyrics";
    }

    private void drawBlock(Canvas canvas, LineBlock block, float blockY, float scale, double covered,
                           boolean activeStyle, int baseJpAlpha, int baseRoAlpha,
                           int viewH, float topFade, float bottomClear, float bottomFade) {
        if (block == null || block.rows.length == 0) return;
        Paint jpP = activeStyle ? activeJpPaint : inactiveJpPaint;
        Paint roP = activeStyle ? activeRoPaint : inactiveRoPaint;
        boolean timed = activeStyle && !Double.isNaN(covered) && !Double.isInfinite(covered);
        boolean scaled = Math.abs(scale - 1f) > 0.002f;

        if (scaled) {
            canvas.save();
            canvas.translate(0f, blockY);
            canvas.scale(scale, scale);
        }
        float originY = scaled ? 0f : blockY;
        float featherSpan = 22f * dp, rampHalf = 6.5f * dp;
        float roFeatherSpan = 14f * dp, roRampHalf = 4.5f * dp;
        float maxLiftPx = 2.8f * dp;

        for (VisualRow row : block.rows) {
            float screenTop = blockY + row.top * scale;
            float screenBottom = blockY + row.bottom * scale;
            if (screenBottom < -4f * dp || screenTop > viewH - bottomClear + 10f * dp) continue;

            float cy = (screenTop + screenBottom) * 0.5f;
            float edge = edgeWeight(cy, viewH, topFade, bottomClear, bottomFade);
            if (edge <= 0.01f) continue;

            float rowTop = originY + row.top;
            float rowBottom = originY + row.bottom;
            float jpBase = originY + row.jpBaseline;
            float roBase = originY + row.roBaseline;

            for (Cell cell : row.cells) {
                float jpX = cell.x + (cell.romaji.isEmpty() ? 0f : (cell.cellW - cell.jpW) * 0.5f);
                float roX = cell.x + (cell.cellW - cell.roW) * 0.5f;

                if (!timed) {
                    int jpA = clampAlpha(Math.round(baseJpAlpha * edge));
                    if (activeStyle && jpA > 205) {
                        activeGlowPaint.setColor((jpA << 24) | 0x00ffffff);
                        canvas.drawText(cell.surface, jpX, jpBase, activeGlowPaint);
                    } else {
                        jpP.setColor((jpA << 24) | 0x00ffffff);
                        canvas.drawText(cell.surface, jpX, jpBase, jpP);
                    }
                    if (!cell.romaji.isEmpty()) {
                        int roA = clampAlpha(Math.round(baseRoAlpha * edge));
                        roP.setColor((roA << 24) | 0x00ffffff);
                        canvas.drawText(cell.romaji, roX, roBase, roP);
                    }
                    continue;
                }

                // Mochy 60fps word-sync animation: float wave + oval spotlight + soft feathered gradient sweep.
                float liftFrac = wordLiftFraction(covered, cell.cpStart, cell.cpEnd);
                float liftY = -maxLiftPx * liftFrac;
                float cellJpBase = jpBase + liftY;
                float cellRoBase = roBase + liftY;

                int dimJpA = clampAlpha(Math.round(0x58 * edge * (baseJpAlpha / 255f)));
                int litJpA = clampAlpha(Math.round(baseJpAlpha * edge));
                int dimRoA = clampAlpha(Math.round(0x46 * edge * (baseJpAlpha / 255f)));
                int litRoA = clampAlpha(Math.round(0xf2 * edge * (baseJpAlpha / 255f)));

                if (covered <= cell.cpStart) {
                    jpP.setColor((dimJpA << 24) | 0x00ffffff);
                    canvas.drawText(cell.surface, jpX, cellJpBase, jpP);
                    if (!cell.romaji.isEmpty()) {
                        roP.setColor((dimRoA << 24) | 0x00ffffff);
                        canvas.drawText(cell.romaji, roX, cellRoBase, roP);
                    }
                } else if (covered >= cell.cpEnd) {
                    activeGlowPaint.setColor((litJpA << 24) | 0x00ffffff);
                    canvas.drawText(cell.surface, jpX, cellJpBase, activeGlowPaint);
                    if (!cell.romaji.isEmpty()) {
                        roP.setColor((litRoA << 24) | 0x00ffffff);
                        canvas.drawText(cell.romaji, roX, cellRoBase, roP);
                    }
                } else {
                    float u = (float) ((covered - cell.cpStart) / Math.max(1, cell.cpEnd - cell.cpStart));
                    u = Math.max(0f, Math.min(1f, u));

                    // 1. Soft oval spotlight hugging the active word + its romaji.
                    if (!cell.blank) {
                        float bell = (float) Math.sqrt(Math.max(0.0, Math.sin(Math.PI * u)));
                        int spotAlpha = clampAlpha(Math.round(255f * bell * edge * (baseJpAlpha / 255f)));
                        if (spotAlpha > 4) {
                            float spotCx = cell.x + cell.cellW * (0.36f + 0.28f * u);
                            float spotCy = (rowTop + rowBottom) * 0.50f + liftY;
                            float rx = Math.max(20f * dp, cell.cellW * 0.62f);
                            float ry = Math.max(26f * dp, (rowBottom - rowTop) * 0.72f);
                            spotlightMatrix.setScale(rx, ry);
                            spotlightMatrix.postTranslate(spotCx, spotCy);
                            spotlightShader.setLocalMatrix(spotlightMatrix);
                            spotlightPaint.setAlpha(spotAlpha);
                            ovalBounds.set(spotCx - rx, spotCy - ry, spotCx + rx, spotCy + ry);
                            canvas.drawOval(ovalBounds, spotlightPaint);
                        }
                    }

                    // 2. Soft feathered LinearGradient sweep across the Japanese glyphs.
                    float rawSplit = prefixWidth(cell.surface, covered - cell.cpStart, activeJpPaint);
                    float normSplit = cell.jpW > 0.5f ? (rawSplit / cell.jpW) : u;
                    float frontierX = (jpX - rampHalf) + normSplit * (cell.jpW + 2f * rampHalf);
                    sweepMatrix.setScale(featherSpan, 1f);
                    sweepMatrix.postTranslate(frontierX - featherSpan * 0.5f, 0f);
                    jpSweepShader.setLocalMatrix(sweepMatrix);
                    activeSweepPaint.setColor(0xffffffff);
                    activeSweepPaint.setAlpha(litJpA);
                    canvas.drawText(cell.surface, jpX, cellJpBase, activeSweepPaint);

                    // 3. Synchronized feathered sweep across the aligned romaji underneath.
                    if (!cell.romaji.isEmpty()) {
                        float roFrontierX = (roX - roRampHalf) + u * (cell.roW + 2f * roRampHalf);
                        sweepMatrix.setScale(roFeatherSpan, 1f);
                        sweepMatrix.postTranslate(roFrontierX - roFeatherSpan * 0.5f, 0f);
                        roSweepShader.setLocalMatrix(sweepMatrix);
                        activeRoSweepPaint.setColor(0xffffffff);
                        activeRoSweepPaint.setAlpha(litJpA);
                        canvas.drawText(cell.romaji, roX, cellRoBase, activeRoSweepPaint);
                    }
                }
            }
        }
        if (scaled) {
            canvas.restore();
        }
    }

    static float prefixWidth(String surface, double localCodePoints, Paint paint) {
        if (localCodePoints <= 0 || surface.isEmpty()) return 0f;
        int totalCp = surface.codePointCount(0, surface.length());
        double clamped = Math.min(localCodePoints, totalCp);
        int whole = (int) clamped;
        int endOffset = surface.offsetByCodePoints(0, whole);
        float width = endOffset > 0 ? paint.measureText(surface, 0, endOffset) : 0f;
        float fraction = (float) (clamped - whole);
        if (fraction > 0f && endOffset < surface.length()) {
            int nextOffset = surface.offsetByCodePoints(endOffset, 1);
            width += paint.measureText(surface, endOffset, nextOffset) * fraction;
        }
        return width;
    }

    private static float edgeWeight(float cy, int viewH, float topFade, float bottomClear, float bottomFade) {
        float wTop = cy >= topFade ? 1f : smootherstep(0f, topFade, cy);
        float bottomLimit = viewH - bottomClear;
        float bottomStart = bottomLimit - bottomFade;
        float wBot = cy <= bottomStart ? 1f : (1f - smootherstep(bottomStart, bottomLimit, cy));
        return Math.max(0f, Math.min(1f, wTop * wBot));
    }

    private static float smootherstep(float edge0, float edge1, float x) {
        if (edge1 <= edge0) return x >= edge1 ? 1f : 0f;
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * t * (t * (t * 6f - 15f) + 10f);
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        if (edge1 <= edge0) return x >= edge1 ? 1f : 0f;
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }

    private static int clampAlpha(int alpha) {
        return Math.max(0, Math.min(255, alpha));
    }

    static LineBlock measureBlock(String text, Lyrics.Ruby[] rubies, Paint jpPaint, Paint roPaint,
                                  float minRoGap, float rubyGap, float rowGap,
                                  int maxWidth, int maxRows, String emptyFallback) {
        String source = (text == null || text.isEmpty()) ? (emptyFallback == null ? "" : emptyFallback) : text;
        if (source.isEmpty() || maxWidth <= 0) return new LineBlock(new VisualRow[0], Math.max(8f, rubyGap * 5f), 0f);

        boolean useRubies = text != null && !text.isEmpty() && rubies != null && rubies.length > 0;
        ArrayList<Slice> slices = new ArrayList<>();
        if (useRubies) {
            int cp = 0;
            for (Lyrics.Ruby r : rubies) {
                if ("\n".equals(r.surface)) {
                    slices.add(new Slice("\n", "", cp, cp + 1, true));
                    cp += 1;
                } else {
                    int count = r.surface.codePointCount(0, r.surface.length());
                    appendBoundedSlices(slices, r.surface, r.romaji == null ? "" : r.romaji, cp, jpPaint, maxWidth);
                    cp += count;
                }
            }
        } else {
            tokenizePlainSlices(slices, source, jpPaint, maxWidth);
        }

        Paint.FontMetrics jpFm = jpPaint.getFontMetrics();
        Paint.FontMetrics roFm = roPaint.getFontMetrics();
        float jpAscent = -jpFm.ascent, jpH = jpAscent + jpFm.descent;
        float roAscent = -roFm.ascent, roH = roAscent + roFm.descent;

        ArrayList<VisualRow> rows = new ArrayList<>(2);
        ArrayList<Cell> rowCells = new ArrayList<>(8);
        float x = 0f, y = 0f, maxRowW = 0f;
        boolean rowHasRomaji = false;

        for (int i = 0; i < slices.size(); i++) {
            Slice s = slices.get(i);
            if (s.newline) {
                maxRowW = Math.max(maxRowW, x);
                y = flushRow(rows, rowCells, y, x, jpAscent, jpH, roAscent, roH, rubyGap, rowGap, rowHasRomaji, true);
                x = 0f;
                rowHasRomaji = false;
                if (rows.size() >= maxRows) break;
                continue;
            }
            if (rowCells.isEmpty() && !rows.isEmpty() && s.romaji.isEmpty() && s.surface.trim().isEmpty()) {
                continue;
            }
            float jpW = jpPaint.measureText(s.surface);
            float roW = s.romaji.isEmpty() ? 0f : roPaint.measureText(s.romaji);
            float cellW = s.romaji.isEmpty() ? jpW : Math.max(jpW, roW + minRoGap);

            if (!rowCells.isEmpty() && x + cellW > maxWidth) {
                maxRowW = Math.max(maxRowW, x);
                y = flushRow(rows, rowCells, y, x, jpAscent, jpH, roAscent, roH, rubyGap, rowGap, rowHasRomaji, false);
                x = 0f;
                rowHasRomaji = false;
                if (rows.size() >= maxRows) break;
                if (s.romaji.isEmpty() && s.surface.trim().isEmpty()) continue;
            }

            rowCells.add(new Cell(x, cellW, jpW, roW, s.cpStart, s.cpEnd, s.surface, s.romaji));
            if (!s.romaji.isEmpty()) rowHasRomaji = true;
            x += cellW;
        }
        if (!rowCells.isEmpty() && rows.size() < maxRows) {
            maxRowW = Math.max(maxRowW, x);
            y = flushRow(rows, rowCells, y, x, jpAscent, jpH, roAscent, roH, rubyGap, rowGap, rowHasRomaji, false);
        }
        float totalHeight = rows.isEmpty() ? jpH : rows.get(rows.size() - 1).bottom;
        return new LineBlock(rows.toArray(new VisualRow[0]), totalHeight, maxRowW);
    }

    private static float flushRow(ArrayList<VisualRow> rows, ArrayList<Cell> rowCells, float y, float rowWidth,
                                  float jpAscent, float jpH, float roAscent, float roH,
                                  float rubyGap, float rowGap, boolean hasRomaji, boolean allowEmpty) {
        if (rowCells.isEmpty()) {
            if (!allowEmpty) return y;
            float bottom = y + jpH * 0.65f;
            rows.add(new VisualRow(new Cell[0], y, y + jpAscent * 0.65f, 0f, bottom, 0f, false));
            return bottom + rowGap;
        }
        float jpBase = y + jpAscent;
        float roBase = hasRomaji ? (y + jpH + rubyGap + roAscent) : 0f;
        float bottom = hasRomaji ? (y + jpH + rubyGap + roH) : (y + jpH);
        rows.add(new VisualRow(rowCells.toArray(new Cell[0]), y, jpBase, roBase, bottom, rowWidth, hasRomaji));
        rowCells.clear();
        return bottom + rowGap;
    }

    private static void tokenizePlainSlices(ArrayList<Slice> out, String source, Paint jpPaint, int maxWidth) {
        int i = 0, n = source.length(), cp = 0;
        while (i < n) {
            char c = source.charAt(i);
            if (c == '\n') {
                out.add(new Slice("\n", "", cp, cp + 1, true));
                i++; cp++;
                continue;
            }
            boolean space = Character.isWhitespace(c);
            int start = i;
            while (i < n && source.charAt(i) != '\n' && Character.isWhitespace(source.charAt(i)) == space) {
                i++;
            }
            String token = source.substring(start, i);
            int count = token.codePointCount(0, token.length());
            appendBoundedSlices(out, token, "", cp, jpPaint, maxWidth);
            cp += count;
        }
    }

    private static void appendBoundedSlices(ArrayList<Slice> out, String surface, String romaji,
                                            int cpBase, Paint jpPaint, int maxWidth) {
        if (surface.isEmpty()) return;
        int totalCp = surface.codePointCount(0, surface.length());
        if (maxWidth <= 0 || jpPaint.measureText(surface) <= maxWidth || totalCp <= 1) {
            out.add(new Slice(surface, romaji, cpBase, cpBase + totalCp, false));
            return;
        }
        int offset = 0, cpOffset = 0;
        while (offset < surface.length()) {
            int chunkEnd = offset, chunkCp = 0;
            while (chunkEnd < surface.length()) {
                int next = surface.offsetByCodePoints(chunkEnd, 1);
                if (chunkEnd > offset && jpPaint.measureText(surface, offset, next) > maxWidth) break;
                chunkEnd = next;
                chunkCp++;
            }
            String sub = surface.substring(offset, chunkEnd);
            out.add(new Slice(sub, offset == 0 ? romaji : "", cpBase + cpOffset, cpBase + cpOffset + chunkCp, false));
            offset = chunkEnd;
            cpOffset += chunkCp;
        }
    }

    /** Scrollable unsynced lyrics view preserving word-aligned ruby romaji on every Japanese line. */
    static final class Plain extends View {
        private final float dp, sp;
        private final Paint jpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint roPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private Typeface appliedBold, appliedRegular;
        private Lyrics lyrics;
        private LineBlock[] blocks;
        private float[] offsets;
        private int cachedWidth;
        private int contentHeight;

        Plain(Context context, float dp) {
            super(context);
            this.dp = dp;
            this.sp = context.getResources().getDisplayMetrics().scaledDensity;
            jpPaint.setTextSize(18.0f * sp);
            roPaint.setTextSize(8.6f * sp);
            refreshTypeface();
        }

        void refreshTypeface() {
            Typeface bold = FontChoice.style(getContext(), true);
            Typeface regular = FontChoice.style(getContext(), false);
            if (bold == appliedBold && regular == appliedRegular) return;
            appliedBold = bold;
            appliedRegular = regular;
            jpPaint.setTypeface(bold);
            roPaint.setTypeface(regular);
            blocks = null;
            requestLayout();
            invalidate();
        }

        void setLyrics(Lyrics data) {
            lyrics = data != null && !data.isSynced() ? data : null;
            blocks = null;
            offsets = null;
            cachedWidth = 0;
            contentHeight = 0;
            requestLayout();
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            if (w <= 0) w = Math.round(320 * dp);
            refreshTypeface();
            if (lyrics == null || lyrics.size() == 0) {
                setMeasuredDimension(w, 0);
                return;
            }
            int innerW = Math.max(40, w - Math.round(8 * dp));
            if (blocks == null || cachedWidth != innerW) {
                cachedWidth = innerW;
                blocks = new LineBlock[lyrics.size()];
                offsets = new float[lyrics.size()];
                float y = Math.round(20 * dp);
                float lineGap = 15f * dp;
                for (int i = 0; i < lyrics.size(); i++) {
                    String line = lyrics.at(i);
                    LineBlock b = line.isEmpty()
                        ? new LineBlock(new VisualRow[0], 14f * dp, 0f)
                        : measureBlock(line, lyrics.rubiesAt(i), jpPaint, roPaint, 5f * dp, 2f * dp, 6f * dp, innerW, 8, null);
                    blocks[i] = b;
                    offsets[i] = y;
                    y += b.height + lineGap;
                }
                contentHeight = Math.round(y + 28f * dp);
            }
            setMeasuredDimension(w, contentHeight);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (lyrics == null || blocks == null) return;
            jpPaint.setColor(0xe6ffffff);
            roPaint.setColor(0x98ffffff);
            for (int i = 0; i < blocks.length; i++) {
                LineBlock block = blocks[i];
                if (block == null) continue;
                float blockY = offsets[i];
                for (VisualRow row : block.rows) {
                    float jpBase = blockY + row.jpBaseline;
                    float roBase = blockY + row.roBaseline;
                    for (Cell cell : row.cells) {
                        float jpX = cell.x + (cell.romaji.isEmpty() ? 0f : (cell.cellW - cell.jpW) * 0.5f);
                        canvas.drawText(cell.surface, jpX, jpBase, jpPaint);
                        if (!cell.romaji.isEmpty()) {
                            float roX = cell.x + (cell.cellW - cell.roW) * 0.5f;
                            canvas.drawText(cell.romaji, roX, roBase, roPaint);
                        }
                    }
                }
            }
        }
    }
}
