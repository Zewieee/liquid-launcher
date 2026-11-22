package local.launcherfix;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.app.Fragment;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.gathin.tvhome.Backdrop;
import com.gathin.tvhome.ClearGlass;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** In-app, single-selection wallpaper gallery. Only the existing importer applies a choice. */
public final class WallpaperImportPicker {
    public interface Listener {
        void onPicked(Uri uri);
        void onCancelled();
        void onExternal();
    }

    private static final String PERMISSION_TAG = "wallpaper_gallery_permission";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<WallpaperImportPicker> active = new WeakReference<>(null);
    private final Activity activity;
    private final Context app;
    private final boolean vi;
    private final String internalStorage;
    private final float density;
    private final Typeface font;
    private Listener listener;
    private final Dialog dialog;
    private Dialog foldersDialog;
    private final LinearLayout panel, empty;
    private final GridView grid;
    private final GalleryAdapter adapter = new GalleryAdapter();
    private final int tileHeight;
    private final TextView message, count, previewName, previewInfo, choose, allow, external, cancel, folders;
    private final TextView[] filters = new TextView[3];
    private final ImageView preview;
    private final HashMap<ImageView, Future<?>> thumbnailJobs = new HashMap<>();
    private final LinkedBlockingDeque<Runnable> workQueue = new LinkedBlockingDeque<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS,
        workQueue, task -> new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            task.run();
        }, "wallpaper-gallery"));
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private List<WallpaperGallery.Entry> all = Collections.emptyList(), filtered = Collections.emptyList();
    private WallpaperGallery.Entry selected;
    private Future<?> scanJob, previewJob;
    private int scanGeneration, galleryGeneration, previewGeneration, type, outcome, selectedPosition = -1;
    private String folderKey = "";
    private Uri chosen;
    private boolean closed, closing, silent, permissionPending, userNavigated;

    private final Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityStopped(Activity owner) {
            if (owner == activity && !closed && !permissionPending) {
                LiveWallpaper.cancelVideoPick(activity);
                dismissForActivity(activity);
            }
        }
        @Override public void onActivityDestroyed(Activity owner) {
            if (owner == activity && !closed) {
                LiveWallpaper.cancelVideoPick(activity);
                dismissForActivity(activity);
            }
        }
        @Override public void onActivityCreated(Activity a, Bundle b) {}
        @Override public void onActivityStarted(Activity a) {}
        @Override public void onActivityResumed(Activity a) {}
        @Override public void onActivityPaused(Activity a) {}
        @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
    };

    private WallpaperImportPicker(Activity owner, Listener listener) {
        activity = owner; app = owner.getApplicationContext(); this.listener = listener;
        density = owner.getResources().getDisplayMetrics().density;
        vi = "vi".equals(Locale.getDefault().getLanguage());
        internalStorage = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
        int fontId = owner.getResources().getIdentifier("font_body", "string", owner.getPackageName());
        font = fontId == 0 ? Typeface.DEFAULT : Typeface.create(owner.getString(fontId), Typeface.NORMAL);
        dialog = new Dialog(owner) {
            @Override public void onBackPressed() { finish(0, null); }
        };
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        panel = column(); panel.setTag("wallpaper_gallery");
        panel.setPadding(dp(20), dp(18), dp(20), dp(16));
        panel.setBackground(ClearGlass.panel(dp(24), density));
        panel.setClipChildren(false); panel.setClipToPadding(false);

        LinearLayout header = row();
        ImageView emblem = new ImageView(owner);
        emblem.setImageDrawable(new GalleryIcon(density));
        emblem.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(emblem, new LinearLayout.LayoutParams(dp(27), dp(27)));
        TextView heading = text(vi ? "Chọn hình nền" : "Choose wallpaper", 20, 0xffffffff);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, dp(32), 1); hp.setMarginStart(dp(12));
        header.addView(heading, hp);
        TextView subtitle = text(vi ? "Ảnh & video" : "Photos & video", 12, 0xbbffffff);
        header.addView(subtitle);
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(32)));

        LinearLayout tabs = row();
        String[] names = vi ? new String[]{"Tất cả", "Ảnh", "Video"} : new String[]{"All", "Photos", "Video"};
        for (int i = 0; i < filters.length; i++) {
            final int category = i;
            TextView tab = button(names[i], "gallery_filter_" + i); filters[i] = tab;
            tab.setOnClickListener(v -> { if (!closing && type != category) { type = category; showGallery(false); } });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(82), dp(44)); p.setMarginEnd(dp(6)); tabs.addView(tab, p);
        }
        tabs.addView(new View(owner), new LinearLayout.LayoutParams(0, 1, 1));
        folders = button(vi ? "Mọi thư mục ▾" : "All folders ▾", "gallery_folders");
        folders.setOnClickListener(v -> showFolders());
        tabs.addView(folders, new LinearLayout.LayoutParams(dp(186), dp(44)));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, dp(44)); tp.topMargin = dp(8); tp.bottomMargin = dp(12); panel.addView(tabs, tp);

        LinearLayout content = row(); content.setGravity(Gravity.TOP);
        FrameLayout gridHost = new FrameLayout(owner);
        grid = new GridView(owner); grid.setId(View.generateViewId()); grid.setTag("gallery_grid");
        grid.setNumColumns(3); grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(8)); grid.setVerticalSpacing(dp(8));
        grid.setSelector(new ColorDrawable(0)); grid.setVerticalScrollBarEnabled(true);
        grid.setClipToPadding(false); grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        grid.setFocusable(true); grid.setFocusableInTouchMode(true); grid.setAdapter(adapter);
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (live() && grid.hasFocus() && position >= 0 && position < filtered.size()) select(filtered.get(position));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        grid.setOnItemClickListener((parent, view, position, id) -> activateItem(position));
        grid.setOnFocusChangeListener((view, focused) -> paintSelection());
        grid.setOnKeyListener((view, key, event) -> navigateGrid(key, event));
        grid.setRecyclerListener(view -> { if (view instanceof GalleryTile) cancelThumbnail(((GalleryTile) view).image); });
        gridHost.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        empty = column(); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(18), dp(16), dp(18), dp(16));
        message = text("", 14, 0xd2ffffff); message.setSingleLine(false); message.setGravity(Gravity.CENTER);
        empty.addView(message, new LinearLayout.LayoutParams(-1, -2));
        gridHost.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        content.addView(gridHost, new LinearLayout.LayoutParams(0, -1, 1));

        LinearLayout detail = column();
        detail.setPadding(dp(12), dp(10), dp(12), dp(10));
        detail.setBackground(ClearGlass.panel(dp(18), density));
        preview = new ImageView(owner); preview.setTag("gallery_preview"); preview.setScaleType(ImageView.ScaleType.CENTER);
        preview.setImageDrawable(new GalleryIcon(density)); rounded(preview, 12);
        detail.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
        previewName = text(vi ? "Xem trước" : "Preview", 14, 0xffffffff); previewName.setMaxLines(2); previewName.setSingleLine(false);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, dp(36)); np.topMargin = dp(8); detail.addView(previewName, np);
        previewInfo = text(vi ? "Chọn một ảnh hoặc video" : "Select a photo or video", 11, 0xbbffffff);
        detail.addView(previewInfo, new LinearLayout.LayoutParams(-1, dp(22)));
        choose = button(vi ? "Dùng hình nền" : "Use wallpaper", "gallery_choose");
        choose.setOnClickListener(v -> { if (selected != null) finish(1, selected.uri); });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(48)); cp.topMargin = dp(6); detail.addView(choose, cp);
        int panelWidth = Math.min(dp(860), Math.max(dp(540), owner.getResources().getDisplayMetrics().widthPixels - dp(48)));
        int detailWidth = Math.min(dp(250), Math.round(panelWidth * .30f));
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(detailWidth, -1); detailParams.setMarginStart(dp(16));
        content.addView(detail, detailParams);
        panel.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout footer = row();
        count = text("", 11, 0xbbffffff); count.setTag("gallery_count");
        footer.addView(count, new LinearLayout.LayoutParams(0, -2, 1));
        allow = button(vi ? "Cho phép" : "Allow", "gallery_allow"); allow.setOnClickListener(v -> requestAccess());
        addFooter(footer, allow, 84);
        external = button(vi ? "Tệp khác" : "Other files", "gallery_external"); external.setOnClickListener(v -> finish(2, null)); addFooter(footer, external, 104);
        cancel = button(vi ? "Hủy" : "Cancel", "gallery_cancel"); cancel.setOnClickListener(v -> finish(0, null)); addFooter(footer, cancel, 76);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, dp(48)); fp.topMargin = dp(12); panel.addView(footer, fp);

        Backdrop.wrap(owner, dialog, panel, true, 0);
        int panelHeight = Math.min(dp(440), Math.max(dp(340), owner.getResources().getDisplayMetrics().heightPixels - dp(48)));
        tileHeight = Math.max(dp(72), (panelHeight - dp(198)) / 2);
        panel.setLayoutParams(new FrameLayout.LayoutParams(panelWidth, panelHeight, Gravity.CENTER));
        dialog.setOnKeyListener((d, key, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && key >= KeyEvent.KEYCODE_DPAD_UP && key <= KeyEvent.KEYCODE_DPAD_RIGHT) userNavigated = true;
            return closing;
        });
        dialog.setOnDismissListener(d -> dismissed());
    }

    public static boolean show(Activity owner, Listener listener) {
        if (owner == null || listener == null || owner.isFinishing() || owner.isDestroyed()) return false;
        WallpaperImportPicker previous = active.get();
        if (previous != null) dismissForActivity(previous.activity);
        WallpaperImportPicker picker = new WallpaperImportPicker(owner, listener);
        active = new WeakReference<>(picker);
        try {
            owner.getApplication().registerActivityLifecycleCallbacks(picker.lifecycle);
            picker.dialog.show();
            if (picker.dialog.getWindow() != null) picker.dialog.getWindow().setLayout(-1, -1);
            picker.scan(); picker.filters[0].requestFocus();
            return true;
        } catch (RuntimeException unavailable) {
            picker.silent = true; picker.dispose();
            if (picker.dialog.isShowing()) picker.dialog.dismiss();
            return false;
        }
    }

    public static Dialog getActiveDialog() { WallpaperImportPicker picker = active.get(); return picker == null ? null : picker.dialog; }
    public static boolean dismissForActivity(Activity owner) {
        WallpaperImportPicker picker = active.get();
        if (picker == null || picker.activity != owner) return false;
        picker.silent = true; picker.dispose(); PopupOptimizer.dismissImmediate(picker.dialog, false); return true;
    }
    public static boolean dismissFromBack(Activity owner) {
        WallpaperImportPicker picker = active.get();
        if (picker == null || picker.activity != owner || picker.closed) return false;
        if (picker.foldersDialog != null) PopupOptimizer.dismissAnimated(picker.foldersDialog, true);
        else picker.finish(0, null);
        return true;
    }

    private boolean live() { return !closed && !closing && !activity.isFinishing() && !activity.isDestroyed() && dialog.isShowing(); }
    private void finish(int result, Uri uri) {
        if (!live()) return;
        closing = true; outcome = result; chosen = uri; cancelWork();
        PopupOptimizer.dismissAnimated(dialog, true);
    }
    private void dismissed() {
        Listener callback = listener;
        boolean deliver = !silent && !closed && !activity.isFinishing() && !activity.isDestroyed() && !activity.isChangingConfigurations();
        dispose();
        if (!deliver || callback == null) return;
        if (outcome == 1 && chosen != null) callback.onPicked(chosen);
        else if (outcome == 2) callback.onExternal();
        else callback.onCancelled();
    }
    private void dispose() {
        if (closed) return;
        closed = true; listener = null; cancelWork(); worker.shutdownNow();
        activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
        if (foldersDialog != null) { foldersDialog.setOnDismissListener(null); foldersDialog.dismiss(); foldersDialog = null; }
        Fragment permission = activity.getFragmentManager().findFragmentByTag(PERMISSION_TAG);
        if (permission != null) try { activity.getFragmentManager().beginTransaction().remove(permission).commitAllowingStateLoss(); }
        catch (RuntimeException ignored) { }
        preview.setImageDrawable(null); grid.setAdapter(null); cache.evictAll();
        if (active.get() == this) active = new WeakReference<>(null);
    }
    private void cancelFuture(Future<?> future) {
        if (future != null) { future.cancel(true); if (future instanceof Runnable) worker.remove((Runnable) future); }
    }
    private void cancelThumbs() {
        galleryGeneration++; previewGeneration++;
        for (Future<?> job : thumbnailJobs.values()) cancelFuture(job);
        thumbnailJobs.clear(); cancelFuture(previewJob); previewJob = null;
    }
    private void cancelThumbnail(ImageView image) {
        cancelFuture(thumbnailJobs.remove(image)); image.setTag(null);
    }
    private void cancelWork() { scanGeneration++; cancelFuture(scanJob); scanJob = null; cancelThumbs(); }

    private void scan() {
        if (!live()) return;
        cancelWork(); final int token = scanGeneration;
        message.setText(vi ? "Đang tìm ảnh và video…" : "Finding photos and video…"); empty.setVisibility(View.VISIBLE);
        allow.setVisibility(WallpaperGallery.missingPermissions(app).length == 0 ? View.GONE : View.VISIBLE);
        choose.setEnabled(false); choose.setAlpha(.4f); count.setText(vi ? "Đang tải…" : "Loading…"); wire();
        WeakReference<WallpaperImportPicker> target = new WeakReference<>(this);
        final Context context = app;
        scanJob = worker.submit(() -> {
            List<WallpaperGallery.Entry> result = Collections.emptyList(); boolean failed = false;
            try { result = WallpaperGallery.load(context); } catch (Exception error) { failed = true; }
            final List<WallpaperGallery.Entry> entries = result; final boolean problem = failed;
            MAIN.post(() -> {
                WallpaperImportPicker picker = target.get();
                if (picker == null || !picker.live() || token != picker.scanGeneration) return;
                picker.scanJob = null; picker.all = entries;
                picker.showGallery(!picker.userNavigated);
                if (problem && entries.isEmpty()) picker.message.setText(picker.vi ? "Không đọc được thư viện. Chọn Tệp khác để tiếp tục." : "Library unavailable. Choose Other files to continue.");
            });
        });
    }

    private void showGallery(boolean moveFocus) {
        if (!live()) return;
        cancelThumbs(); selected = null; selectedPosition = -1;
        filtered = WallpaperGallery.filter(all, type, folderKey);
        for (int i = 0; i < filters.length; i++) { filters[i].setSelected(type == i); filters[i].setAlpha(type == i ? 1f : .68f); }
        int missing = WallpaperGallery.missingPermissions(app).length;
        allow.setVisibility(missing == 0 ? View.GONE : View.VISIBLE);
        empty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        message.setText(missing > 0
            ? (vi ? "Cho phép truy cập ảnh để xem thư viện trên TV." : "Allow media access to browse your TV library.")
            : (vi ? "Chưa có ảnh trong mục này. Dùng Tệp khác để chọn từ USB hoặc vị trí khác." : "No media here. Use Other files for USB or another location."));
        adapter.notifyDataSetChanged(); grid.setSelection(0);
        grid.setVisibility(filtered.isEmpty() ? View.GONE : View.VISIBLE);
        if (!filtered.isEmpty()) {
            select(filtered.get(0));
        } else {
            previewGeneration++; preview.setScaleType(ImageView.ScaleType.CENTER); preview.setImageDrawable(new GalleryIcon(density));
            previewName.setText(vi ? "Xem trước" : "Preview"); previewInfo.setText(vi ? "Chọn một ảnh hoặc video" : "Select a photo or video");
        }
        updateCount(); choose.setEnabled(selected != null); choose.setAlpha(selected != null ? 1f : .4f); wire();
        if (moveFocus) { if (!filtered.isEmpty()) focusGrid(0); else footerFirst().requestFocus(); }
    }

    private void select(WallpaperGallery.Entry entry) {
        if (!live() || entry == null) return;
        boolean unchanged = selected == entry;
        selected = entry; selectedPosition = filtered.indexOf(entry);
        paintSelection(); updateCount();
        choose.setEnabled(true); choose.setAlpha(1f);
        previewName.setText(entry.name); previewInfo.setText((entry.video ? "MP4" : (vi ? "Ảnh" : "Photo")) + "  ·  " + folderLabel(entry));
        wire();
        if (unchanged && previewJob != null) return;
        final int token = ++previewGeneration; cancelFuture(previewJob);
        final String key = key(entry, 560, 315);
        Bitmap found = cache.get(key);
        if (found != null && !found.isRecycled()) { preview.setScaleType(ImageView.ScaleType.CENTER_CROP); preview.setImageBitmap(found); return; }
        Bitmap thumbnail = cache.get(key(entry, 320, 180));
        if (thumbnail != null && !thumbnail.isRecycled()) { preview.setScaleType(ImageView.ScaleType.CENTER_CROP); preview.setImageBitmap(thumbnail); }
        else { preview.setScaleType(ImageView.ScaleType.CENTER); preview.setImageDrawable(new GalleryIcon(density)); }
        WeakReference<WallpaperImportPicker> target = new WeakReference<>(this); final Context context = app;
        previewJob = worker.submit(() -> {
            Bitmap image = decode(context, entry, 560, 315);
            MAIN.post(() -> {
                WallpaperImportPicker picker = target.get();
                if (picker == null || !picker.live() || token != picker.previewGeneration) { recycle(image); return; }
                picker.previewJob = null;
                if (image != null) { picker.cache.put(key, image); picker.preview.setScaleType(ImageView.ScaleType.CENTER_CROP); picker.preview.setImageBitmap(image); }
            });
        });
        // Leave a running decoder alone; put only the queued selection ahead of other tiles.
        Runnable priority = (Runnable) previewJob;
        if (workQueue.remove(priority)) workQueue.offerFirst(priority);
    }

    private void thumbnail(WallpaperGallery.Entry entry, ImageView view) {
        cancelThumbnail(view);
        final int token = galleryGeneration; final Object binding = new Object(); view.setTag(binding);
        final String key = key(entry, 320, 180);
        Bitmap found = cache.get(key);
        if (found != null && !found.isRecycled()) { view.setScaleType(ImageView.ScaleType.CENTER_CROP); view.setImageBitmap(found); return; }
        view.setScaleType(ImageView.ScaleType.CENTER); view.setImageDrawable(new GalleryIcon(density));
        WeakReference<WallpaperImportPicker> target = new WeakReference<>(this); WeakReference<ImageView> imageView = new WeakReference<>(view); final Context context = app;
        Future<?> job = worker.submit(() -> {
            Bitmap image = decode(context, entry, 320, 180);
            MAIN.post(() -> {
                WallpaperImportPicker picker = target.get(); ImageView cell = imageView.get();
                if (picker == null || !picker.live() || token != picker.galleryGeneration || cell == null || cell.getTag() != binding) { recycle(image); return; }
                picker.thumbnailJobs.remove(cell);
                if (image != null) {
                    picker.cache.put(key, image); cell.setScaleType(ImageView.ScaleType.CENTER_CROP); cell.setImageBitmap(image);
                    if (picker.selected == entry && picker.preview.getScaleType() == ImageView.ScaleType.CENTER) {
                        picker.preview.setScaleType(ImageView.ScaleType.CENTER_CROP); picker.preview.setImageBitmap(image);
                    }
                }
            });
        });
        thumbnailJobs.put(view, job);
    }

    private final class GalleryAdapter extends BaseAdapter {
        @Override public int getCount() { return filtered.size(); }
        @Override public WallpaperGallery.Entry getItem(int position) { return filtered.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            GalleryTile tile = convertView instanceof GalleryTile ? (GalleryTile) convertView : new GalleryTile();
            WallpaperGallery.Entry entry = getItem(position); tile.entry = entry;
            tile.setLayoutParams(new AbsListView.LayoutParams(-1, tileHeight));
            tile.setTag("gallery_item:" + entry.uri); tile.setContentDescription(entry.name + (entry.video ? ", MP4" : ""));
            tile.name.setText(entry.name); tile.badge.setVisibility(entry.video ? View.VISIBLE : View.GONE);
            tile.showSelected(); thumbnail(entry, tile.image); return tile;
        }
    }

    private final class GalleryTile extends LinearLayout {
        final ImageView image;
        final TextView name, badge, mark;
        WallpaperGallery.Entry entry;
        GalleryTile() {
            super(activity); setOrientation(VERTICAL); setFocusable(false); setClickable(false);
            setPadding(dp(4), dp(4), dp(4), dp(4)); setBackground(new ClearGlass.Plate(dp(14), density, -1));
            FrameLayout imageBox = new FrameLayout(activity); rounded(imageBox, 10);
            image = new ImageView(activity); image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            imageBox.addView(image, new FrameLayout.LayoutParams(-1, -1));
            badge = text("MP4", 10, 0xffffffff); badge.setPadding(dp(6), dp(2), dp(6), dp(2)); badge.setBackground(ClearGlass.panel(dp(6), density));
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START); bp.setMargins(dp(5), 0, 0, dp(5)); imageBox.addView(badge, bp);
            mark = text("✓", 13, 0xffffffff); mark.setGravity(Gravity.CENTER); mark.setBackground(ClearGlass.panel(dp(12), density));
            FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP | Gravity.END); mp.setMargins(0, dp(5), dp(5), 0); imageBox.addView(mark, mp);
            addView(imageBox, new LinearLayout.LayoutParams(-1, 0, 1));
            name = text("", 11, 0xf2ffffff); name.setPadding(dp(4), dp(2), dp(4), 0); addView(name, new LinearLayout.LayoutParams(-1, dp(24)));
        }
        void showSelected() {
            boolean chosen = entry == selected; setSelected(chosen);
            setAlpha(chosen && grid.hasFocus() ? 1f : .78f); mark.setVisibility(chosen ? View.VISIBLE : View.GONE);
        }
    }

    private void paintSelection() {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i); if (child instanceof GalleryTile) ((GalleryTile) child).showSelected();
        }
    }
    private void updateCount() {
        String value = filtered.isEmpty() ? "0" : (selectedPosition + 1) + " / " + filtered.size();
        count.setText(value + (vi ? " tệp" : " files"));
        grid.setContentDescription((vi ? "Thư viện hình nền, " : "Wallpaper gallery, ") + value);
    }
    private View footerFirst() { return allow.getVisibility() == View.VISIBLE ? allow : external; }
    private void activateItem(int position) {
        if (live() && position >= 0 && position < filtered.size()) { select(filtered.get(position)); choose.requestFocus(); }
    }
    private void focusGrid(int position) {
        if (!live() || filtered.isEmpty()) { footerFirst().requestFocus(); return; }
        int target = Math.max(0, Math.min(position, filtered.size() - 1));
        select(filtered.get(target)); grid.requestFocus(); grid.setSelection(target); paintSelection();
    }
    private boolean navigateGrid(int key, KeyEvent event) {
        if (key < KeyEvent.KEYCODE_DPAD_UP || key > KeyEvent.KEYCODE_DPAD_RIGHT || filtered.isEmpty()) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        int position = Math.max(0, Math.min(selectedPosition, filtered.size() - 1));
        if (key == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < 3) filters[position].requestFocus(); else focusGrid(position - 3);
        } else if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (position / 3 == (filtered.size() - 1) / 3) footerFirst().requestFocus();
            else focusGrid(Math.min(position + 3, filtered.size() - 1));
        } else if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (position % 3 != 0) focusGrid(position - 1);
        } else if (position % 3 == 2 || position + 1 == filtered.size()) choose.requestFocus();
        else focusGrid(position + 1);
        return true;
    }
    private static Bitmap decode(Context context, WallpaperGallery.Entry entry, int width, int height) {
        try { if (!Thread.currentThread().isInterrupted()) return WallpaperGallery.thumbnail(context, entry, width, height); }
        catch (Exception | OutOfMemoryError unavailable) { }
        return null;
    }
    private static String key(WallpaperGallery.Entry entry, int width, int height) { return entry.uri + ":" + entry.modified + ":" + width + "x" + height; }
    private static void recycle(Bitmap image) { if (image != null && !image.isRecycled()) image.recycle(); }
    private String folderLabel(WallpaperGallery.Entry entry) {
        return internalStorage.equals(entry.folderKey) ? (vi ? "Bộ nhớ trong" : "Internal storage") : entry.folderLabel;
    }

    private void showFolders() {
        if (!live() || foldersDialog != null) return;
        final Dialog picker = new Dialog(activity); picker.requestWindowFeature(Window.FEATURE_NO_TITLE); foldersDialog = picker;
        LinearLayout body = column(); body.setPadding(dp(18), dp(16), dp(18), dp(16)); body.setBackground(ClearGlass.panel(dp(22), density));
        body.addView(text(vi ? "Thư mục" : "Folders", 18, 0xffffffff), new LinearLayout.LayoutParams(-1, dp(36)));
        ScrollView scroll = new ScrollView(activity); scroll.setVerticalScrollBarEnabled(false); LinearLayout rows = column(); scroll.addView(rows);
        LinkedHashMap<String, String> names = new LinkedHashMap<>(); names.put("", vi ? "Mọi thư mục" : "All folders");
        for (WallpaperGallery.Entry item : all) names.put(item.folderKey, folderLabel(item));
        ArrayList<View> options = new ArrayList<>(); View current = null;
        for (java.util.Map.Entry<String, String> item : names.entrySet()) {
            final String id = item.getKey(), label = item.getValue();
            TextView option = button((id.equals(folderKey) ? "✓  " : "") + label, "gallery_folder:" + id);
            option.setGravity(Gravity.CENTER_VERTICAL | Gravity.START); option.setPadding(dp(14), 0, dp(14), 0);
            option.setOnClickListener(v -> { folderKey = id; folders.setText(label + " ▾"); showGallery(false); PopupOptimizer.dismissAnimated(picker, false); });
            LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, dp(48)); op.bottomMargin = dp(6); rows.addView(option, op); options.add(option);
            if (id.equals(folderKey)) current = option;
        }
        for (int i = 0; i < options.size(); i++) { View option = options.get(i); option.setNextFocusUpId(options.get(Math.max(0, i - 1)).getId()); option.setNextFocusDownId(options.get(Math.min(options.size() - 1, i + 1)).getId()); }
        body.addView(scroll, new LinearLayout.LayoutParams(-1, Math.min(dp(294), names.size() * dp(54))));
        Backdrop.wrap(activity, picker, body, true, 0); body.setLayoutParams(new FrameLayout.LayoutParams(Math.min(dp(440), panel.getLayoutParams().width - dp(40)), -2, Gravity.CENTER));
        picker.setOnDismissListener(d -> { foldersDialog = null; if (live()) folders.requestFocus(); });
        picker.show(); if (picker.getWindow() != null) picker.getWindow().setLayout(-1, -1);
        if (current != null) current.requestFocus();
    }

    private void requestAccess() {
        if (!live() || permissionPending) return;
        String[] permissions = WallpaperGallery.missingPermissions(app);
        if (permissions.length == 0) { scan(); return; }
        try {
            if (activity.getFragmentManager().isStateSaved()) return;
            Fragment previous = activity.getFragmentManager().findFragmentByTag(PERMISSION_TAG);
            if (previous != null) activity.getFragmentManager().beginTransaction().remove(previous).commitNow();
            PermissionFragment fragment = new PermissionFragment();
            activity.getFragmentManager().beginTransaction().add(fragment, PERMISSION_TAG).commitNow();
            permissionPending = true; fragment.requestPermissions(permissions, 130);
        } catch (RuntimeException unavailable) {
            permissionPending = false; message.setText(vi ? "Không mở được quyền ảnh. Bạn có thể chọn Tệp khác." : "Cannot request media access. Choose Other files.");
        }
    }

    public static final class PermissionFragment extends Fragment {
        @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
            super.onRequestPermissionsResult(request, permissions, grants);
            WallpaperImportPicker picker = active.get();
            if (request == 130 && picker != null && picker.activity == getActivity() && picker.live()) {
                picker.permissionPending = false; picker.userNavigated = false; picker.scan();
            }
            if (isAdded()) getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
        }
    }

    private void wire() {
        ArrayList<View> top = new ArrayList<>(); Collections.addAll(top, filters); top.add(folders);
        ArrayList<View> bottom = new ArrayList<>();
        if (allow.getVisibility() == View.VISIBLE) bottom.add(allow);
        bottom.add(external); bottom.add(cancel);
        for (int i = 0; i < top.size(); i++) {
            final int column = i;
            View v = top.get(i); v.setNextFocusLeftId(top.get(Math.max(0, i - 1)).getId()); v.setNextFocusRightId(top.get(Math.min(top.size() - 1, i + 1)).getId()); v.setNextFocusUpId(v.getId());
            v.setNextFocusDownId(filtered.isEmpty() ? footerFirst().getId() : (i == 3 ? choose.getId() : grid.getId()));
            v.setOnKeyListener((view, key, event) -> {
                if (key != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (column == 3 && selected != null) choose.requestFocus(); else focusGrid(column);
                }
                return true;
            });
        }
        choose.setNextFocusLeftId(grid.getId()); choose.setNextFocusRightId(choose.getId()); choose.setNextFocusUpId(folders.getId()); choose.setNextFocusDownId(external.getId());
        choose.setOnKeyListener((view, key, event) -> {
            if (key != KeyEvent.KEYCODE_DPAD_LEFT) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) focusGrid(selectedPosition);
            return true;
        });
        for (int i = 0; i < bottom.size(); i++) {
            View v = bottom.get(i); v.setNextFocusLeftId(bottom.get(Math.max(0, i - 1)).getId()); v.setNextFocusRightId(bottom.get(Math.min(bottom.size() - 1, i + 1)).getId());
            v.setNextFocusDownId(v.getId()); v.setNextFocusUpId(selected == null ? filters[0].getId() : grid.getId());
            v.setOnKeyListener((view, key, event) -> {
                if (key != KeyEvent.KEYCODE_DPAD_UP) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (filtered.isEmpty()) filters[0].requestFocus(); else focusGrid(selectedPosition);
                }
                return true;
            });
        }
    }

    private int dp(float value) { return Math.round(value * density); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(activity); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(activity); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private TextView text(String value, float size, int color) {
        TextView view = new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setTypeface(font);
        view.setSingleLine(); view.setEllipsize(TextUtils.TruncateAt.END); view.setIncludeFontPadding(false); view.setGravity(Gravity.CENTER_VERTICAL);
        ClearGlass.shadow(view, density); FontChoice.apply(view); return view;
    }
    private TextView button(String title, String tag) {
        TextView view = text(title, 12, 0xffffffff); view.setGravity(Gravity.CENTER); view.setPadding(dp(8), 0, dp(8), 0);
        view.setId(View.generateViewId()); view.setTag(tag); view.setFocusable(true); view.setClickable(true);
        view.setBackground(new ClearGlass.Plate(dp(14), density, -1)); return view;
    }
    private void addFooter(LinearLayout footer, View view, int width) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(width), dp(48)); params.setMarginStart(dp(6)); footer.addView(view, params);
    }
    private void rounded(View view, int radius) {
        view.setOutlineProvider(new ViewOutlineProvider() { @Override public void getOutline(View v, Outline outline) { outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(radius)); } }); view.setClipToOutline(true);
    }
    private static final class GalleryIcon extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        GalleryIcon(float density) { this.density = density; paint.setColor(0xb3ffffff); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.5f * density); paint.setStrokeJoin(Paint.Join.ROUND); }
        @Override public void draw(Canvas canvas) {
            float size = Math.min(Math.min(getBounds().width(), getBounds().height()), 27 * density), x = getBounds().exactCenterX() - size / 2, y = getBounds().exactCenterY() - size / 2;
            canvas.drawRoundRect(x + size * .1f, y + size * .15f, x + size * .9f, y + size * .85f, size * .1f, size * .1f, paint);
            canvas.drawCircle(x + size * .34f, y + size * .37f, size * .08f, paint);
            Path path = new Path(); path.moveTo(x + size * .13f, y + size * .72f); path.lineTo(x + size * .4f, y + size * .49f); path.lineTo(x + size * .58f, y + size * .65f); path.lineTo(x + size * .73f, y + size * .5f); path.lineTo(x + size * .88f, y + size * .66f); canvas.drawPath(path, paint);
        }
        @Override public int getIntrinsicWidth() { return Math.round(27 * density); }
        @Override public int getIntrinsicHeight() { return getIntrinsicWidth(); }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
