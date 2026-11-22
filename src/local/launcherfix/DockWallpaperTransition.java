package local.launcherfix;

import android.util.Log;
import com.gathin.tvhome.MainActivity;
import com.gathin.tvhome.PaperTransition;
import com.gathin.tvhome.WallpaperPlayer;
import java.io.File;
import java.lang.ref.WeakReference;

/** Reuse the selected native transition and reject stale asynchronous preparation. */
public final class DockWallpaperTransition implements Runnable {
    private final WeakReference<MainActivity> owner;
    private final WeakReference<WallpaperPlayer> player;
    private final File file;
    private final long generation;

    private DockWallpaperTransition(MainActivity activity, File file, long generation) {
        owner=new WeakReference<>(activity);player=new WeakReference<>(activity.wallpaperPlayer);
        this.file=file;this.generation=generation;
    }

    public static boolean canAnimate(MainActivity activity, File target) {
        WallpaperPlayer p=activity.wallpaperPlayer;
        return activity.started && activity.animations && !activity.reducedMotion && p!=null
            && !p.closed && p.active && p.enabled && !p.transitioning && p.current!=null
            && !p.current.isRecycled() && target!=null && !WallpaperMedia.isVideo(target)
            && !LiveWallpaper.hasVideoVisual(activity) && !target.getAbsolutePath().equals(p.displayedPath);
    }

    public static void begin(MainActivity activity, File file) {
        WallpaperPlayer p=activity.wallpaperPlayer;
        DockWallpaperTransition callback=new DockWallpaperTransition(activity,file,p.selectionGeneration);
        try {
            PaperTransition.prepare(activity,null,file,PaperTransition.chosen(activity),callback);
        } catch (RuntimeException | OutOfMemoryError error) {
            PaperTransition.discardFor(callback);
            WallpaperLoads.cancelTransition(p);
            Log.w("TvHome.Wallpaper", "Could not prepare dock wallpaper transition",error);
        }
    }

    @Override public void run() {
        MainActivity activity=owner.get();
        WallpaperPlayer expected=player.get();
        WallpaperPlayer p=activity==null?null:activity.wallpaperPlayer;
        boolean current=activity!=null && !activity.isFinishing() && !activity.isDestroyed()
            && activity.started && activity.animations && !activity.reducedMotion && p!=null && p==expected
            && !p.closed && p.active && p.transitioning && p.selectionGeneration==generation
            && p.current!=null && !p.current.isRecycled() && p.files.contains(file);
        if (!current || !PaperTransition.preparedFor(this)) {
            PaperTransition.discardFor(this);
            if (expected!=null && expected.transitioning && expected.selectionGeneration==generation)
                WallpaperLoads.cancelTransition(expected);
            return;
        }
        try { PaperTransition.start(activity); }
        catch (RuntimeException | OutOfMemoryError error) {
            PaperTransition.discardFor(this);
            WallpaperLoads.cancelTransition(p);
            Log.w("TvHome.Wallpaper", "Could not start dock wallpaper transition",error);
        }
    }
}
