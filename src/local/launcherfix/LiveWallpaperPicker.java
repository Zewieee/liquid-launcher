package local.launcherfix;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import com.gathin.tvhome.MainActivity;
import java.io.File;
import java.util.HashSet;
import java.util.Set;

/** Playback preferences for videos selected through the ordinary Wallpaper picker. */
public final class LiveWallpaperPicker {
    public static final String PREFS = "live_wallpaper";
    private LiveWallpaperPicker() {}

    public static boolean isEnabled(Context context) {
        return context != null && context.getSharedPreferences(PREFS, 0).getBoolean("enabled", false);
    }

    public static File selectedFile(Context context) {
        if (!isEnabled(context)) return null;
        String path = context.getSharedPreferences(PREFS, 0).getString("path", "");
        return path.isEmpty() ? new File(context.getFilesDir(), "live-wallpaper.mp4") : new File(path);
    }

    /** Old builds kept video mode separate from wallpaper_choice. Preserve that active choice. */
    public static void migrateSelection(Context context) {
        recoverDecoderFailure(context);
        SharedPreferences live = context.getSharedPreferences(PREFS, 0);
        if (!live.getBoolean("enabled", false) || live.contains("path")) return;
        File previous = new File(context.getFilesDir(), "live-wallpaper.mp4");
        SharedPreferences settings = context.getSharedPreferences("launcher_settings", 0);
        String oldChoice = settings.getString("wallpaper_choice", "");
        if (oldChoice != null && new File(oldChoice).isAbsolute() && !WallpaperMedia.isVideo(new File(oldChoice)))
            rememberImage(context, new File(oldChoice));
        live.edit().putString("path", previous.getAbsolutePath()).apply();
        settings.edit().putString("wallpaper_choice", previous.getAbsolutePath()).apply();
        context.getSharedPreferences("wallpaper_collections", 0).edit()
            .putString("active_collection_id", "default").apply();
        context.getSharedPreferences("wallpaper_rotation", 0).edit()
            .putString("image", previous.getName()).putLong("changed", System.currentTimeMillis()).apply();
    }

    /** Retry after the codec service is reset; stop migration once a primary frame succeeds. */
    private static synchronized void recoverDecoderFailure(Context context) {
        SharedPreferences live = context.getSharedPreferences(PREFS, 0);
        if (live.getBoolean("decoder_recovery_70_done", false)) return;
        int boot = android.provider.Settings.Global.getInt(context.getContentResolver(),
            android.provider.Settings.Global.BOOT_COUNT, -1);
        if (live.getInt("decoder_recovery_70_boot", -2) == boot) return;
        Set<String> failed = failedPaths(context);
        SharedPreferences.Editor edit = live.edit().putInt("decoder_recovery_70_boot", boot)
            .remove("failed_paths");
        String choice = context.getSharedPreferences("launcher_settings", 0)
            .getString("wallpaper_choice", "");
        // Honor a subsequent explicit image/None choice; only restore the still-selected video.
        File restore = null;
        if (choice != null && !choice.isEmpty() && !"none".equals(choice) && !"auto".equals(choice)) {
            File selected = new File(choice);
            if (selected.isAbsolute() && WallpaperMedia.isVideo(selected)
                    && failed.contains(selected.getAbsolutePath())) restore = selected;
            else {
                String previous = live.getString("path", "");
                if (!previous.isEmpty() && failed.contains(previous)
                        && choice.equals(new File(previous).getName())) restore = new File(previous);
            }
        }
        if (restore != null && restore.isFile()) {
            edit.putBoolean("enabled", true).putString("path", restore.getAbsolutePath());
            context.getSharedPreferences("wallpaper_rotation", 0).edit()
                .putString("image", restore.getName()).putLong("changed", System.currentTimeMillis()).apply();
            android.util.Log.i("WallpaperVideo", "Retrying selected video after decoder recovery");
        } else if (!"auto".equals(choice) || failed.isEmpty()) {
            edit.putBoolean("decoder_recovery_70_done", true);
        }
        edit.apply();
    }

    public static void confirmDecoderRecovery(Context context) {
        SharedPreferences live = context.getSharedPreferences(PREFS, 0);
        if (!live.getBoolean("decoder_recovery_70_done", false))
            live.edit().putBoolean("decoder_recovery_70_done", true).apply();
    }

    /** Only publication changes mode; LiveWallpaper.refresh starts the decoder after metadata. */
    public static void selectVideo(Context context, File file) {
        if (file == null || !WallpaperMedia.isVideo(file)) throw new IllegalArgumentException("Not an MP4 wallpaper");
        if (context instanceof MainActivity) {
            MainActivity activity = (MainActivity) context;
            String previous = activity.wallpaperPlayer == null ? null : activity.wallpaperPlayer.displayedPath;
            if (previous != null && !previous.isEmpty() && !WallpaperMedia.isVideo(new File(previous))) rememberImage(context, new File(previous));
        }
        Set<String> failed = failedPaths(context);
        failed.remove(file.getAbsolutePath());
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("enabled", true)
            .putString("path", file.getAbsolutePath()).putStringSet("failed_paths", failed).apply();
    }

    public static void selectImage(Context context) {
        LiveWallpaper.prepareImageSelection(context);
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("enabled", false).apply();
    }

    /** Decoder failure must not immediately retry the same broken entry in an auto collection. */
    public static void disable(Context context) {
        File failed = selectedFile(context);
        SharedPreferences.Editor edit = context.getSharedPreferences(PREFS, 0).edit().putBoolean("enabled", false);
        Set<String> paths = failedPaths(context);
        if (failed != null) paths.add(failed.getAbsolutePath());
        edit.putStringSet("failed_paths", paths);
        edit.apply();
        if (context instanceof Activity) LiveWallpaper.refresh((Activity) context);
    }

    public static Set<String> failedPaths(Context context) {
        return new HashSet<>(context.getSharedPreferences(PREFS, 0)
            .getStringSet("failed_paths", java.util.Collections.emptySet()));
    }

    public static void rememberImage(Context context, File file) {
        if (file == null || WallpaperMedia.isVideo(file)) return;
        SharedPreferences live = context.getSharedPreferences(PREFS, 0);
        if (!file.getAbsolutePath().equals(live.getString("image_fallback", "")))
            live.edit().putString("image_fallback", file.getAbsolutePath()).apply();
    }

    public static File fallbackImage(Context context) {
        String path = context.getSharedPreferences(PREFS, 0).getString("image_fallback", "");
        return path.isEmpty() ? null : new File(path);
    }
}
