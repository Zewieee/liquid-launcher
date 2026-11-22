package local.launcherfix;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.widget.Toast;
import com.gathin.tvhome.AppAdapter;
import com.gathin.tvhome.MainActivity;
import com.gathin.tvhome.MainActivity$$ExternalSyntheticLambda17;
import com.gathin.tvhome.Order;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Projects folders over the existing home order without discarding hidden app keys. */
public final class FolderTiles {
    private static final int KIND = 4;
    private static final String CLASS_MARKER = ".folder.";
    private FolderTiles() {}

    public static boolean isFolder(AppAdapter.Entry entry) {
        return entry != null && entry.kind == KIND && id(entry) != null;
    }
    public static String id(AppAdapter.Entry entry) {
        if (entry == null || entry.kind != KIND || entry.component == null) return null;
        String prefix = entry.component.getPackageName() + CLASS_MARKER;
        String name = entry.component.getClassName();
        return name.startsWith(prefix) ? name.substring(prefix.length()) : null;
    }
    public static String key(Context context, String id) { return FolderStore.componentKey(context, id); }

    static FolderStore.Snapshot read(Context context) {
        try { return FolderStore.read(context); }
        catch (IOException | RuntimeException error) {
            // A failed folder read must never hide the underlying apps.
            Log.w("TvHome.Folders", "Cannot read folders; showing ordinary apps", error);
            return FolderStore.Snapshot.empty();
        }
    }

    static final class Plan {
        final LinkedHashMap<String,Integer> ranks;
        final int docked;
        Plan(LinkedHashMap<String,Integer> ranks, int docked) { this.ranks = ranks; this.docked = docked; }
    }

    /** Pure order planning also covers a first install with only favorite-package ordering. */
    static Plan plan(Map<String,Integer> prior, int priorDocked, List<String> sortedApps,
                     int favoritePrefix, Map<String,String> folderAnchors) {
        ArrayList<Map.Entry<String,Integer>> ranked = new ArrayList<>(prior.entrySet());
        ranked.removeIf(row -> row.getKey() == null || row.getValue() == null);
        Collections.sort(ranked, (left,right) -> {
            int order = Integer.compare(left.getValue(), right.getValue());
            return order != 0 ? order : left.getKey().compareTo(right.getKey());
        });
        ArrayList<String> keys = new ArrayList<>();
        int docked = 0;
        for (Map.Entry<String,Integer> row : ranked) {
            if (!keys.contains(row.getKey())) keys.add(row.getKey());
            if (priorDocked >= 0 && row.getValue() < priorDocked) docked++;
        }
        for (String app : sortedApps) if (!keys.contains(app)) keys.add(app);
        if (priorDocked < 0 && favoritePrefix > 0 && favoritePrefix <= sortedApps.size())
            docked = keys.indexOf(sortedApps.get(favoritePrefix - 1)) + 1;
        for (Map.Entry<String,String> folder : folderAnchors.entrySet()) {
            if (keys.contains(folder.getKey())) continue;
            int at = folder.getValue() == null ? -1 : keys.indexOf(folder.getValue());
            if (at < 0) at = keys.size();
            if (at < docked) docked++;
            keys.add(at, folder.getKey());
        }
        LinkedHashMap<String,Integer> ranks = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) ranks.put(keys.get(i), i);
        return new Plan(ranks, docked);
    }

    /** Called after the original sort, before settings and empty dock slots are inserted. */
    public static void apply(AppAdapter adapter, List<AppAdapter.Entry> entries) {
        FolderStore.Snapshot folders = TileLoader.folderSnapshot(adapter.ctx);
        if (folders.folders.isEmpty()) return;
        ArrayList<String> regularKeys = new ArrayList<>();
        int favoritePrefix = 0;
        boolean inFavorites = true;
        for (AppAdapter.Entry entry : entries) {
            regularKeys.add(entry.component.flattenToShortString());
            if (inFavorites && adapter.favourites.contains(entry.pkg)) favoritePrefix++;
            else inFavorites = false;
        }
        LinkedHashMap<String,String> anchors = new LinkedHashMap<>();
        for (FolderStore.Folder folder : folders.folders) {
            String anchor = folder.anchorComponentKey;
            if (anchor == null || anchor.isEmpty()) {
                for (AppAdapter.Entry entry : entries) {
                    if (entry.kind == 0 && folder.packages.contains(entry.pkg)) {
                        anchor = entry.component.flattenToShortString(); break;
                    }
                }
            }
            anchors.put(key(adapter.ctx, folder.id), anchor);
        }
        Plan plan = plan((Map<String,Integer>)adapter.savedRanks, adapter.savedDocked,
                regularKeys, favoritePrefix, anchors);
        Map<String,String> membership = folders.membership();
        entries.removeIf(entry -> entry.kind == 0 && membership.containsKey(entry.pkg));
        for (FolderStore.Folder folder : folders.folders) {
            ComponentName component = ComponentName.unflattenFromString(key(adapter.ctx, folder.id));
            AppAdapter.Entry entry = new AppAdapter.Entry(KIND, folder.name, component,
                    null, new FolderIcon(), null, 0);
            entry.material = "transparent";
            entries.add(entry);
        }
        adapter.savedRanks = plan.ranks;
        adapter.savedDocked = plan.docked;
        Collections.sort(entries, Comparator.comparingInt(entry -> plan.ranks.get(entry.component.flattenToShortString())));
    }

    /** Worker snapshots own the plan until the existing generation check permits publication. */
    public static void publish(AppAdapter live, AppAdapter snapshot) {
        live.savedRanks = new HashMap(snapshot.savedRanks);
        live.savedDocked = snapshot.savedDocked;
    }

    public static Order order(MainActivity main, String dock, ArrayList<String> visible, int docked) {
        final FolderStore.Snapshot snapshot;
        try { snapshot = FolderStore.read(main); }
        catch (IOException | RuntimeException error) {
            Log.w("TvHome.Folders", "Cannot preserve folder members while saving order", error);
            boolean vi = "vi".equals(main.getResources().getConfiguration().getLocales().get(0).getLanguage());
            Toast.makeText(main, vi ? "Không lưu được thứ tự ứng dụng" : "Could not save app order", Toast.LENGTH_SHORT).show();
            return null;
        }
        LinkedHashMap<String,Collection<String>> members = new LinkedHashMap<>();
        for (FolderStore.Folder folder : snapshot.folders)
            members.put(key(main, folder.id), folder.packages);
        FolderOrder.Result result = FolderOrder.expand(visible, docked,
                (Map<String,Integer>)main.adapter.savedRanks, members);
        return new Order(dock, new ArrayList<>(result.keys), result.docked);
    }

    public static boolean onLongPress(MainActivity main, View view, int position) {
        if (main == null || main.reordering || main.adapter == null || position < 0
                || position >= main.adapter.items.size()) return false;
        AppAdapter.Entry entry = main.adapter.items.get(position);
        if (!isFolder(entry)) return false;
        FolderUi.manage(main, id(entry));
        return true;
    }

    /** Last bind hook wins over generic app launch/details listeners, including tile-menu-off. */
    public static void bind(AppAdapter adapter, AppAdapter.Holder holder, AppAdapter.Entry entry) {
        if (!isFolder(entry) || !(adapter.ctx instanceof MainActivity)) return;
        MainActivity main = (MainActivity) adapter.ctx;
        String id = id(entry);
        holder.label.setVisibility(View.VISIBLE);
        holder.label.setText(entry.label);
        HomeTileStyle.bind(holder, entry);
        holder.card.setOnClickListener(view -> FolderUi.show(main, id));
        holder.card.setOnLongClickListener(view -> {
            if (!main.reordering) FolderUi.manage(main, id);
            return true;
        });
    }

    public static boolean move(MainActivity main, String id) {
        if (main == null || main.adapter == null || main.adapter.positionOf(key(main,id)) < 0) return false;
        // Reuse the app menu's Move action, including quickMove, movingKey and dock-slot rebuild.
        new MainActivity$$ExternalSyntheticLambda17(main,key(main,id),0).run();
        return main.reordering;
    }

    private static final class FolderIcon extends Drawable {
        private static final ConstantState STATE = new ConstantState() {
            @Override public Drawable newDrawable() { return new FolderIcon(); }
            @Override public int getChangingConfigurations() { return 0; }
        };
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        FolderIcon() {
            paint.setColor(0xffffffff); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.7f);
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            path.moveTo(3,7); path.lineTo(3,18); path.quadTo(3,20,5,20); path.lineTo(19,20);
            path.quadTo(21,20,21,18); path.lineTo(21,9); path.quadTo(21,7,19,7);
            path.lineTo(12,7); path.lineTo(10,4); path.lineTo(5,4); path.quadTo(3,4,3,6);
            path.lineTo(3,7); path.lineTo(21,7);
        }
        @Override public int getIntrinsicWidth() { return 72; }
        @Override public int getIntrinsicHeight() { return 72; }
        @Override public ConstantState getConstantState() { return STATE; }
        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds(); int save = canvas.save();
            canvas.translate(bounds.left, bounds.top); canvas.scale(bounds.width()/24f, bounds.height()/24f);
            canvas.drawPath(path,paint); canvas.restoreToCount(save);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
