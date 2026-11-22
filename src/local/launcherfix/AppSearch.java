package local.launcherfix;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import com.gathin.tvhome.Backdrop;
import com.gathin.tvhome.ControlCenter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A transient TV app search; package enumeration and icon loading stay off the UI thread. */
public final class AppSearch {
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor WORK = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
        new LinkedBlockingQueue<Runnable>(), task -> new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            task.run();
        }, "launcher-app-search"));
    private static WeakReference<Session> active = new WeakReference<>(null);
    static { WORK.allowCoreThreadTimeOut(true); }
    private AppSearch() {}

    public static void show(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        Session old = active.get();
        if (old != null && old.dialog.isShowing()) return;
        Session session = new Session(activity);
        active = new WeakReference<>(session);
        session.show();
    }

    public static boolean dismiss(Activity activity) {
        Session session = active.get();
        if (session == null || session.owner != activity) return false;
        session.lifecycleClose = true;
        session.close();
        PopupOptimizer.dismissImmediate(session.dialog, false);
        return true;
    }

    public static boolean dismissFromBack(Activity activity) {
        Session session = active.get();
        if (session == null || session.owner != activity || session.closed) return false;
        CrystalSoundEngine.playClose(activity);
        PopupOptimizer.dismissAnimated(session.dialog, true);
        return true;
    }

    public static boolean isShowing(Activity activity) {
        Session session = active.get();
        return session != null && session.owner == activity && session.dialog.isShowing();
    }

    static final class App {
        final ComponentName component;
        final boolean television;
        final String label, searchable;
        final Drawable icon;
        App(ResolveInfo info, boolean television, String label, Drawable icon) {
            component = new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
            this.television = television; this.label = label; this.icon = icon;
            searchable = SearchMatcher.normalize(label);
        }
    }

    static List<App> load(Context context) {
        PackageManager pm = context.getPackageManager();
        LinkedHashMap<String,App> apps = new LinkedHashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            List<ResolveInfo> found = Catalog.query(context, pm, new Intent(Intent.ACTION_MAIN).addCategory(category), 0);
            for (ResolveInfo info : found) {
                if (Thread.currentThread().isInterrupted()) return Collections.emptyList();
                if (info.activityInfo == null || !info.activityInfo.exported) continue;
                String pkg = info.activityInfo.packageName;
                if (context.getPackageName().equals(pkg) || apps.containsKey(pkg)) continue;
                String label = pkg; Drawable icon = null;
                try {
                    CharSequence name = ImageCache.label(context, info, pm);
                    if (name != null && name.toString().trim().length() > 0) label = name.toString().trim();
                    icon = ImageCache.icon(context, info, pm);
                } catch (RuntimeException ignored) {}
                apps.put(pkg, new App(info, Intent.CATEGORY_LEANBACK_LAUNCHER.equals(category), label, icon));
            }
        }
        ArrayList<App> result = new ArrayList<>(apps.values());
        Collections.sort(result, (left, right) -> {
            int order = left.searchable.compareTo(right.searchable);
            return order != 0 ? order : left.component.flattenToString().compareTo(right.component.flattenToString());
        });
        return result;
    }

    private static final class Session {
        final Activity owner;
        final Dialog dialog;
        final float density;
        final boolean vi;
        final WallpaperTheme.Tone tone;
        final WeakReference<View> origin;
        final LinearLayout panel;
        final EditText input;
        final TextView status;
        final ListView results;
        final Rows adapter;
        List<App> apps = Collections.emptyList();
        final ArrayList<App> matches = new ArrayList<>();
        final java.util.HashSet<String> recentPinned = new java.util.HashSet<>();
        Future<?> loading;
        boolean closed, ready, failed, launched, lifecycleClose;
        int confirmKey = KeyEvent.KEYCODE_UNKNOWN;
        long confirmDownTime;

        Session(Activity activity) {
            owner = activity; density = activity.getResources().getDisplayMetrics().density;
            vi = "vi".equals(activity.getResources().getConfiguration().getLocales().get(0).getLanguage());
            ControlCenter center = ControlPanelHelper.getActiveCenter();
            tone = WallpaperTheme.current(center != null && center.activity == activity ? center : null);
            origin = new WeakReference<>(activity.getCurrentFocus());
            dialog = new Dialog(activity) {
                @Override public boolean dispatchKeyEvent(KeyEvent event) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) owner.onUserInteraction();
                    return super.dispatchKeyEvent(event);
                }
            };
            dialog.setOwnerActivity(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            panel = new LinearLayout(activity) {
                @Override protected void onMeasure(int width, int height) {
                    int available = View.MeasureSpec.getSize(height);
                    int target = px(382);
                    if (View.MeasureSpec.getMode(height) != View.MeasureSpec.UNSPECIFIED) target = Math.min(target, Math.max(0, available - px(24)));
                    super.onMeasure(View.MeasureSpec.makeMeasureSpec(Math.min(px(488), View.MeasureSpec.getSize(width)), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(target, View.MeasureSpec.EXACTLY));
                }
            };
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(px(20), px(18), px(20), px(16));
            panel.setBackground(WallpaperTheme.glass(density, tone));
            final int panelRadius = px(22);
            panel.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), panelRadius);
                }
            });
            panel.setClipToOutline(true);
            TextView title = text(vi ? "Tìm ứng dụng" : "Search apps", 18);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            panel.addView(title, new LinearLayout.LayoutParams(-1, px(28)));
            input = new EditText(activity);
            input.setId(View.generateViewId()); input.setTag("app_search_input");
            input.setSingleLine(true); input.setTextSize(16); input.setTextColor(Color.WHITE);
            input.setHintTextColor(0xffc2cbd3); input.setHint(vi ? "Nhập tên ứng dụng" : "Type an app name");
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            input.setShowSoftInputOnFocus(false);
            input.setPadding(px(12), 0, px(12), 0);
            input.setBackground(WallpaperTheme.states(density, tone));
            LinearLayout.LayoutParams inputLayout = new LinearLayout.LayoutParams(-1, px(46));
            inputLayout.topMargin = px(6); panel.addView(input, inputLayout);
            status = text(vi ? "Đang tải ứng dụng…" : "Loading apps…", 11);
            status.setTextColor(0xffcbd2da); status.setGravity(Gravity.CENTER_VERTICAL);
            panel.addView(status, new LinearLayout.LayoutParams(-1, px(26)));
            results = new ListView(activity); results.setId(View.generateViewId()); results.setTag("app_search_results");
            results.setDivider(new ColorDrawable(Color.TRANSPARENT)); results.setDividerHeight(px(4));
            results.setSelector(new ColorDrawable(Color.TRANSPARENT)); results.setCacheColorHint(Color.TRANSPARENT);
            results.setVerticalScrollBarEnabled(false); results.setOverScrollMode(View.OVER_SCROLL_NEVER);
            results.setItemsCanFocus(false); results.setNextFocusUpId(input.getId());
            adapter = new Rows(); results.setAdapter(adapter);
            panel.addView(results, new LinearLayout.LayoutParams(-1, 0, 1));
            input.setNextFocusDownId(results.getId());
            input.setOnFocusChangeListener((view, focused) -> { if (!focused) confirmKey = KeyEvent.KEYCODE_UNKNOWN; });
            input.setOnClickListener(view -> keyboard().showSoftInput(input, InputMethodManager.SHOW_IMPLICIT));
            input.setOnKeyListener((view, key, event) -> {
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) focusResults();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                        confirmKey = key; confirmDownTime = event.getDownTime();
                    } else if (event.getAction() == KeyEvent.ACTION_UP) {
                        boolean confirmed = confirmKey == key && confirmDownTime == event.getDownTime() && !event.isCanceled();
                        confirmKey = KeyEvent.KEYCODE_UNKNOWN;
                        if (confirmed) keyboard().showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
                    }
                    return true;
                }
                return false;
            });
            input.setOnEditorActionListener((view, action, event) -> {
                if (action != EditorInfo.IME_ACTION_SEARCH && action != EditorInfo.IME_ACTION_DONE) return false;
                hideKeyboard(); focusResults(); return true;
            });
            input.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence value, int start, int before, int count) { filter(); }
                @Override public void afterTextChanged(Editable value) {}
            });
            results.setOnKeyListener((view, key, event) -> {
                if (key == KeyEvent.KEYCODE_DPAD_UP && results.getSelectedItemPosition() <= 0) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) input.requestFocus();
                    return true;
                }
                return false;
            });
            results.setOnItemClickListener((parent, view, position, id) -> {
                if (position >= 0 && position < matches.size()) launch(matches.get(position));
            });
            Backdrop.wrap(activity, dialog, panel, true, 0);
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            FontChoice.apply(panel);
            dialog.setOnDismissListener(ignored -> close());
        }

        int px(float value) { return Math.round(value * density); }
        TextView text(String value, int size) {
            TextView text = new TextView(owner); text.setText(value); text.setTextColor(Color.WHITE);
            text.setTextSize(size); text.setSingleLine(true); text.setEllipsize(TextUtils.TruncateAt.END); return text;
        }
        InputMethodManager keyboard() { return (InputMethodManager) owner.getSystemService(Context.INPUT_METHOD_SERVICE); }
        void hideKeyboard() { keyboard().hideSoftInputFromWindow(input.getWindowToken(), 0); }
        void show() {
            dialog.show(); dialog.getWindow().setLayout(-1, -1); input.requestFocus();
            final Context app = owner.getApplicationContext();
            final WeakReference<Session> reference = new WeakReference<>(this);
            loading = WORK.submit(() -> {
                List<App> loaded = Collections.emptyList(); boolean failure = false;
                try { loaded = load(app); }
                catch (RuntimeException | OutOfMemoryError error) { failure = true; Log.w("TvHome.AppSearch", "Cannot load apps", error); }
                final List<App> data = loaded; final boolean error = failure;
                UI.post(() -> {
                    Session target = reference.get();
                    if (target == null || target.closed || target.owner.isFinishing() || target.owner.isDestroyed()) return;
                    target.loading = null; target.apps = data; target.failed = error; target.ready = true; target.filter();
                });
            });
        }
        void filter() {
            if (closed || !ready) return;
            String query = SearchMatcher.normalize(input.getText().toString());
            matches.clear();
            recentPinned.clear();
            if (query.isEmpty()) {
                List<String> recents = RecentApps.list(owner);
                for (String pkg : recents) {
                    if (recentPinned.size() >= 4) break;
                    for (App app : apps) {
                        if (pkg.equals(app.component.getPackageName())) {
                            matches.add(app);
                            recentPinned.add(pkg);
                            break;
                        }
                    }
                }
                for (App app : apps) {
                    if (!recentPinned.contains(app.component.getPackageName())) matches.add(app);
                }
            } else {
                for (App app : apps) if (SearchMatcher.matches(app.searchable, query)) matches.add(app);
            }
            adapter.notifyDataSetChanged();
            String countLabel = matches.size() + (vi ? " ứng dụng" : matches.size() == 1 ? " app" : " apps");
            if (!recentPinned.isEmpty()) {
                countLabel = (vi ? "Gần đây · " : "Recent · ") + countLabel;
            }
            status.setText(failed ? (vi ? "Không tải được ứng dụng. Mở lại để thử." : "Could not load apps. Reopen to retry.") :
                matches.isEmpty() ? (vi ? "Không tìm thấy ứng dụng" : "No apps found") : countLabel);
            if (matches.isEmpty() && results.hasFocus()) input.requestFocus();
            results.setSelection(0);
        }
        void focusResults() {
            hideKeyboard();
            if (!matches.isEmpty()) { results.requestFocus(); results.setSelection(0); }
        }
        void launch(App app) {
            Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            if (app.television) intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            hideKeyboard();
            try {
                launched = true;
                RecentApps.record(owner, app.component.getPackageName());
                owner.startActivity(intent);
                close();
                dialog.dismiss();
            }
            catch (ActivityNotFoundException | SecurityException error) {
                launched = false;
                Toast.makeText(owner, vi ? "Không mở được " + app.label : "Could not open " + app.label, Toast.LENGTH_SHORT).show();
            }
        }
        void close() {
            if (closed) return;
            closed = true; hideKeyboard(); panel.animate().cancel();
            if (loading != null) { loading.cancel(true); if (loading instanceof Runnable) WORK.remove((Runnable) loading); loading = null; }
            if (active.get() == this) active = new WeakReference<>(null);
            apps = Collections.emptyList(); matches.clear(); recentPinned.clear(); results.setAdapter(null);
            if (!launched && !lifecycleClose && !owner.isFinishing() && !owner.isDestroyed()) {
                owner.onUserInteraction();
                CrystalSoundEngine.scheduleAmbientCheck(owner);
                View previous = origin.get();
                if (!launched && owner.hasWindowFocus() && previous != null && previous.isAttachedToWindow()) previous.requestFocus();
            }
        }
        final class Rows extends BaseAdapter {
            @Override public int getCount() { return matches.size(); }
            @Override public Object getItem(int position) { return matches.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                LinearLayout row;
                if (recycled instanceof LinearLayout) row = (LinearLayout) recycled;
                else {
                    row = new LinearLayout(owner); row.setGravity(Gravity.CENTER_VERTICAL); row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setPadding(px(12), 0, px(12), 0);
                    row.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, px(44)));
                    StateListDrawable background = new StateListDrawable();
                    background.addState(new int[]{android.R.attr.state_pressed}, WallpaperTheme.focusWash(px(12), tone, true));
                    background.addState(new int[]{android.R.attr.state_selected}, WallpaperTheme.focusWash(px(12), tone, false));
                    background.addState(new int[]{}, WallpaperTheme.tileGlass(px(12), tone)); row.setBackground(background);
                    ImageView icon = new ImageView(owner); icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    row.addView(icon, new LinearLayout.LayoutParams(px(30), px(30)));
                    TextView label = text("", 14);
                    LinearLayout.LayoutParams labelLayout = new LinearLayout.LayoutParams(0, -1, 1); labelLayout.leftMargin = px(12);
                    label.setGravity(Gravity.CENTER_VERTICAL); row.addView(label, labelLayout);
                    TextView badge = text(vi ? "Gần đây" : "Recent", 10);
                    badge.setTextColor(0xb0ffffff);
                    badge.setGravity(Gravity.CENTER);
                    badge.setPadding(px(8), 0, px(8), 0);
                    GradientDrawable badgeBg = new GradientDrawable();
                    badgeBg.setCornerRadius(px(9));
                    badgeBg.setColor(0x1cffffff);
                    badge.setBackground(badgeBg);
                    LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(-2, px(18));
                    badgeLp.leftMargin = px(8);
                    row.addView(badge, badgeLp);
                    FontChoice.apply(row);
                }
                App app = matches.get(position);
                ((ImageView)row.getChildAt(0)).setImageDrawable(app.icon);
                ((TextView)row.getChildAt(1)).setText(app.label);
                boolean isRecent = recentPinned.contains(app.component.getPackageName());
                row.getChildAt(2).setVisibility(isRecent ? View.VISIBLE : View.GONE);
                row.setContentDescription(app.label); return row;
            }
        }
    }
}
