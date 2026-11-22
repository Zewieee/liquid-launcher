package local.launcherfix;

import android.app.Activity;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.ImageView;
import com.gathin.tvhome.ControlCenter;
import com.gathin.tvhome.MainActivity;
import com.gathin.tvhome.WallpaperPlayer;

/** Borrows a still image or a single video frame; never recycles a displayed bitmap. */
public final class WallpaperPreview {
    private WallpaperPreview() {}

    public static Bitmap current(ControlCenter center) {
        if (center == null || !(center.activity instanceof MainActivity)) return null;
        if (LiveWallpaperPicker.isEnabled(center.activity)) return LiveWallpaper.preview(center.activity);
        WallpaperPlayer player = ((MainActivity) center.activity).wallpaperPlayer;
        return player != null && player.enabled && player.image != null
            && player.image.getVisibility() == View.VISIBLE && player.current != null
            && !player.current.isRecycled() ? player.current : null;
    }

    public static void bind(ImageView image, ControlCenter center) {
        image.setTag("cc_wallpaper_preview_image");
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (image.isAttachedToWindow()) {
            image.setImageBitmap(current(center));
        }
        image.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View view) { image.setImageBitmap(current(center)); }
            public void onViewDetachedFromWindow(View view) { image.setImageDrawable(null); }
        });
    }

    public static void update(ControlCenter center, Bitmap bitmap) {
        if (center == null || center.panel == null) return;
        View view = center.panel.findViewWithTag("cc_wallpaper_preview_image");
        if (!(view instanceof ImageView)) return;
        if (LiveWallpaperPicker.isEnabled(center.activity)) bitmap = current(center);
        ((ImageView) view).setImageBitmap(bitmap != null && !bitmap.isRecycled() ? bitmap : null);
    }

    public static void videoChanged(Activity activity) {
        ControlCenter center = ControlPanelHelper.getActiveCenter();
        if (center != null && center.activity == activity && center.open)
            update(center, current(center));
    }
}
