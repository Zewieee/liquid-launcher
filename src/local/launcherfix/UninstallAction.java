package local.launcherfix;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

/** Ask the system package installer to confirm before it removes anything. */
public final class UninstallAction {
    private UninstallAction() {}
    public static Intent intent(Activity activity, String packageName) {
        if (activity == null || packageName == null || packageName.isEmpty()
                || packageName.equals(activity.getPackageName())) return null;
        return new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null));
    }
}
