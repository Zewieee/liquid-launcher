package local.launcherfix;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import com.gathin.tvhome.AppAdapter;

/** Keeps custom artwork intact while giving tile contents a shared safe area. */
public final class HomeTileStyle {
    private HomeTileStyle() {}

    public static void bind(AppAdapter.Holder holder, AppAdapter.Entry entry) {
        if (holder == null || holder.card == null || entry == null) return;
        float density = holder.card.getResources().getDisplayMetrics().density;
        ViewGroup.LayoutParams cardParams = holder.card.getLayoutParams();
        int height = cardParams == null ? 0 : cardParams.height;
        if (height <= 0) return;

        ImageView banner = holder.banner;
        if (banner != null) {
            // Normal TV banners remain full bleed; transparent artwork gets breathing room.
            boolean insetArtwork = "transparent".equals(entry.material)
                    || "liquid".equals(entry.material);
            int inset = insetArtwork ? Math.round(height * 0.06f) : 0;
            banner.setPadding(inset, inset, inset, inset);
        }

        TextView label = holder.label;
        if (label != null) {
            label.setTextSize(12f);
            label.setLetterSpacing(0f);
            int side = Math.round(8f * density);
            int bottom = Math.round(Math.min(6f * density, height * 0.08f));
            label.setPadding(side, 0, side, bottom);
        }
        if (holder.name != null) {
            holder.name.setTextSize(12f);
            holder.name.setLetterSpacing(0.01f);
        }

        if (holder.icon != null && holder.icon.getVisibility() == View.VISIBLE) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) holder.icon.getLayoutParams();
            boolean hasLabel = label != null && label.getVisibility() == View.VISIBLE;
            int size = Math.round(height * (hasLabel ? 0.48f : 0.62f));
            int bottom = hasLabel ? Math.round(height * 0.22f) : 0;
            holder.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (params.width != size || params.height != size || params.bottomMargin != bottom) {
                params.width = params.height = size;
                params.bottomMargin = bottom;
                holder.icon.setLayoutParams(params);
            }
        }
    }
}
