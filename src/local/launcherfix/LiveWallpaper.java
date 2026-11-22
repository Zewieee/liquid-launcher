package local.launcherfix;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;
import com.gathin.tvhome.MainActivity;
import com.gathin.tvhome.PaperTransition;
import com.gathin.tvhome.WallpaperPlayer;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.Locale;

/** UI ownership of the single GPU wallpaper output. Video frames never pass through a Bitmap. */
public final class LiveWallpaper {
    public static final String TAG = "launcher_live_wallpaper";
    private static WeakReference<MainActivity> home = new WeakReference<>(null);
    private static Host host;
    private static boolean pickingVideo;
    private static long sequence;

    private LiveWallpaper() {}

    public static Bitmap preview(Activity owner) {
        Host current = host;
        if (current == null || current.activity != owner || current.closed || !current.video) return null;
        if (current.preview != null && !current.preview.isRecycled()) return current.preview;
        if (!current.frameAvailable || current.previewAttempted || !current.output.isAvailable()) return null;
        int width = current.output.getWidth(), height = current.output.getHeight();
        if (width < 1 || height < 1) return null;
        current.previewAttempted = true;
        float scale = Math.min(1f, Math.min(480f / width, 270f / height));
        try {
            current.preview = current.output.getBitmap(Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale)));
        } catch (RuntimeException | OutOfMemoryError error) {
            Log.w("TvHome.LiveWallpaper", "Could not capture wallpaper preview", error);
        }
        return current.preview;
    }

    public static boolean blocksImages(WallpaperPlayer player) {
        return player != null && player.root != null
            && (LiveWallpaperPicker.isEnabled(player.root.getContext())
                || (pickingVideo && home.get() != null && home.get().wallpaperPlayer == player));
    }

    public static boolean picking() { return pickingVideo; }

    public static boolean isPlaying(Context context, File file) {
        return file != null && host != null && host.activity == owner(context) && !host.closed
            && !host.failed && !host.waitingImage && (host.video || host.pendingVideo)
            && file.getAbsolutePath().equals(host.videoPath);
    }

    public static boolean hasVideoVisual(Context context) {
        MainActivity activity = owner(context);
        return (host != null && host.activity == activity && !host.closed
                && (host.video || host.waitingImage || host.pendingVideo))
            || (context != null && LiveWallpaperPicker.isEnabled(context));
    }

    public static void onResume(MainActivity activity) {
        if (!valid(activity)) return;
        home = new WeakReference<>(activity);
        if (host != null && host.activity != activity) dispose();
        if (host == null && LiveWallpaperPicker.isEnabled(activity) && !pickingVideo)
            requestVideo(activity, false);
        if (host != null) {
            host.renderer.setResumed(true);
            if (host.waitingImage && host.commit == null && !blocksImages(activity.wallpaperPlayer)
                    && activity.wallpaperPlayer.files.isEmpty()) imageFilesChanged(activity.wallpaperPlayer);
        }
    }

    public static void onPause(MainActivity activity) {
        if (host != null && host.activity == activity) host.renderer.setResumed(false);
    }

    public static void onDestroy(MainActivity activity) {
        if (host != null && host.activity == activity) dispose();
        if (home.get() == activity) { home.clear(); pickingVideo = false; }
    }

    public static void refresh(Activity owner) {
        MainActivity activity = owner(owner);
        if (activity == null) return;
        home = new WeakReference<>(activity);
        pickingVideo = false;
        if (LiveWallpaperPicker.isEnabled(activity)) {
            requestVideo(activity, true);
        } else {
            prepareImageSelection(activity);
            WallpaperPlayer player = activity.wallpaperPlayer;
            WallpaperLoads.invalidate(player);
            if (player.active) {
                if (!player.enabled || player.files.isEmpty()) imageFilesChanged(player);
                else player.update();
            }
        }
        changed(activity);
    }

    private static void requestVideo(MainActivity activity, boolean animate) {
        if (host != null && (host.activity != activity || host.failed)) dispose();
        if (host == null) host = new Host(activity);
        Host current = host;
        current.output.setOpaque(false);
        current.waitingImage = false;
        current.commit = null;
        current.pendingVideo = true;
        current.token = ++sequence;
        current.preview = null;
        current.previewAttempted = false;
        current.frameAvailable = false;
        current.animate = animate && animations(activity);
        File videoFile = LiveWallpaperPicker.selectedFile(activity);
        current.videoPath = videoFile == null ? null : videoFile.getAbsolutePath();
        WallpaperPlayer player = activity.wallpaperPlayer;
        WallpaperLoads.invalidate(player);
        player.showCaption(false);
        current.renderer.setResumed(player.active);
        current.renderer.video(videoFile, current.token,
            current.animate, PaperTransition.chosen(activity));
    }

    /** Keep the outgoing decoder playing until the chosen image has actually reached the output. */
    public static boolean prepareImageSelection(Context context) {
        MainActivity activity = owner(context);
        if (activity == null || !hasVideoVisual(activity)) return false;
        pickingVideo = false;
        if (host != null && host.activity == activity) {
            host.token = ++sequence;
            host.commit = null;
            host.pendingVideo = false;
            host.waitingImage = true;
            host.renderer.cancelPending();
        }
        return true;
    }

    public static void beginVideoPick(Activity owner) {
        MainActivity activity = owner(owner);
        if (activity == null) return;
        home = new WeakReference<>(activity);
        pickingVideo = true;
        // The renderer pins the old file descriptor. Atomic import cannot change its source.
    }

    public static void cancelVideoPick(Activity owner) {
        MainActivity activity = owner(owner);
        pickingVideo = false;
        if (activity == null || !activity.wallpaperPlayer.active) return;
        onResume(activity);
        if (!LiveWallpaperPicker.isEnabled(activity)) activity.wallpaperPlayer.update();
    }

    public static void imageInvalidated(WallpaperPlayer player) {
        if (host == null || host.activity.wallpaperPlayer != player || !host.waitingImage
                || host.commit == null) return;
        host.token = ++sequence;
        host.commit = null;
        host.renderer.cancelPending();
    }

    public static boolean completeImage(WallpaperPlayer player, Bitmap bitmap, File file,
                                        String previous, long changed, long generation) {
        Host current = host;
        if (current == null || current.activity.wallpaperPlayer != player || !current.waitingImage
                || pickingVideo || LiveWallpaperPicker.isEnabled(current.activity)) return false;
        current.image(bitmap, generation, () -> {
            if (player.closed || generation != player.selectionGeneration
                    || !player.files.contains(file) || blocksImages(player)) return false;
            player.transitioning = true;
            try { WallpaperLoads.publish(player, bitmap, file, previous, changed); }
            finally { player.transitioning = false; }
            return true;
        });
        return true;
    }

    public static void imageFilesChanged(WallpaperPlayer player) {
        Host current = host;
        if (current == null || player == null || current.activity.wallpaperPlayer != player
                || !current.waitingImage || blocksImages(player) || !player.files.isEmpty()) return;
        long generation = player.selectionGeneration;
        current.image(blank(player.backgroundColor), generation, () -> {
            if (player.closed || generation != player.selectionGeneration || blocksImages(player)) return false;
            player.transitioning = true;
            try {
                player.show(null);
                player.root.setBackgroundColor(player.backgroundColor);
                player.showCaption(false);
            } finally { player.transitioning = false; }
            return true;
        });
    }

    private static boolean valid(MainActivity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed()
            && activity.root != null && activity.wallpaperPlayer != null;
    }

    private static MainActivity owner(Context context) {
        MainActivity activity = context instanceof MainActivity ? (MainActivity) context : home.get();
        if (!valid(activity)) activity = home.get();
        return valid(activity) ? activity : null;
    }

    private static boolean animations(MainActivity activity) {
        return activity.started && activity.animations && !activity.reducedMotion;
    }

    private static void changed(MainActivity activity) {
        View clock = activity.root.findViewWithTag(ControlPanelHelper.TAG_CLOCK_PILL);
        if (clock != null) clock.invalidate();
        WallpaperPreview.videoChanged(activity);
    }

    private static Bitmap blank(int color) {
        Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        return bitmap;
    }

    private static Bitmap initial(MainActivity activity) {
        Bitmap current = activity.wallpaperPlayer.current;
        if (current != null && !current.isRecycled()) {
            try {
                float scale = Math.min(1f, Math.min(1280f / current.getWidth(), 720f / current.getHeight()));
                Bitmap resized = Bitmap.createScaledBitmap(current, Math.max(1, Math.round(current.getWidth() * scale)),
                    Math.max(1, Math.round(current.getHeight() * scale)), true);
                return resized == current ? current.copy(Bitmap.Config.ARGB_8888, false) : resized;
            } catch (RuntimeException | OutOfMemoryError error) {
                Log.w("TvHome.LiveWallpaper", "Could not seed wallpaper output", error);
            }
        }
        return blank(activity.wallpaperPlayer.backgroundColor);
    }

    private static void dispose() {
        Host previous = host;
        host = null;
        if (previous != null) previous.close();
    }

    private interface Commit { boolean run(); }

    private static final class Host implements WallpaperRenderer.Listener {
        final MainActivity activity;
        final TextureView output;
        final WallpaperRenderer renderer;
        long token, generation;
        boolean video, pendingVideo, waitingImage, closed, failed, frameAvailable, previewAttempted, animate;
        Bitmap preview;
        Commit commit;
        String videoPath;

        Host(MainActivity activity) {
            this.activity = activity;
            output = new TextureView(activity);
            output.setTag(TAG);
            output.setOpaque(false);
            output.setFocusable(false);
            output.setClickable(false);
            output.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            renderer = new WallpaperRenderer(activity, output, this);
            int index = activity.root.indexOfChild(activity.wallpaperPlayer.image);
            activity.root.addView(output, index < 0 ? 0 : index + 1, new FrameLayout.LayoutParams(-1, -1));
            renderer.image(initial(activity), 0, false, PaperTransition.chosen(activity));
        }

        void image(Bitmap target, long generation, Commit publish) {
            output.setOpaque(false);
            token = ++sequence;
            this.generation = generation;
            commit = publish;
            pendingVideo = false;
            waitingImage = true;
            animate = animations(activity);
            activity.wallpaperPlayer.transitioning = true;
            if (failed) onComplete(token);
            else renderer.image(target, token, animate, PaperTransition.chosen(activity));
        }

        @Override public void onReady(long value) {
            if (closed || host != this || value != token) return;
            if (pendingVideo) activity.wallpaperPlayer.transitioning = true;
            if (animate) CrystalSoundEngine.playWallpaperApply(activity);
        }

        @Override public void onComplete(long value) {
            if (closed || host != this || value != token) return;
            if (pendingVideo) {
                pendingVideo = false;
                video = true;
                waitingImage = false;
                frameAvailable = true;
                // Only the completed video output guarantees full coverage and alpha=1.
                output.setOpaque(true);
                activity.wallpaperPlayer.transitioning = false;
                changed(activity);
            } else if (commit != null) {
                Commit publish = commit;
                commit = null;
                if (!publish.run()) {
                    activity.wallpaperPlayer.transitioning = false;
                    activity.wallpaperPlayer.update();
                    return;
                }
                video = false;
                waitingImage = false;
                changed(activity);
                // Keep the identical GPU frame until ImageView has drawn the committed image.
                output.postOnAnimation(() -> output.postOnAnimation(() -> {
                    if (!closed && host == this && token == value && !waitingImage && !pendingVideo) dispose();
                }));
            }
        }

        @Override public void onError(long value, Exception error) {
            if (closed || host != this || (value != token && value != 0)) return;
            Log.w("TvHome.LiveWallpaper", "Wallpaper renderer failed", error);
            failed = true;
            if (waitingImage && commit != null) {
                onComplete(token);
                return;
            }
            LiveWallpaperPicker.disable(activity);
            if (valid(activity)) Toast.makeText(activity, "vi".equals(Locale.getDefault().getLanguage())
                ? "Không phát được video. Đã quay lại hình nền ảnh."
                : "Cannot play this video. Restored image wallpaper.", Toast.LENGTH_LONG).show();
        }

        void close() {
            if (closed) return;
            closed = true;
            commit = null;
            preview = null;
            renderer.close();
            if (output.getParent() instanceof ViewGroup) ((ViewGroup) output.getParent()).removeView(output);
        }
    }
}
