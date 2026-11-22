package local.launcherfix;

import android.graphics.Bitmap;
import android.util.Log;
import com.gathin.tvhome.WallpaperPlayer;
import java.io.File;

/** Selection identity stays separate from the basename stored in rotation preferences. */
public final class WallpaperLoads {
    private WallpaperLoads() {}

    public static void invalidate(WallpaperPlayer player) {
        LiveWallpaper.imageInvalidated(player);
        player.selectionGeneration++;
        player.displayedPath = null;
        player.transitioning = false;
    }

    public static boolean isDisplayed(WallpaperPlayer player, File file) {
        return player.current != null && !player.current.isRecycled()
            && file.getAbsolutePath().equals(player.displayedPath);
    }

    public static void cancelTransition(WallpaperPlayer player) {
        if(player==null||!player.transitioning)return;
        invalidate(player);
        if(!player.closed&&player.active&&player.enabled)player.update();
    }

    public static boolean commitTransition(WallpaperPlayer player, Bitmap bitmap, File file, long generation) {
        if(player.closed||!player.transitioning||generation!=player.selectionGeneration
                ||!player.files.contains(file)||bitmap==null||bitmap.isRecycled())return false;
        player.displayed=file.getName();
        player.displayedPath=file.getAbsolutePath();
        player.enabled=true;
        LiveWallpaperPicker.rememberImage(player.root.getContext(), file);
        player.preferences.edit().putString("image",player.displayed).putLong("changed",System.currentTimeMillis()).apply();
        // The shader already transitioned; publish preview/colors without another crossfade.
        try{player.show(bitmap);player.showCaption(true);}finally{player.transitioning=false;}
        return true;
    }

    public static void complete(WallpaperPlayer player, Bitmap bitmap, File file,
                                String previous, long changed, long generation) {
        player.loading = false;
        if (player.closed || player.transitioning || !player.active || !player.enabled
                || LiveWallpaper.blocksImages(player)
                || generation != player.selectionGeneration || !player.files.contains(file)) {
            if (bitmap != null) bitmap.recycle();
            if (!player.closed && player.active && player.enabled) player.update();
            return;
        }
        if (bitmap == null) {
            player.files.remove(file);
            Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + file.getName());
            LiveWallpaper.imageFilesChanged(player);
            player.update();
            return;
        }
        if (LiveWallpaper.completeImage(player, bitmap, file, previous, changed, generation)) return;
        publish(player, bitmap, file, previous, changed);
    }

    /** Shared final publication, also used after a video/image shader handoff. */
    static void publish(WallpaperPlayer player, Bitmap bitmap, File file, String previous, long changed) {
        LiveWallpaperPicker.rememberImage(player.root.getContext(), file);
        player.show(bitmap);
        player.displayed = file.getName();
        player.displayedPath = file.getAbsolutePath();
        player.showCaption(true);
        if (!player.displayed.equals(previous) || changed == 0) {
            player.preferences.edit().putString("image", player.displayed)
                .putLong("changed", System.currentTimeMillis()).apply();
        }
    }
}
