package local.launcherfix;

import android.view.View;
import com.gathin.tvhome.ControlCenter;
import com.gathin.tvhome.MainActivity;

/** Do not override a user's newer focus; provide a fallback on return. */
public final class ControlPanelFocus {
    private ControlPanelFocus() {}

    public static void initial(ControlCenter center, View first) {
        if (center == null || !center.open || center.dialog == null || first == null || first.isFocused()) return;
        if (center.dialog.getCurrentFocus() == null) first.requestFocus();
    }

    public static void restore(ControlCenter center) {
        if (center == null) return;
        View target=center.returnTo;
        if (target != null && target.isAttachedToWindow() && target.isShown() && target.requestFocus()) return;
        if (center.activity instanceof MainActivity) ((MainActivity)center.activity).focusTile(0);
    }
}
