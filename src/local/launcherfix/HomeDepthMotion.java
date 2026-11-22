package local.launcherfix;

import android.view.View;
import com.gathin.tvhome.MainActivity;

/** Retained hook signatures: changing focus no longer shifts the grid or wallpaper. */
public final class HomeDepthMotion {
    private HomeDepthMotion() {}
    public static void onTileFocused(MainActivity activity,View tile,int position) {}
    public static void reset(MainActivity activity) {}
}
