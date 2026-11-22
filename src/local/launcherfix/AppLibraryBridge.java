package local.launcherfix;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import com.gathin.tvhome.AppLibraryDialog;
import com.gathin.tvhome.MainActivity;
import java.util.List;

public final class AppLibraryBridge {
    public static volatile boolean launchedFromLibrary = false;

    private AppLibraryBridge() {}

    public static List<ResolveInfo> query(Context context, PackageManager pm, Intent intent, int flags) {
        if (context != null) {
            return Catalog.query(context, pm, intent, flags);
        }
        return pm.queryIntentActivities(intent, flags);
    }

    public static CharSequence label(Context context, ResolveInfo info, PackageManager pm) {
        if (context != null) {
            return ImageCache.label(context, info, pm);
        }
        return info.loadLabel(pm);
    }

    public static Drawable icon(Context context, ResolveInfo info, PackageManager pm) {
        if (context != null) {
            return ImageCache.icon(context, info, pm);
        }
        return info.loadIcon(pm);
    }

    public static void onOpenApp(AppLibraryDialog dialog, ResolveInfo info) {
        launchedFromLibrary = true;
        if (dialog != null && dialog.activity instanceof MainActivity) {
            MainActivity a = (MainActivity) dialog.activity;
            a.libraryFocusPending = false;
            MainActivity.lastFocused = 0;
            MainActivity.lastFocusedKey = null;
        }
    }

    public static void onLibraryDismissed(final MainActivity activity) {
        if (activity == null) return;
        launchedFromLibrary = false;
        activity.libraryFocusPending = false;
    }
}
