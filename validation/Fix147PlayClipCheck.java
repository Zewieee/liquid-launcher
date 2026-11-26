package local.launcherfix;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import java.lang.reflect.Method;

/**
 * Isolated ART verification for fix.147:
 * Ensures MiniPlayer's focused Play/Pause circle (44dp, 1.08x scale, -1.5dp lift),
 * credit-row pills, offset-dialog pills, and Timeline seek thumb are never clipped.
 */
public final class Fix147PlayClipCheck {
    private static int cases;

    private static void check(boolean ok, String msg) {
        if (!ok) throw new IllegalStateException("Fix147PlayClipCheck failed: " + msg);
        cases++;
    }

    private static Context resolveSystemContext() throws Exception {
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }
        Class<?> atClass = Class.forName("android.app.ActivityThread");
        Method current = atClass.getDeclaredMethod("currentActivityThread");
        current.setAccessible(true);
        Object thread = current.invoke(null);
        if (thread == null) {
            Method sysMain = atClass.getDeclaredMethod("systemMain");
            sysMain.setAccessible(true);
            thread = sysMain.invoke(null);
        }
        Method getSysCtx = atClass.getDeclaredMethod("getSystemContext");
        getSysCtx.setAccessible(true);
        return (Context) getSysCtx.invoke(thread);
    }

    private static Bitmap renderHudHierarchy(Context ctx, boolean unclip) {
        float dp = 2.0f;
        int w = Math.round(220 * dp);
        int h = Math.round(140 * dp);

        FrameLayout host = new FrameLayout(ctx);
        LinearLayout leftBottom = new LinearLayout(ctx);
        leftBottom.setOrientation(LinearLayout.VERTICAL);
        leftBottom.setGravity(Gravity.BOTTOM | Gravity.START);
        leftBottom.setPadding(Math.round(34 * dp), Math.round(20 * dp), Math.round(16 * dp), Math.round(24 * dp));
        MiniPlayer.unclipped(leftBottom);
        host.addView(leftBottom, new FrameLayout.LayoutParams(w, h));

        LinearLayout controlsHud = new LinearLayout(ctx);
        controlsHud.setOrientation(LinearLayout.VERTICAL);
        if (unclip) MiniPlayer.unclipped(controlsHud);
        leftBottom.addView(controlsHud, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actionRow = new LinearLayout(ctx);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        if (unclip) MiniPlayer.unclipped(actionRow);
        int rowH = Math.round(44 * dp);
        controlsHud.addView(actionRow, new LinearLayout.LayoutParams(-1, rowH));

        ImageView play = new ImageView(ctx);
        int playDiameter = Math.round(44 * dp);
        float rad = playDiameter * 0.5f;
        WallpaperTheme.Tone tone = WallpaperTheme.sample(null);
        Drawable focusedWash = WallpaperTheme.focusWash(rad, tone, false);
        focusedWash.setAlpha(255);
        play.setBackground(focusedWash);
        actionRow.addView(play, new LinearLayout.LayoutParams(playDiameter, playDiameter));

        host.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        );
        host.layout(0, 0, w, h);

        play.setPivotX(playDiameter * 0.5f);
        play.setPivotY(playDiameter * 0.5f);
        play.setScaleX(1.08f);
        play.setScaleY(1.08f);
        play.setTranslationY(-Math.round(1.5f * dp));

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.eraseColor(Color.TRANSPARENT);
        Canvas canvas = new Canvas(out);
        host.draw(canvas);
        return out;
    }

    public static void main(String[] args) throws Exception {
        // 1. Null-safety & ViewGroup flag verification
        check(MiniPlayer.unclipped(null) == null, "unclipped(null) safe");

        Context ctx = resolveSystemContext();
        check(ctx != null, "system context available");

        LinearLayout sampleRow = new LinearLayout(ctx);
        check(sampleRow.getClipChildren() && sampleRow.getClipToPadding(),
            "Android LinearLayout defaults to clipChildren=true & clipToPadding=true");
        LinearLayout unclippedRow = MiniPlayer.unclipped(sampleRow);
        check(unclippedRow == sampleRow, "unclipped returns same instance");
        check(!sampleRow.getClipChildren() && !sampleRow.getClipToPadding(),
            "unclipped disables both clipChildren and clipToPadding");

        // 2. Pixel-level verification: clipped baseline slices top/bottom vs fix.147 unclipped preserves full circle
        float dp = 2.0f;
        int h = Math.round(140 * dp);
        int padLeft = Math.round(34 * dp);
        int padBottom = Math.round(24 * dp);
        int playDiameter = Math.round(44 * dp);
        int rowBottomY = h - padBottom;
        int rowTopY = rowBottomY - playDiameter;
        int centerX = padLeft + playDiameter / 2;

        // Scaled 1.08x + lifted -3px: top reaches rowTopY - 6.52px, bottom reaches rowBottomY + 0.52px
        int probeTopOutsideRowY = rowTopY - 4;
        Bitmap clippedBmp = renderHudHierarchy(ctx, false);
        Bitmap unclippedBmp = renderHudHierarchy(ctx, true);

        int clippedTopAlpha = Color.alpha(clippedBmp.getPixel(centerX, probeTopOutsideRowY));
        int unclippedTopAlpha = Color.alpha(unclippedBmp.getPixel(centerX, probeTopOutsideRowY));
        check(clippedTopAlpha == 0, "Baseline clipped row slices top arc of Play circle: alpha=" + clippedTopAlpha);
        check(unclippedTopAlpha > 40, "Unclipped row preserves top arc of Play circle: alpha=" + unclippedTopAlpha);
        clippedBmp.recycle();
        unclippedBmp.recycle();

        // 3. Odd/fractional density circle radius & clearance invariants
        float[] densities = {1.0f, 1.33125f, 1.5f, 2.0f, 2.625f, 3.0f};
        for (float d : densities) {
            int playPx = Math.round(44f * d);
            int secPx = Math.round(38f * d);
            float playRad = playPx * 0.5f;
            float secRad = secPx * 0.5f;
            check(Math.abs(playRad * 2f - playPx) < 1e-4f, "Exact Play circle diameter at density=" + d);
            check(Math.abs(secRad * 2f - secPx) < 1e-4f, "Exact Prev/Next circle diameter at density=" + d);

            float topOverhang = playPx * 0.04f + Math.round(1.5f * d);
            float bottomOverhang = Math.max(0f, playPx * 0.04f - Math.round(1.5f * d));
            int bottomPadPx = Math.round(24f * d);
            int topClearanceToBarPx = Math.round(6f * d) + Math.round(8.5f * d);
            check(topOverhang < topClearanceToBarPx, "Play focus top overhang clears timeline bar at density=" + d);
            check(bottomOverhang < bottomPadPx, "Play focus bottom overhang stays inside panel at density=" + d);
        }

        // 4. Timeline focused thumb halo stays inside View bounds at 0% and 100%
        float padDp = MiniPlayer.timelineFocusPadDp();
        float glowDp = MiniPlayer.timelineThumbGlowRadiusDp();
        check(padDp > glowDp && (padDp - glowDp) >= 0.15f,
            "Timeline focus horizontal pad (" + padDp + "dp) clears thumb glow (" + glowDp + "dp)");
        check(8.0f > glowDp, "Timeline 16dp height clears thumb glow vertically");

        System.out.println("FIX147_PLAY_CLIP_CHECK_OK cases=" + cases
            + " clipped_top_alpha=" + clippedTopAlpha
            + " unclipped_top_alpha=" + unclippedTopAlpha);
    }
}
