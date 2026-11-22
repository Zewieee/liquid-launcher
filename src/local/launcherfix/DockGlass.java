package local.launcherfix;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import com.gathin.tvhome.LiquidGlassRoot;

/** Transparent clear-glass style shared by the app and clock docks. */
public final class DockGlass {
    private static final RectF VIDEO_BOUNDS = new RectF();
    private DockGlass() {}

    public static boolean drawVideoClock(Canvas canvas, View owner, Rect bounds,
                                         LiquidGlassRoot.Surface surface) {
        if (!ControlPanelHelper.TAG_CLOCK_PILL.equals(owner.getTag())
                || !LiveWallpaper.hasVideoVisual(owner.getContext())) return false;
        // Software lens captures cannot include TextureView video; keep the clock transparent.
        if (surface.root != null || surface.front != null || surface.spare != null)
            LiquidGlassRoot.releaseSurface(surface);
        VIDEO_BOUNDS.set(bounds);
        return draw(canvas, VIDEO_BOUNDS, VIDEO_BOUNDS.height() / 2f,
            owner.getResources().getDisplayMetrics().density, 1);
    }

    public static boolean draw(Canvas canvas, RectF bounds, float radius, float density, int style) {
        return style == 1;
    }

    public static Drawable clock(float density) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(Color.TRANSPARENT);
        result.setCornerRadius(40f * density);
        return result;
    }
}
