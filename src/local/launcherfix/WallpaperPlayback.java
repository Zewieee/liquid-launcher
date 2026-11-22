package local.launcherfix;

import android.content.Context;
import android.view.View;
import com.gathin.tvhome.MainActivity;
import com.gathin.tvhome.WallpaperPlayer;
import java.io.File;
import java.util.Iterator;
import java.util.Set;

/** Route the native rotation's chosen file without sending MP4s to the image decoder. */
public final class WallpaperPlayback {
    private WallpaperPlayback() {}

    public static boolean beforeUpdate(WallpaperPlayer player) {
        if (player == null || player.root == null || player.closed || !player.active
                || player.transitioning || LiveWallpaper.picking()) return true;
        Context context = player.root.getContext();
        boolean none = "none".equals(context.getSharedPreferences("launcher_settings", 0)
            .getString("wallpaper_choice", ""));
        Set<String> failed = LiveWallpaperPicker.failedPaths(context);
        boolean removed = false;
        for (Iterator iterator = player.files.iterator(); iterator.hasNext();) {
            File file = (File) iterator.next();
            if (none || failed.contains(file.getAbsolutePath())) {
                iterator.remove();
                removed = true;
            }
        }
        if (!none && removed && player.files.isEmpty()) {
            File fallback = LiveWallpaperPicker.fallbackImage(context);
            if (fallback != null && !WallpaperMedia.isVideo(fallback) && fallback.isFile()) {
                player.files.add(fallback);
                player.enabled = true;
                player.image.setVisibility(View.VISIBLE);
                player.preferences.edit().putString("image", fallback.getName())
                    .putLong("changed", System.currentTimeMillis()).apply();
            }
        }
        if (removed) WallpaperLoads.invalidate(player);
        if (none || player.files.isEmpty()) {
            if (LiveWallpaperPicker.isEnabled(context)) LiveWallpaperPicker.selectImage(context);
            LiveWallpaper.imageFilesChanged(player);
            return true;
        }
        return false;
    }

    /** true means the video renderer owns this selection; false continues native image loading. */
    public static boolean select(WallpaperPlayer player, File file, String previous, long changed) {
        if (file == null) return true;
        Context context = player.root.getContext();
        if (!WallpaperMedia.isVideo(file)) {
            if (LiveWallpaperPicker.isEnabled(context)) {
                LiveWallpaperPicker.selectImage(context);
                WallpaperLoads.invalidate(player);
            }
            return false;
        }
        if (!(context instanceof MainActivity)) return true;
        File selected = LiveWallpaperPicker.selectedFile(context);
        if (!file.equals(selected)) LiveWallpaperPicker.selectVideo(context, file);
        if (!LiveWallpaper.isPlaying(context, file)) LiveWallpaper.refresh((MainActivity) context);
        // refresh invalidates the previous bitmap identity, so publish video identity afterwards.
        player.displayed = file.getName();
        player.displayedPath = file.getAbsolutePath();
        player.showCaption(false);
        if (!file.getName().equals(previous) || changed == 0)
            player.preferences.edit().putString("image", file.getName())
                .putLong("changed", System.currentTimeMillis()).apply();
        return true;
    }
}
