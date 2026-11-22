package local.launcherfix;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;

/**
 * Isolated ART verification for fix.143 Liquid Glass music visualizer
 * (Dock 4-bar capsule + Popup 28-bar silk aura ribbon & floating crystal capsules).
 */
public final class Fix143VisualizerCheck {
    static int checks;

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) throw new AssertionError(msg);
    }

    static long sumAlpha(Bitmap bmp, int x0, int x1) {
        long sum = 0;
        int h = bmp.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = x0; x < x1; x++) {
                sum += (bmp.getPixel(x, y) >>> 24) & 255;
            }
        }
        return sum;
    }

    public static void main(String[] args) {
        // 1. Mode geometry & edge tapering invariants
        MusicVisualizerView.Engine dock = new MusicVisualizerView.Engine(MusicVisualizerView.MODE_DOCK);
        MusicVisualizerView.Engine stage = new MusicVisualizerView.Engine(MusicVisualizerView.MODE_STAGE);
        check(dock.count == MusicVisualizerView.DOCK_BARS && dock.count == 4, "Dock mode has 4 bars");
        check(stage.count == MusicVisualizerView.STAGE_BARS && stage.count == 28, "Stage mode has 28 bars");
        check(stage.window[14] > stage.window[0] * 2.0f && stage.window[13] > stage.window[27] * 2.0f,
            "Stage bell window tapers edges smoothly");
        check(Math.abs(stage.window[0] - stage.window[27]) < 0.001f, "Stage bell window symmetric");

        // 2. Smooth 60fps simulation (Dock & Stage): bounded per-frame deltas, dynamic variety, dt clamping
        dock.setTrackSeed("track-alpha");
        stage.setTrackSeed("track-alpha");
        float[] dockMin = new float[dock.count];
        float[] dockMax = new float[dock.count];
        for (int i = 0; i < dock.count; i++) { dockMin[i] = 1f; dockMax[i] = 0f; }
        float maxDockDelta = 0f;
        float maxStageDelta = 0f;
        float maxStageNeighborDiff = 0f;
        float[] prevDock = dock.levels.clone();
        float[] prevStage = stage.levels.clone();

        for (int frame = 0; frame < 180; frame++) { // 3 seconds at ~60fps
            boolean dockMore = dock.step(16L, true);
            boolean stageMore = stage.step(16L, true);
            check(dockMore && stageMore, "Active playback continues scheduling frames");
            for (int i = 0; i < dock.count; i++) {
                float v = dock.levels[i];
                check(!Float.isNaN(v) && !Float.isInfinite(v) && v >= 0f && v <= 1f, "Dock level valid");
                if (frame >= 20) {
                    dockMin[i] = Math.min(dockMin[i], v);
                    dockMax[i] = Math.max(dockMax[i], v);
                }
                maxDockDelta = Math.max(maxDockDelta, Math.abs(v - prevDock[i]));
                prevDock[i] = v;
            }
            for (int i = 0; i < stage.count; i++) {
                float v = stage.levels[i];
                check(!Float.isNaN(v) && !Float.isInfinite(v) && v >= 0f && v <= 1f, "Stage level valid");
                maxStageDelta = Math.max(maxStageDelta, Math.abs(v - prevStage[i]));
                prevStage[i] = v;
                if (i > 0) {
                    maxStageNeighborDiff = Math.max(maxStageNeighborDiff, Math.abs(v - stage.levels[i - 1]));
                }
            }
        }
        check(dock.activity > 0.99f && stage.activity > 0.99f, "Activity reaches steady playing state");
        check(maxDockDelta < 0.22f, "Dock 60fps per-frame delta stays silky smooth: " + maxDockDelta);
        check(maxStageDelta < 0.20f, "Stage 60fps per-frame delta stays silky smooth: " + maxStageDelta);
        check(maxStageNeighborDiff < 0.22f, "Stage spatial neighbor coupling prevents spiky jitter: " + maxStageNeighborDiff);
        for (int i = 0; i < dock.count; i++) {
            check((dockMax[i] - dockMin[i]) > 0.12f,
                "Dock bar " + i + " moves dynamically: span=" + (dockMax[i] - dockMin[i]));
        }

        // 3. Large frame-drop dt clamping (5000ms stall must clamp to 50ms, never teleport!)
        float[] beforeStall = stage.levels.clone();
        stage.step(5000L, true);
        float maxStallJump = 0f;
        for (int i = 0; i < stage.count; i++) {
            maxStallJump = Math.max(maxStallJump, Math.abs(stage.levels[i] - beforeStall[i]));
        }
        check(maxStallJump < 0.30f, "dt clamped to 50ms prevents jumps after stall: jump=" + maxStallJump);

        // 4. Distinct track seed gives distinct wave personality
        MusicVisualizerView.Engine seedA = new MusicVisualizerView.Engine(MusicVisualizerView.MODE_STAGE);
        MusicVisualizerView.Engine seedB = new MusicVisualizerView.Engine(MusicVisualizerView.MODE_STAGE);
        seedA.setTrackSeed("YOASOBI-Idol");
        seedB.setTrackSeed("yuiko-InaiSekai");
        for (int f = 0; f < 45; f++) {
            seedA.step(16L, true);
            seedB.step(16L, true);
        }
        float seedDiff = 0f;
        for (int i = 0; i < seedA.count; i++) seedDiff += Math.abs(seedA.levels[i] - seedB.levels[i]);
        check(seedDiff > 0.35f, "Different tracks produce distinct wave phrasing: diff=" + seedDiff);

        // 5. Smooth play -> pause settle-to-rest & deterministic stop (0% idle CPU)
        float prevAct = stage.activity;
        int settleFrames = 0;
        boolean running = true;
        while (running && settleFrames < 200) {
            running = stage.step(16L, false);
            settleFrames++;
            check(stage.activity <= prevAct + 1e-4f, "Pause activity decays monotonically");
            prevAct = stage.activity;
        }
        check(!running, "Stage stops scheduling frames once settled after pause");
        check(settleFrames >= 25 && settleFrames <= 140,
            "Pause settle takes smooth ~0.4-2.2s window: frames=" + settleFrames);
        check(stage.activity == 0f && !stage.isSettling(), "Settled stage reaches clean zero activity");
        for (int i = 0; i < stage.count; i++) {
            check(Math.abs(stage.levels[i] - stage.rest[i]) < 1e-5f, "Stage rests cleanly at rest pose");
        }

        // Same pause settle check for Dock
        int dockSettle = 0;
        boolean dockRun = true;
        while (dockRun && dockSettle < 200) {
            dockRun = dock.step(16L, false);
            dockSettle++;
        }
        check(!dockRun && dock.activity == 0f && dockSettle >= 20 && dockSettle <= 140,
            "Dock settles cleanly to rest and stops: frames=" + dockSettle);

        // 6. Luminous accent color calibration (no violet/lavender, neutral -> crystal, smooth color morph)
        int neutral = MusicVisualizerView.Engine.luminousAccent(0xff404040);
        check(neutral == MusicVisualizerView.DEFAULT_ACCENT, "Neutral grey maps to crystal accent");

        int violetIn = Color.HSVToColor(255, new float[]{295f, 0.50f, 0.70f});
        int violetOut = MusicVisualizerView.Engine.luminousAccent(violetIn);
        float[] outHsv = new float[3];
        Color.colorToHSV(violetOut, outHsv);
        check(outHsv[0] <= 268f || outHsv[0] >= 332f,
            "Luminous accent preserves Taste-Skill hue calibration (no violet): hue=" + outHsv[0]);
        check(outHsv[2] >= 0.94f && outHsv[1] >= 0.21f && outHsv[1] <= 0.47f,
            "Luminous accent stays vibrant crystal without neon harshness: s=" + outHsv[1] + " v=" + outHsv[2]);

        MusicVisualizerView.Engine morph = new MusicVisualizerView.Engine(MusicVisualizerView.MODE_STAGE);
        morph.setAccentColor(0xff1aa3d8, true);
        int startRgb = morph.currentAccentRgb();
        morph.setAccentColor(0xffe07b24, false);
        check(morph.isColorMorphing(), "Non-immediate tone change starts smooth morph");
        morph.step(16L, true);
        int midRgb = morph.currentAccentRgb();
        check(midRgb != startRgb, "Color morph moves smoothly on first frame");
        for (int f = 0; f < 90; f++) morph.step(16L, true);
        check(!morph.isColorMorphing(), "Color morph finishes cleanly within ~1.5s");
        int endRgb = morph.currentAccentRgb();
        check(((endRgb >> 16) & 255) > ((startRgb >> 16) & 255), "Warm amber target increases red channel");

        // 7. Reduced-motion / snapState immediate pose & color
        MusicVisualizerView.Engine snapped = new MusicVisualizerView.Engine(MusicVisualizerView.MODE_DOCK);
        snapped.setAccentColor(0xff20b880, false);
        snapped.snapState(true);
        check(snapped.activity == 1f && !snapped.isColorMorphing() && snapped.levels[1] == snapped.staticPose[1],
            "snapState(true) lands immediately on static playing pose and target tint");
        snapped.snapState(false);
        check(snapped.activity == 0f && snapped.levels[1] == snapped.rest[1],
            "snapState(false) lands immediately on resting pose");

        // 8. Real Skia Canvas rendering verification (Dock 32x26px & Stage 776x56px) + zero shader churn
        MusicVisualizerView.Engine dockRenderEng = new MusicVisualizerView.Engine(MusicVisualizerView.MODE_DOCK);
        MusicVisualizerView.Renderer dockRenderer = new MusicVisualizerView.Renderer(2.0f, dockRenderEng);
        Bitmap dockBmp = Bitmap.createBitmap(32, 26, Bitmap.Config.ARGB_8888);
        Canvas dockCanvas = new Canvas(dockBmp);
        dockRenderEng.snapState(false);
        dockRenderer.draw(dockCanvas, 32, 26);
        long dockPausedAlpha = sumAlpha(dockBmp, 0, 32);

        dockBmp.eraseColor(0);
        dockRenderEng.setAccentColor(0xff2da8e8, true);
        for (int f = 0; f < 30; f++) dockRenderEng.step(16L, true);
        dockRenderer.draw(dockCanvas, 32, 26);
        long dockPlayingAlpha = sumAlpha(dockBmp, 0, 32);
        check(dockPausedAlpha > 2000L && dockPlayingAlpha > dockPausedAlpha * 1.4,
            "Dock renders calm paused & vivid playing capsules: paused=" + dockPausedAlpha + " playing=" + dockPlayingAlpha);

        LinearGradient firstDockShader = dockRenderer.barShader;
        int buildsAfterWarm = dockRenderer.shaderRebuilds;
        for (int f = 0; f < 120; f++) {
            dockRenderEng.step(16L, true);
            dockBmp.eraseColor(0);
            dockRenderer.draw(dockCanvas, 32, 26);
        }
        check(dockRenderer.barShader == firstDockShader && dockRenderer.shaderRebuilds == buildsAfterWarm,
            "Dock allocates ZERO new Shaders across 120 steady 60fps frames");
        dockBmp.recycle();

        MusicVisualizerView.Engine stageRenderEng = new MusicVisualizerView.Engine(MusicVisualizerView.MODE_STAGE);
        MusicVisualizerView.Renderer stageRenderer = new MusicVisualizerView.Renderer(2.0f, stageRenderEng);
        Bitmap stageBmp = Bitmap.createBitmap(776, 56, Bitmap.Config.ARGB_8888);
        Canvas stageCanvas = new Canvas(stageBmp);
        stageRenderEng.setAccentColor(0xff2da8e8, true);
        stageRenderEng.snapState(false);
        stageRenderer.draw(stageCanvas, 776, 56);
        long stagePausedAlpha = sumAlpha(stageBmp, 0, 776);

        stageBmp.eraseColor(0);
        for (int f = 0; f < 45; f++) stageRenderEng.step(16L, true);
        stageRenderer.draw(stageCanvas, 776, 56);
        long stagePlayingAlpha = sumAlpha(stageBmp, 0, 776);
        long leftEdgeAlpha = sumAlpha(stageBmp, 0, 64);
        long centerAlpha = sumAlpha(stageBmp, 356, 420);
        long rightEdgeAlpha = sumAlpha(stageBmp, 712, 776);
        check(stagePausedAlpha > 15000L && stagePlayingAlpha > stagePausedAlpha * 1.6,
            "Stage renders serene paused & rich playing dual-layer waveform: paused=" + stagePausedAlpha + " playing=" + stagePlayingAlpha);
        check(centerAlpha > leftEdgeAlpha * 1.35 && centerAlpha > rightEdgeAlpha * 1.35,
            "Stage edges taper gracefully into glass: left=" + leftEdgeAlpha + " center=" + centerAlpha + " right=" + rightEdgeAlpha);

        LinearGradient firstStageBar = stageRenderer.barShader;
        LinearGradient firstStageRibbon = stageRenderer.ribbonShader;
        int stageBuilds = stageRenderer.shaderRebuilds;
        for (int f = 0; f < 120; f++) {
            stageRenderEng.step(16L, true);
            stageBmp.eraseColor(0);
            stageRenderer.draw(stageCanvas, 776, 56);
        }
        check(stageRenderer.barShader == firstStageBar && stageRenderer.ribbonShader == firstStageRibbon
            && stageRenderer.shaderRebuilds == stageBuilds,
            "Stage allocates ZERO new Shaders across 120 steady 60fps frames");
        stageBmp.recycle();

        System.out.println("FIX143_VISUALIZER_CHECK_OK cases=" + checks
            + " dock_settle_frames=" + dockSettle
            + " stage_settle_frames=" + settleFrames
            + " max_stage_step=" + String.format(java.util.Locale.US, "%.3f", maxStageDelta)
            + " stage_center_vs_edge=" + centerAlpha + "/" + leftEdgeAlpha);
    }
}
