package local.launcherfix;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;
import com.gathin.tvhome.AppAdapter;
import com.gathin.tvhome.ClearGlass;
import com.gathin.tvhome.ControlCenter;
import com.gathin.tvhome.LiquidGlassRoot;
import com.gathin.tvhome.LiquidTune;
import com.gathin.tvhome.MainActivity;
import com.gathin.tvhome.SettingsPopup;
import com.gathin.tvhome.WallpaperPlayer;
import android.app.Dialog;
import android.content.DialogInterface;
import java.lang.ref.WeakReference;
import java.util.Locale;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.FrameLayout;
import com.gathin.tvhome.LiquidPillDrawable;
import com.gathin.tvhome.Shamsi;
import com.gathin.tvhome.WallpaperPopup;
import com.gathin.tvhome.WallpaperPage;
import java.io.File;
import java.util.List;
import java.util.Random;
import android.os.Build;
import android.os.SystemClock;
import java.util.concurrent.TimeUnit;

/** Native Control Panel hooks, dock and app focus behavior. */
public final class ControlPanelHelper {
    public static final String TAG_PANEL_V2 = "cc_bento_v2";
    public static final String TAG_RAM_STATE = "cc_ram_state";
    public static final String TAG_NET_SUB = "cc_net_sub";
    public static final String TAG_CLEAN_RAM = "cc_clean_ram";
    public static final String TAG_DOCK_CARD = "cc_dock_card";
    public static final String TAG_HOME_SETTINGS = "cc_home_settings";
    public static final String TAG_TV_SETTINGS = "cc_tv_settings";
    public static final String TAG_CLOCK_PILL = "clock_pill_dock";
    public static final String TAG_DOCK_TIME = "dock_time";
    public static final String TAG_DOCK_AMPM = "dock_ampm";
    public static final String TAG_DOCK_AUDIO = "dock_audio_output";
    public static final String TAG_DOCK_SEARCH = "dock_search";
    public static final String TAG_DOCK_PICTURE = "dock_picture";
    public static final String TAG_DOCK_CLEAN_RAM = "dock_clean_ram";
    public static final String TAG_DOCK_WALLPAPER = "dock_wallpaper";

    private static final int[] PICTURE_CYCLE = {7, 9, 3, 10, 2};
    private static final int[] BACKLIGHT_PRESETS = {25, 50, 75, 100};

    private static WeakReference<ControlCenter> activeCenter = new WeakReference<>(null);
    public static volatile ControlCenter activeCenterInstance = null;
    public static volatile WeakReference<View> lastClockPill = new WeakReference<>(null);
    private static WeakReference<SettingsPopup> activeSettingsPopup = new WeakReference<>(null);
    private static final java.util.WeakHashMap<ControlCenter,Boolean> revealed = new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<ControlCenter,CachedPanel> sCachedPanels = new java.util.WeakHashMap<>();
    private static volatile android.os.Handler sMainHandler;
    private static android.os.Handler mainHandler() {
        android.os.Handler h = sMainHandler;
        if (h == null) {
            android.os.Looper l = android.os.Looper.getMainLooper();
            if (l != null) {
                h = new android.os.Handler(l);
                sMainHandler = h;
            }
        }
        return h;
    }
    private static final java.util.concurrent.ThreadPoolExecutor TELEMETRY_WORKER = createTelemetryWorker();
    private static java.util.concurrent.ThreadPoolExecutor createTelemetryWorker() {
        java.util.concurrent.ThreadPoolExecutor exec = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 30L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(() -> {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    r.run();
                }, "cc-telemetry");
                t.setDaemon(true);
                return t;
            });
        exec.allowCoreThreadTimeOut(true);
        return exec;
    }
    static boolean telemetryWorkerAllowsCoreTimeout() {
        return TELEMETRY_WORKER.allowsCoreThreadTimeOut();
    }
    private static final java.util.concurrent.atomic.AtomicBoolean sTelemetryBusy =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile TelemetrySnapshot sTelemetry = new TelemetrySnapshot();

    private static final class TelemetrySnapshot {
        boolean valid;
        String pictureMode;
        String network;
        boolean canSetBacklight;
        int backlight = -1;
        int volume = 0;
        int maxVolume = 15;
        boolean muted;
        long availRamMb = -1L;
        int ramPctFree = 0;
    }

    private static final class CachedPanel {
        final LinearLayout panel;
        final LinearLayout topRow, bottomRow;
        final View firstButton, backlightSlider, volumeSlider, pictureModule;
        final TextView networkState, backlightValue, volumeValue, pictureValue;
        final GradientDrawable networkDot;
        final ClearGlass.Plate backlightGlass, volumeGlass;
        final boolean vi;
        final float dp;
        long fontEpoch;

        CachedPanel(ControlCenter c, LinearLayout built, long fontEpoch) {
            this.panel = built;
            this.topRow = c.topRow;
            this.bottomRow = c.bottomRow;
            this.firstButton = c.firstButton;
            this.backlightSlider = c.backlightSlider;
            this.volumeSlider = c.volumeSlider;
            this.pictureModule = c.pictureModule;
            this.networkState = c.networkState;
            this.backlightValue = c.backlightValue;
            this.volumeValue = c.volumeValue;
            this.pictureValue = c.pictureValue;
            this.networkDot = c.networkDot;
            this.backlightGlass = c.backlightGlass;
            this.volumeGlass = c.volumeGlass;
            this.vi = c.vi;
            this.dp = c.dp;
            this.fontEpoch = fontEpoch;
        }

        void detach() {
            panel.animate().cancel();
            panel.setAlpha(1f);
            panel.setTranslationY(0f);
            panel.setScaleX(1f);
            panel.setScaleY(1f);
            if (panel.getParent() instanceof ViewGroup) {
                ((ViewGroup) panel.getParent()).removeView(panel);
            }
        }

        void restoreTo(ControlCenter c) {
            detach();
            c.panel = panel;
            c.topRow = topRow;
            c.bottomRow = bottomRow;
            c.firstButton = firstButton;
            c.backlightSlider = backlightSlider;
            c.volumeSlider = volumeSlider;
            c.pictureModule = pictureModule;
            c.networkState = networkState;
            c.backlightValue = backlightValue;
            c.volumeValue = volumeValue;
            c.pictureValue = pictureValue;
            c.networkDot = networkDot;
            c.backlightGlass = backlightGlass;
            c.volumeGlass = volumeGlass;
            if (fontEpoch != FontChoice.epoch()) {
                FontChoice.apply(panel);
                fontEpoch = FontChoice.epoch();
            }
            ReferenceControlPanel.position(c);
        }
    }

    private ControlPanelHelper() {}

    private static final class ClockDock extends FrameLayout {
        ClockDock(Context context) { super(context); setAlpha(1f); }
        @Override public void setAlpha(float alpha) {
            // Native panel callbacks must not reveal the dock during screensaver.
            super.setAlpha(CrystalSoundEngine.isAmbientActive() ? 0f : alpha);
        }
    }

    public static void onConstruct(ControlCenter center) {
        if (center != null) {
            activeCenter = new WeakReference<>(center);
        }
    }

    public static ControlCenter getActiveCenter() {
        return activeCenter != null ? activeCenter.get() : null;
    }

    public static SettingsPopup getActiveSettingsPopup() {
        return activeSettingsPopup.get();
    }

    private static int px(float dp, float value) {
        return Math.round(value * dp);
    }

    private static String t(boolean vi, String viStr, String enStr) {
        return vi ? viStr : enStr;
    }

    public static void updateClockFormat(TextClock tc) {
        if (tc == null) return;
        tc.setFormat24Hour("HH:mm:ss");
        tc.setFormat12Hour("h:mm:ss");
    }

    private static TextView makeText(Context ctx, float dp, String str, float sp, int color, Typeface tf) {
        TextView tv = new TextView(ctx);
        tv.setText(str);
        tv.setTextSize(2, sp);
        tv.setTextColor(color);
        tv.setTypeface(tf != null ? tf : Typeface.DEFAULT);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setIncludeFontPadding(false);
        ClearGlass.shadow(tv, dp);
        return tv;
    }

    public static void onBuild(ControlCenter center) {
        if (center == null) return;
        activeCenter = new WeakReference<>(center);
        if (center.panel != null) {
            ReferenceControlPanel.position(center);
        }
        if (center.activity != null && center.dialog != null) {
            // CachedPanel already applied FontChoice once; skip redundant DecorView font tree walk.
            CrystalSoundEngine.attachDialog(center.activity, center.dialog, false);
        }
    }

    public static void ensureShot(ControlCenter center) {
        if (center == null || center.activity == null) return;
        if (center.backdrop != null) {
            center.backdrop.setColorFilter(null);
        }
        DisplayMetrics dm = center.activity.getResources().getDisplayMetrics();
        int targetW = PopupOptimizer.captureWidth();
        int targetH = PopupOptimizer.captureHeight(dm.widthPixels, dm.heightPixels);
        if (center.shot != null && !center.shot.isRecycled()) {
            if (center.shot.getWidth() == targetW && center.shot.getHeight() == targetH) return;
            center.shot.recycle();
        }
        center.shot = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
    }

    public static void releaseShot(ControlCenter center) {
        if (center == null) return;
        if (center.open) {
            return;
        }
        CachedPanel cached = sCachedPanels.get(center);
        if (cached != null) {
            cached.detach();
        }
        if (center.backdrop != null) {
            center.backdrop.setImageBitmap(null);
        }
        if (center.shot != null) {
            if (!center.shot.isRecycled()) {
                center.shot.recycle();
            }
            center.shot = null;
        }
    }

    public static void safeBlurShot(ControlCenter center) {
        if (center == null || !center.open) return;
        Bitmap shot = center.shot;
        if (shot == null || shot.isRecycled()) return;
        PopupOptimizer.blurAndDimInPlace(shot);
    }

    public static void trimMemory(int level) {
        if (level >= 20 || level == 10 || level == 15) {
            ControlCenter center = activeCenter.get();
            if (center != null && !center.open) {
                releaseShot(center);
            }
        }
        if (level >= 40 || level == 15) {
            ControlCenter center = activeCenter.get();
            if (center == null || !center.open) {
                for (CachedPanel cp : sCachedPanels.values()) {
                    if (cp != null) cp.detach();
                }
                sCachedPanels.clear();
            }
        }
    }

    public static void preShow(ControlCenter center) {
        if (center == null) return;
        // 1. If dialog is not actually showing, open flag MUST NOT be true
        if (center.dialog == null || !center.dialog.isShowing()) {
            center.open = false;
        }
        // 2. If dialog instance exists but is not showing (zombie from hide()), dismiss cleanly so build() recreates a fresh one
        if (center.dialog != null && !center.dialog.isShowing()) {
            CachedPanel cached = sCachedPanels.get(center);
            if (cached != null) cached.detach();
            try {
                center.dialog.dismiss();
            } catch (Throwable ignored) {}
            center.dialog = null;
        }
    }

    public static boolean ownsControlCenterDialog(Dialog dialog) {
        if (dialog == null) return false;
        ControlCenter c = activeCenter == null ? null : activeCenter.get();
        return (c != null && c.dialog == dialog)
            || (activeCenterInstance != null && activeCenterInstance.dialog == dialog);
    }

    public static void onCloseDialog(ControlCenter center, Dialog dialog) {
        if (center != null) {
            center.open = false;
            DeviceInfoDialog.dismissForActivity(center.activity);
            CachedPanel cached = sCachedPanels.get(center);
            if (cached != null) cached.detach();
            center.dialog = null;
            releaseShot(center);
        }
        if (dialog != null) {
            try {
                if (dialog.isShowing()) dialog.dismiss();
            } catch (Throwable ignored) {}
        }
    }

    public static void animateClose(final ControlCenter center, final Dialog dialog) {
        if (center == null || dialog == null || !dialog.isShowing()) {
            onCloseDialog(center, dialog);
            return;
        }
        if (center.activity != null) {
            CrystalSoundEngine.playClose(center.activity);
        }
        final LinearLayout panel = center.panel;
        if (!PopupOptimizer.motionEnabled(center.activity)
                || PopupOptimizer.isActivityInactive(center.activity)
                || panel == null || !panel.isAttachedToWindow()) {
            onCloseDialog(center, dialog);
            return;
        }
        final View bg = center.backdrop;
        final View sc = center.screen;
        if (bg != null && bg.getVisibility() == View.VISIBLE) {
            bg.animate().cancel();
            bg.animate()
                .alpha(0f)
                .setDuration(205L)
                .setInterpolator(PopupOptimizer.EXIT_EASE)
                .start();
        }
        if (sc != null) {
            sc.animate().cancel();
            sc.animate()
                .alpha(0f)
                .setDuration(205L)
                .setInterpolator(PopupOptimizer.EXIT_EASE)
                .start();
        }
        panel.animate().cancel();
        panel.animate()
            .alpha(0f)
            .scaleX(0.968f)
            .scaleY(0.968f)
            .translationY(-8f * center.dp)
            .setDuration(210L)
            .setInterpolator(PopupOptimizer.EXIT_EASE)
            .withLayer()
            .withEndAction(new Runnable() {
                @Override
                public void run() {
                    if (!center.open && center.dialog == dialog) {
                        onCloseDialog(center, dialog);
                    }
                }
            })
            .start();
        android.os.Handler mh = mainHandler();
        if (mh != null) {
            mh.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!center.open && center.dialog == dialog && dialog.isShowing()) {
                        onCloseDialog(center, dialog);
                    }
                }
            }, 265L);
        }
    }

    public static void onShow(final ControlCenter center) {
        if (center == null || center.activity == null) return;
        activeCenter = new WeakReference<>(center);
        activeCenterInstance = center;
        revealed.remove(center);
        if (center.dialog != null) {
            CrystalSoundEngine.attachDialog(center.activity, center.dialog, false);
        } else {
            CrystalSoundEngine.playOpen(center.activity);
        }
        ensureShot(center);
        center.callCapture(true);

        // Tight 22ms (single-frame) fallback so slow/dropped PixelCopy never stalls input-to-open.
        if (center.activity.getWindow() != null) {
            View decor = center.activity.getWindow().getDecorView();
            if (decor != null) {
                decor.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (center.open) {
                            if (center.dialog == null || !center.dialog.isShowing()) {
                                center.callReveal(false);
                            }
                        }
                    }
                }, 22L);
            }
        }
    }

    public static void animateReveal(final ControlCenter center, final View backdropOrScreen) {
        if (center == null || center.panel == null || backdropOrScreen == null) return;
        final LinearLayout panel = center.panel;
        if (!PopupOptimizer.motionEnabled(center.activity)) {
            backdropOrScreen.animate().cancel();
            backdropOrScreen.setAlpha(1f);
            panel.animate().cancel();
            panel.setAlpha(1f);
            panel.setTranslationY(0f);
            panel.setScaleX(1f);
            panel.setScaleY(1f);
            return;
        }

        backdropOrScreen.animate().cancel();
        backdropOrScreen.setAlpha(0f);
        panel.animate().cancel();
        panel.setAlpha(0f);
        panel.setTranslationY(-11f * center.dp);
        panel.setScaleX(0.962f);
        panel.setScaleY(0.962f);

        // Wait 1 VSync after Dialog.show() allocates the WindowSurface so frame 0 starts cleanly at t=0.
        panel.postOnAnimation(new Runnable() {
            @Override
            public void run() {
                if (!center.open || !panel.isAttachedToWindow()) return;
                backdropOrScreen.animate()
                    .alpha(1f)
                    .setDuration(255L)
                    .setInterpolator(PopupOptimizer.ENTRANCE_EASE)
                    .start();
                panel.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(285L)
                    .setInterpolator(PopupOptimizer.ENTRANCE_EASE)
                    .withLayer()
                    .start();
            }
        });
    }

    public static void onReveal(ControlCenter center) {
        if (center == null || center.activity == null) return;
        boolean withShot = center.backdrop != null && center.backdrop.getVisibility() == View.VISIBLE;
        if (!withShot && center.screen != null) {
            center.screen.setBackgroundColor(0x440A0F16);
        }
        if (center.panel != null) WallpaperTheme.applyCurrent(center);
        revealed.put(center,Boolean.TRUE);
    }

    /** A late PixelCopy updates the backdrop without restarting the panel or moving focus. */
    public static boolean updateLateBackdrop(ControlCenter center, boolean withShot) {
        if (center == null || !Boolean.TRUE.equals(revealed.get(center)) || center.dialog == null
                || !center.dialog.isShowing()) return false;
        Bitmap shot=center.shot;
        if (withShot && center.backdrop != null && shot != null && !shot.isRecycled()) {
            ImageView image=center.backdrop;
            image.setColorFilter(null);
            image.animate().cancel();
            image.setImageBitmap(shot);
            image.setVisibility(View.VISIBLE);
            image.setAlpha(0f);
            image.animate().alpha(1f).setDuration(150L).withEndAction(() -> {
                if (center.open && center.backdrop == image && center.shot == shot && center.screen != null)
                    center.screen.setBackground(null);
            }).start();
        }
        return true;
    }

    public static LinearLayout buildPanel(final ControlCenter center) {
        activeCenter = new WeakReference<>(center);
        CachedPanel cached = sCachedPanels.get(center);
        if (cached != null && cached.vi == center.vi && cached.dp == center.dp) {
            cached.restoreTo(center);
            return cached.panel;
        }
        LinearLayout built = ReferenceControlPanel.build(center);
        FontChoice.apply(built);
        sCachedPanels.put(center, new CachedPanel(center, built, FontChoice.epoch()));
        return built;
    }

    public static void refreshValuesFast(final ControlCenter center) {
        if (center == null || center.panel == null || center.activity == null) return;
        TelemetrySnapshot snap = sTelemetry;
        if (snap.valid) {
            applyTelemetrySnapshot(center, snap);
        } else {
            // Seed benign placeholder labels without Binder IPC on frame 0
            if (center.pictureValue != null && center.pictureValue.getVisibility() != View.VISIBLE) {
                center.pictureValue.setText(t(center.vi, "Chế độ TV", "TV preset"));
                center.pictureValue.setVisibility(View.VISIBLE);
            }
            if (center.backlightValue != null && center.backlightValue.getVisibility() != View.VISIBLE) {
                center.backlightValue.setText(t(center.vi, "Mở cài đặt", "Settings"));
                center.backlightValue.setVisibility(View.VISIBLE);
            }
        }
        requestAsyncTelemetryRefresh(center);
    }

    private static void requestAsyncTelemetryRefresh(final ControlCenter center) {
        if (center == null || center.activity == null) return;
        if (!sTelemetryBusy.compareAndSet(false, true)) return;
        final WeakReference<ControlCenter> ref = new WeakReference<>(center);
        final Context appCtx = center.activity.getApplicationContext();
        TELEMETRY_WORKER.execute(() -> {
            try {
                ControlCenter c = ref.get();
                if (c == null || c.activity == null || c.activity.isDestroyed() || c.activity.isFinishing()) return;
                final TelemetrySnapshot next = collectTelemetry(c, appCtx);
                sTelemetry = next;
                android.os.Handler mh = mainHandler();
                if (mh != null) {
                    mh.post(() -> {
                        ControlCenter live = ref.get();
                        if (live != null && live.open && live.panel != null
                                && live.activity != null && !live.activity.isDestroyed()) {
                            applyTelemetrySnapshot(live, next);
                        }
                    });
                }
            } catch (Throwable ignored) {
            } finally {
                sTelemetryBusy.set(false);
            }
        });
    }

    private static TelemetrySnapshot collectTelemetry(ControlCenter center, Context ctx) {
        TelemetrySnapshot s = new TelemetrySnapshot();
        s.pictureMode = center.callPictureMode();
        s.network = center.callNetwork();
        try {
            s.canSetBacklight = center.activity.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == 0;
        } catch (RuntimeException ignored) {
            s.canSetBacklight = false;
        }
        s.backlight = center.callGlobal("picture_backlight", -1);
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                s.maxVolume = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                s.volume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                s.muted = am.isStreamMute(AudioManager.STREAM_MUSIC);
            }
        } catch (RuntimeException ignored) {}
        int[] pct = new int[]{0};
        s.availRamMb = queryAvailableRamMb(ctx, pct);
        s.ramPctFree = pct[0];
        s.valid = true;
        return s;
    }

    private static void setValueView(TextView tv, String value) {
        if (tv == null) return;
        if (value == null) {
            if (tv.getVisibility() != View.GONE) tv.setVisibility(View.GONE);
            return;
        }
        if (!TextUtils.equals(tv.getText(), value)) tv.setText(value);
        if (tv.getVisibility() != View.VISIBLE) tv.setVisibility(View.VISIBLE);
    }

    private static String spokenLabel(String title, String val) {
        return TextUtils.isEmpty(val) ? title : (title + ", " + val);
    }

    private static void applyTelemetrySnapshot(ControlCenter center, TelemetrySnapshot s) {
        if (center == null || center.panel == null || s == null) return;
        boolean vi = center.vi;

        // 1. Picture mode
        setValueView(center.pictureValue, s.pictureMode);
        if (center.pictureModule != null) {
            center.pictureModule.setContentDescription(spokenLabel(t(vi, "Hình ảnh", "Picture"), s.pictureMode));
        }

        // 2. Network
        setValueView(center.networkState, s.network);
        String offlineStr = t(vi, "Không có mạng", "Offline");
        boolean online = s.network != null && !s.network.equals(offlineStr);
        if (center.networkDot != null) {
            center.networkDot.setColor(online ? 0xFFFFFFFF : 0x66FFFFFF);
        }

        // 3. Backlight
        center.canSetBacklight = s.canSetBacklight;
        center.callShowBacklight(s.backlight);

        // 4. Volume
        if (center.volumeSlider != null) {
            String volStr = s.muted ? t(vi, "Tắt tiếng", "Muted") : String.valueOf(s.volume);
            setValueView(center.volumeValue, volStr);
            if (center.volumeGlass != null) {
                float lvl = s.muted ? 0f : Math.min(1f, s.volume / (float) Math.max(1, s.maxVolume));
                center.volumeGlass.setLevel(lvl);
            }
            center.volumeSlider.setContentDescription(spokenLabel(t(vi, "Âm lượng", "Volume"), volStr));
        }

        // 5. Labels & RAM subtitle
        if (center.pictureValue != null && center.pictureValue.getVisibility() != View.VISIBLE) {
            center.pictureValue.setText(t(vi, "Chế độ TV", "TV preset"));
            center.pictureValue.setVisibility(View.VISIBLE);
        }
        if (center.backlightValue != null) {
            if (center.backlightValue.getVisibility() == View.VISIBLE) {
                CharSequence cur = center.backlightValue.getText();
                if (cur != null && cur.length() > 0 && cur.charAt(cur.length() - 1) != '%') {
                    center.backlightValue.setText(cur + "%");
                }
            } else {
                center.backlightValue.setText(t(vi, "Mở cài đặt", "Settings"));
                center.backlightValue.setVisibility(View.VISIBLE);
            }
        }
        View netSub = center.panel.findViewWithTag(TAG_NET_SUB);
        if (netSub instanceof TextView && netSub != center.networkState && center.networkState != null) {
            CharSequence netText = center.networkState.getText();
            if (!TextUtils.isEmpty(netText)) {
                ((TextView) netSub).setText(netText);
            }
        }
        applyRamLabel(center, s.availRamMb);
    }

    private static void scheduleWarmControlPanel(final ControlCenter center) {
        if (center == null || center.activity == null) return;
        android.os.Handler mh = mainHandler();
        if (mh == null) return;
        mh.postDelayed(() -> {
            if (center.activity == null || center.activity.isFinishing() || center.activity.isDestroyed()) return;
            if (center.activity instanceof MainActivity && !((MainActivity) center.activity).started) return;
            requestAsyncTelemetryRefresh(center);
            android.os.Looper.myQueue().addIdleHandler(() -> {
                if (center.activity != null && !center.activity.isFinishing() && !center.activity.isDestroyed()
                        && (! (center.activity instanceof MainActivity) || ((MainActivity) center.activity).started)
                        && !center.open && center.dialog == null && !sCachedPanels.containsKey(center)) {
                    try {
                        buildPanel(center);
                        CachedPanel cp = sCachedPanels.get(center);
                        if (cp != null) cp.detach();
                        center.panel = null;
                    } catch (Throwable ignored) {}
                }
                return false;
            });
        }, 900L);
    }

    public static void onRefreshValues(ControlCenter center) {
        if (center == null || center.panel == null || center.activity == null) return;
        boolean vi = center.vi;

        // 1. Ensure pictureValue always has a helpful label
        if (center.pictureValue != null && center.pictureValue.getVisibility() != View.VISIBLE) {
            center.pictureValue.setText(t(vi, "Chế độ TV", "TV preset"));
            center.pictureValue.setVisibility(View.VISIBLE);
        }

        // 2. Ensure backlightValue always has a helpful label
        if (center.backlightValue != null) {
            if (center.backlightValue.getVisibility() == View.VISIBLE) {
                CharSequence cur = center.backlightValue.getText();
                if (cur != null && cur.length() > 0 && cur.charAt(cur.length() - 1) != '%') {
                    center.backlightValue.setText(cur + "%");
                }
            } else {
                center.backlightValue.setText(t(vi, "Mở cài đặt", "Settings"));
                center.backlightValue.setVisibility(View.VISIBLE);
            }
        }

        // 3. Sync Network subtitle in Bento card
        View netSub = center.panel.findViewWithTag(TAG_NET_SUB);
        if (netSub instanceof TextView && netSub != center.networkState && center.networkState != null) {
            CharSequence netText = center.networkState.getText();
            if (!TextUtils.isEmpty(netText)) {
                ((TextView) netSub).setText(netText);
            }
        }

        // 4. Sync Live RAM status in Header.
        refreshRamStatus(center);
    }

    public static long queryAvailableRamMb(Context ctx, int[] outPercentFree) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return -1L;
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long availMb = mi.availMem / (1024L * 1024L);
            if (outPercentFree != null && outPercentFree.length > 0 && mi.totalMem > 0) {
                outPercentFree[0] = Math.round((mi.availMem * 100.0f) / mi.totalMem);
            }
            return availMb;
        } catch (RuntimeException unused) {
            return -1L;
        }
    }

    public static void refreshRamStatus(ControlCenter center) {
        if (center == null || center.panel == null || center.activity == null) return;
        int[] pct = new int[]{0};
        long availMb = queryAvailableRamMb(center.activity, pct);
        applyRamLabel(center, availMb);
    }

    private static void applyRamLabel(ControlCenter center, long availMb) {
        if (center == null || center.panel == null) return;
        View view = center.panel.findViewWithTag(TAG_RAM_STATE);
        if (!(view instanceof TextView)) return;
        TextView ramTv = (TextView) view;
        if (availMb < 0) {
            ramTv.setVisibility(View.GONE);
            return;
        }
        boolean vi = center.vi;
        long uptimeMs = SystemClock.elapsedRealtime();
        long hours = TimeUnit.MILLISECONDS.toHours(uptimeMs);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(uptimeMs) % 60;
        String uptimeStr = hours > 0 ? (hours + (vi ? "g " : "h ") + minutes + (vi ? "p" : "m")) : (minutes + (vi ? "p" : "m"));
        String ramStr = availMb >= 1024
            ? String.format(Locale.US, "%.1f GB", availMb / 1024.0f)
            : (availMb + " MB");
        String label = (vi ? "Bật " : "Up ") + uptimeStr + " • " + ramStr + (vi ? " trống" : " free");
        if (!TextUtils.equals(ramTv.getText(), label)) {
            ramTv.setText(label);
        }
        if (ramTv.getVisibility() != View.VISIBLE) {
            ramTv.setVisibility(View.VISIBLE);
        }
    }

    public static void cycleOrOpenPicture(ControlCenter center) {
        if (center == null || center.activity == null) return;
        if (center.canSetBacklight) {
            int cur = center.callGlobal("picture_mode", -1);
            if (cur >= 0) {
                int next = PICTURE_CYCLE[0];
                for (int i = 0; i < PICTURE_CYCLE.length; i++) {
                    if (PICTURE_CYCLE[i] == cur) {
                        next = PICTURE_CYCLE[(i + 1) % PICTURE_CYCLE.length];
                        break;
                    }
                }
                try {
                    Settings.Global.putInt(center.activity.getContentResolver(), "picture_mode", next);
                    String modeName = center.callPictureMode();
                    if (center.pictureValue != null && modeName != null) {
                        center.pictureValue.setText(modeName);
                        center.pictureValue.setVisibility(View.VISIBLE);
                    }
                    sTelemetry.pictureMode = modeName;
                    requestAsyncTelemetryRefresh(center);
                    return;
                } catch (SecurityException unused) {
                    center.canSetBacklight = false;
                }
            }
        }
        center.callOpenPicture();
    }

    public static void cycleOrOpenBacklight(ControlCenter center) {
        if (center == null || center.activity == null) return;
        if (center.canSetBacklight) {
            int cur = center.callGlobal("picture_backlight", -1);
            if (cur >= 0) {
                int next = BACKLIGHT_PRESETS[0];
                for (int preset : BACKLIGHT_PRESETS) {
                    if (preset > cur + 2) {
                        next = preset;
                        break;
                    }
                }
                try {
                    Settings.Global.putInt(center.activity.getContentResolver(), "picture_backlight", next);
                    center.callShowBacklight(next);
                    onRefreshValues(center);
                    sTelemetry.backlight = next;
                    requestAsyncTelemetryRefresh(center);
                    return;
                } catch (SecurityException unused) {
                    center.canSetBacklight = false;
                }
            }
        }
        center.callOpenPicture();
    }

    public static void toggleMute(ControlCenter center) {
        if (center == null || center.activity == null) return;
        try {
            AudioManager am = (AudioManager) center.activity.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                boolean muted = am.isStreamMute(AudioManager.STREAM_MUSIC);
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    muted ? AudioManager.ADJUST_UNMUTE : AudioManager.ADJUST_MUTE,
                    0
                );
                center.callShowVolume();
                sTelemetry.muted = !muted;
                requestAsyncTelemetryRefresh(center);
            }
        } catch (SecurityException unused) {
            center.callOpenTv(".device.sound.SoundActivity", "android.settings.SOUND_SETTINGS");
        }
    }

    public static void cleanMemoryNow(final ControlCenter center) {
        if (center == null || center.activity == null) return;
        final Activity act = center.activity;
        final int[] pctBefore = new int[]{0};
        final long beforeMb = queryAvailableRamMb(act, pctBefore);

        if (act instanceof MainActivity) {
            MemoryTrimmer.trim((MainActivity) act, 80);
        } else {
            ImageCache.trim(80);
        }
        releaseShot(center);

        // Android Performance Patterns: Run System.gc() in background worker
        // to prevent freezing the UI thread during Stop-The-World garbage collection.
        android.os.AsyncTask.THREAD_POOL_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                System.gc();
                final int[] pctAfter = new int[]{0};
                final long afterMb = queryAvailableRamMb(act, pctAfter);
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refreshRamStatus(center);
                        long delta = Math.max(0L, afterMb - beforeMb);
                        boolean vi = center.vi;
                        String msg;
                        if (delta > 0) {
                            msg = vi
                                ? ("Đã dọn bộ nhớ đệm (+" + delta + " MB) • Trống " + afterMb + " MB")
                                : ("Cleaned cache (+" + delta + " MB) • Free " + afterMb + " MB");
                        } else {
                            msg = vi
                                ? ("Đã giải phóng bộ nhớ • Trống " + afterMb + " MB (" + pctAfter[0] + "%)")
                                : ("Memory optimized • Free " + afterMb + " MB (" + pctAfter[0] + "%)");
                        }
                        Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    /** Register the existing settings popup for HOME/BACK dismissal. */
    public static void onSettingsPopupBuilt(SettingsPopup popup) {
        if (popup != null) activeSettingsPopup = new WeakReference<>(popup);
    }

    public static final class ExtraGlyph extends Drawable {
        public static final int CLEAN_RAM = 1;
        public static final int CLOSE = 2;
        public static final int SEARCH = 3;

        private final int kind;
        private final int size;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF oval = new RectF();

        public ExtraGlyph(int kind, float dp, int sizeDp) {
            this.kind = kind;
            this.size = Math.round(sizeDp * dp);
            this.line.setColor(0xFFFFFFFF);
            this.line.setStyle(Paint.Style.STROKE);
            this.line.setStrokeCap(Paint.Cap.ROUND);
            this.line.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public int getIntrinsicWidth() {
            return this.size;
        }

        @Override
        public int getIntrinsicHeight() {
            return this.size;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float scale = Math.min(b.width(), b.height()) / 24.0f;
            canvas.save();
            float half = scale * 12.0f;
            canvas.translate(b.exactCenterX() - half, b.exactCenterY() - half);
            canvas.scale(scale, scale);
            this.line.setStrokeWidth(1.85f);
            this.path.reset();

            if (this.kind == CLOSE) {
                // 'X' close icon
                this.line.setStrokeWidth(2.2f);
                canvas.drawLine(7.0f, 7.0f, 17.0f, 17.0f, this.line);
                canvas.drawLine(17.0f, 7.0f, 7.0f, 17.0f, this.line);
            } else if (this.kind == SEARCH) {
                canvas.drawCircle(10.5f, 10.5f, 6.0f, this.line);
                canvas.drawLine(15.0f, 15.0f, 20.0f, 20.0f, this.line);
            } else {
                // RAM chip / memory booster icon
                this.oval.set(5.0f, 6.5f, 19.0f, 17.5f);
                canvas.drawRoundRect(this.oval, 2.0f, 2.0f, this.line);
                canvas.drawLine(8.5f, 10.0f, 8.5f, 14.0f, this.line);
                canvas.drawLine(12.0f, 10.0f, 12.0f, 14.0f, this.line);
                canvas.drawLine(15.5f, 10.0f, 15.5f, 14.0f, this.line);
                // Top & bottom pins
                canvas.drawLine(8.0f, 4.0f, 8.0f, 6.5f, this.line);
                canvas.drawLine(12.0f, 4.0f, 12.0f, 6.5f, this.line);
                canvas.drawLine(16.0f, 4.0f, 16.0f, 6.5f, this.line);
                canvas.drawLine(8.0f, 17.5f, 8.0f, 20.0f, this.line);
                canvas.drawLine(12.0f, 17.5f, 12.0f, 20.0f, this.line);
                canvas.drawLine(16.0f, 17.5f, 16.0f, 20.0f, this.line);
            }
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            this.line.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            this.line.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return -3;
        }
    }

    public static boolean clearDockGlass(View owner) {
        return TAG_CLOCK_PILL.equals(owner.getTag()) && owner.hasFocus();
    }

    static Drawable makeDockItemHighlight(float dp) {
        StateListDrawable sld = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0x30FFFFFF);
        focused.setShape(GradientDrawable.RECTANGLE);
        focused.setCornerRadius(9.0f * dp);
        sld.addState(new int[]{android.R.attr.state_focused}, focused);
        sld.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
        return sld;
    }

    static void attachDockFocusMotion(final View v, final float dp, final float focusScale) {
        if (v == null) return;
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                View pill = view.getRootView().findViewWithTag(TAG_CLOCK_PILL);
                if (pill != null) pill.invalidate();
                view.animate().cancel();
                if (hasFocus) {
                    view.animate()
                        .scaleX(focusScale)
                        .scaleY(focusScale)
                        .translationY(-1.5f * dp)
                        .translationZ(4.0f * dp)
                        .setDuration(185L)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                        .start();
                } else {
                    view.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .translationY(0.0f)
                        .translationZ(0.0f)
                        .setDuration(130L)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                        .start();
                }
            }
        });
    }

    static ImageView makeDockButton(Context ctx, float dp, Drawable icon, String tag, String desc) {
        ImageView iv = new ImageView(ctx);
        iv.setTag(tag);
        iv.setFocusable(true);
        iv.setClickable(true);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setImageDrawable(icon);
        iv.setContentDescription(desc);
        iv.setBackground(makeDockItemHighlight(dp));
        attachDockFocusMotion(iv, dp, 1.16f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(dp, 24.0f), px(dp, 22.0f));
        lp.setMarginStart(px(dp, 3.0f));
        iv.setLayoutParams(lp);
        return iv;
    }

    private static String getWallpaperSubtitle(Activity act) {
        try {
            WallpaperCollectionHelper.Collection col = WallpaperCollectionHelper.getActiveCollection(act);
            if (col != null) {
                return col.name + " (" + col.count() + ")";
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static void rotateWallpaper(ControlCenter center) {
        if(center!=null)WallpaperCollectionHelper.rotateWallpaper(center.activity);
    }

    public static void addClockPill(final ControlCenter center) {
        if (center == null || center.activity == null) return;
        final Activity act = center.activity;
        final float dp = center.dp;
        final boolean vi = center.vi;
        final Typeface fontMed = center.callFont("font_medium");

        View root = act.findViewById(act.getResources().getIdentifier("root", "id", act.getPackageName()));
        if (!(root instanceof FrameLayout)) return;

        // Disable focus and hide header, topButtons, and leftBlock
        View topButtons = act.findViewById(act.getResources().getIdentifier("topButtons", "id", act.getPackageName()));
        if (topButtons != null) {
            topButtons.setVisibility(View.GONE);
            if (topButtons instanceof ViewGroup) {
                ViewGroup tbGroup = (ViewGroup) topButtons;
                tbGroup.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                tbGroup.setFocusable(false);
                for (int i = 0; i < tbGroup.getChildCount(); i++) {
                    View child = tbGroup.getChildAt(i);
                    child.setFocusable(false);
                    child.setFocusableInTouchMode(false);
                    child.setClickable(false);
                }
            }
        }
        View leftBlock = act.findViewById(act.getResources().getIdentifier("leftBlock", "id", act.getPackageName()));
        if (leftBlock != null) {
            leftBlock.setVisibility(View.GONE);
            leftBlock.setFocusable(false);
        }
        if (center.header instanceof ViewGroup) {
            ViewGroup headerGroup = (ViewGroup) center.header;
            headerGroup.setVisibility(View.GONE);
            headerGroup.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            headerGroup.setFocusable(false);
            for (int i = 0; i < headerGroup.getChildCount(); i++) {
                View child = headerGroup.getChildAt(i);
                child.setFocusable(false);
                child.setFocusableInTouchMode(false);
            }
        } else if (center.header != null) {
            center.header.setVisibility(View.GONE);
            center.header.setFocusable(false);
        }

        View existing = ((FrameLayout) root).findViewWithTag(TAG_CLOCK_PILL);
        if (existing != null) {
            ((FrameLayout) root).removeView(existing);
        }

        final FrameLayout frameLayout = new ClockDock(act);
        frameLayout.setTag(TAG_CLOCK_PILL);
        frameLayout.setBackground(new LiquidPillDrawable(frameLayout));
        frameLayout.setElevation(px(dp, 16.0f));
        frameLayout.setTranslationZ(px(dp, 16.0f));

        LinearLayout dock = new LinearLayout(act);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setBaselineAligned(false);
        dock.setClipChildren(false);
        dock.setClipToPadding(false);

        // 1. CLOCK (with seconds: HH:mm:ss / h:mm:ss)
        LinearLayout btnClock = new LinearLayout(act);
        btnClock.setTag(TAG_DOCK_TIME);
        btnClock.setOrientation(LinearLayout.HORIZONTAL);
        btnClock.setGravity(Gravity.CENTER_VERTICAL);
        btnClock.setFocusable(true);
        btnClock.setClickable(true);
        btnClock.setBackground(makeDockItemHighlight(dp));
        btnClock.setPadding(px(dp, 6.0f), px(dp, 2.0f), px(dp, 6.0f), px(dp, 2.0f));

        TextClock tcTime = new TextClock(act);
        updateClockFormat(tcTime);
        tcTime.setTextSize(2, 12.0f);
        tcTime.setTextColor(0xFFFFFFFF);
        tcTime.setTypeface(fontMed);
        tcTime.setFontFeatureSettings("tnum");
        tcTime.setLetterSpacing(0.02f);
        ClearGlass.shadow(tcTime, dp);
        btnClock.addView(tcTime);

        TextClock tcAmpm = new TextClock(act);
        tcAmpm.setTag(TAG_DOCK_AMPM);
        tcAmpm.setFormat24Hour("");
        tcAmpm.setFormat12Hour("a");
        tcAmpm.setTextSize(2, 8.5f);
        tcAmpm.setTextColor(0xD0FFFFFF);
        tcAmpm.setTypeface(fontMed);
        tcAmpm.setLetterSpacing(0.06f);
        ClearGlass.shadow(tcAmpm, dp);
        tcAmpm.setVisibility(DateFormat.is24HourFormat(act) ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams ampmLp = new LinearLayout.LayoutParams(-2, -2);
        ampmLp.setMarginStart(px(dp, 3.0f));
        btnClock.addView(tcAmpm, ampmLp);

        LinearLayout.LayoutParams statsLp = new LinearLayout.LayoutParams(-2, px(dp, 18.0f));
        statsLp.setMarginStart(px(dp, 6.0f));
        btnClock.addView(DockStats.create(act, frameLayout, dp, fontMed, vi), statsLp);

        btnClock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                center.returnTo = v;
                center.callShow();
            }
        });
        attachDockFocusMotion(btnClock, dp, 1.05f);
        dock.addView(btnClock);

        // 2. DIVIDER
        View div = new View(act);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(px(dp, 1.0f), px(dp, 14.0f));
        divLp.setMarginStart(px(dp, 6.0f));
        divLp.setMarginEnd(px(dp, 6.0f));
        div.setBackgroundColor(0x35FFFFFF);
        dock.addView(div, divLp);

        // 3. ACTION BUTTONS: Media, Search, Audio Output, Picture Mode, Clean RAM, Change Wallpaper
        View btnMedia = MiniPlayer.create(act, frameLayout, dp, fontMed, vi);
        dock.addView(btnMedia);

        ImageView btnSearch = makeDockButton(act, dp, new ExtraGlyph(ExtraGlyph.SEARCH, dp, 12), TAG_DOCK_SEARCH, vi ? "Tìm ứng dụng" : "Search apps");
        btnSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppSearch.show(act);
            }
        });
        dock.addView(btnSearch);

        ImageView btnAudio = makeDockButton(act, dp, new ControlCenter.Glyph(ControlCenter.Glyph.SOUND, dp, 12), TAG_DOCK_AUDIO, vi ? "Đầu ra âm thanh" : "Audio output");
        btnAudio.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AudioOutput.show(act, v, dp, vi);
            }
        });
        btnAudio.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                v.performClick();
                return true;
            }
        });
        dock.addView(btnAudio);

        ImageView btnPicture = makeDockButton(act, dp, new ControlCenter.Glyph(ControlCenter.Glyph.PICTURE, dp, 12), TAG_DOCK_PICTURE, vi ? "Chế độ hình ảnh" : "Picture Mode");
        btnPicture.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleOrOpenPicture(center);
                String mode = center.callPictureMode();
                if (mode != null && mode.length() > 0) {
                    String msg = (vi ? "Chế độ hình ảnh: " : "Picture mode: ") + mode;
                    Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
                }
            }
        });
        btnPicture.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                center.callOpenPicture();
                return true;
            }
        });
        dock.addView(btnPicture);

        ImageView btnCleanRam = makeDockButton(act, dp, new ExtraGlyph(ExtraGlyph.CLEAN_RAM, dp, 12), TAG_DOCK_CLEAN_RAM, vi ? "Dọn RAM" : "Clean RAM");
        btnCleanRam.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cleanMemoryNow(center);
            }
        });
        dock.addView(btnCleanRam);

        ImageView btnWallpaper = makeDockButton(act, dp, new ControlCenter.Glyph(ControlCenter.Glyph.WALLPAPER, dp, 12), TAG_DOCK_WALLPAPER, vi ? "Đổi hình nền" : "Change Wallpaper");
        btnWallpaper.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                try {
                    v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80L).withLayer().withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100L).withLayer().start();
                        }
                    }).start();
                } catch (Throwable ignored) {}
                rotateWallpaper(center);
            }
        });
        btnWallpaper.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                center.callOpenWallpaper();
                return true;
            }
        });
        dock.addView(btnWallpaper);

        // Connect D-pad IDs
        btnClock.setId(View.generateViewId());
        btnMedia.setId(View.generateViewId());
        btnSearch.setId(View.generateViewId());
        btnAudio.setId(View.generateViewId());
        btnPicture.setId(View.generateViewId());
        btnCleanRam.setId(View.generateViewId());
        btnWallpaper.setId(View.generateViewId());

        btnClock.setNextFocusRightId(btnMedia.getId());
        btnMedia.setNextFocusLeftId(btnClock.getId());
        btnMedia.setNextFocusRightId(btnSearch.getId());
        btnSearch.setNextFocusLeftId(btnMedia.getId());
        btnSearch.setNextFocusRightId(btnAudio.getId());
        btnAudio.setNextFocusLeftId(btnSearch.getId());
        btnAudio.setNextFocusRightId(btnPicture.getId());
        btnPicture.setNextFocusLeftId(btnAudio.getId());
        btnPicture.setNextFocusRightId(btnCleanRam.getId());
        btnCleanRam.setNextFocusLeftId(btnPicture.getId());
        btnCleanRam.setNextFocusRightId(btnWallpaper.getId());
        btnWallpaper.setNextFocusLeftId(btnCleanRam.getId());

        View gridView = act.findViewById(act.getResources().getIdentifier("grid", "id", act.getPackageName()));
        int gridId = gridView != null ? gridView.getId() : View.NO_ID;
        if (gridId != View.NO_ID) {
            btnClock.setNextFocusDownId(gridId);
            btnMedia.setNextFocusDownId(gridId);
            btnSearch.setNextFocusDownId(gridId);
            btnAudio.setNextFocusDownId(gridId);
            btnPicture.setNextFocusDownId(gridId);
            btnCleanRam.setNextFocusDownId(gridId);
            btnWallpaper.setNextFocusDownId(gridId);
            if (gridView != null) {
                gridView.setNextFocusUpId(btnClock.getId());
                if (gridView instanceof androidx.recyclerview.widget.RecyclerView) {
                    // Balanced offscreen ViewHolder cache for low-RAM TV
                    ((androidx.recyclerview.widget.RecyclerView) gridView).setItemViewCacheSize(6);
                }
                final View finalGridView = gridView;
                gridView.setOnGenericMotionListener(new View.OnGenericMotionListener() {
                    @Override
                    public boolean onGenericMotion(View v, MotionEvent event) {
                        if (event.getAction() == MotionEvent.ACTION_SCROLL) {
                            float vscroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                            if (vscroll > 0.0f && !finalGridView.canScrollVertically(-1)) {
                                if (act instanceof MainActivity) {
                                    return focusDockFromGrid((MainActivity) act, 0);
                                }
                            }
                        }
                        return false;
                    }
                });
            }
        }

        btnClock.setOnKeyListener(makeDockNavListener(act, center, null, btnMedia));
        btnMedia.setOnKeyListener(makeDockNavListener(act, center, btnClock, btnSearch));
        btnSearch.setOnKeyListener(makeDockNavListener(act, center, btnMedia, btnAudio));
        btnAudio.setOnKeyListener(makeDockNavListener(act, center, btnSearch, btnPicture));
        btnPicture.setOnKeyListener(makeDockNavListener(act, center, btnAudio, btnCleanRam));
        btnCleanRam.setOnKeyListener(makeDockNavListener(act, center, btnPicture, btnWallpaper));
        btnWallpaper.setOnKeyListener(makeDockNavListener(act, center, btnCleanRam, null));

// Dock buttons focus listeners

        int padH = px(dp, 10.0f);
        int padBottom = px(dp, 4.0f);
        dock.setPadding(padH, padBottom, padH, padBottom);

        frameLayout.setOnGenericMotionListener(new View.OnGenericMotionListener() {
            @Override
            public boolean onGenericMotion(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_SCROLL) {
                    float vscroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    if (vscroll > 0.0f) {
                        center.returnTo = v;
                        center.callShow();
                        return true;
                    }
                }
                return false;
            }
        });
        frameLayout.addView(dock, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        FontChoice.apply(frameLayout);
        FrameLayout.LayoutParams flLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        flLp.topMargin = px(dp, 2.0f);
        ((FrameLayout) root).addView(frameLayout, flLp);
        frameLayout.bringToFront();

        center.clockPill = frameLayout;
        activeCenter = new WeakReference<>(center);
        lastClockPill = new WeakReference<>((View) frameLayout);
        ControlCenter.shownPill = new WeakReference<>(frameLayout);
        ControlCenter.stylePill();
        scheduleWarmControlPanel(center);
    }


    public static boolean focusDockFromGrid(MainActivity main, int position) {
        if (main == null) return false;
        try {
            View pill = main.getWindow().getDecorView().findViewWithTag(TAG_CLOCK_PILL);
            if (pill == null && lastClockPill != null) pill = lastClockPill.get();
            if (pill instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) pill;
                View target = null;
                if (position >= 5) {
                    target = vg.findViewWithTag(TAG_DOCK_WALLPAPER);
                } else if (position == 4) {
                    target = vg.findViewWithTag(TAG_DOCK_CLEAN_RAM);
                } else if (position == 3) {
                    target = vg.findViewWithTag(TAG_DOCK_PICTURE);
                } else if (position == 2) {
                    target = vg.findViewWithTag(TAG_DOCK_AUDIO);
                } else if (position == 1) {
                    target = vg.findViewWithTag(TAG_DOCK_SEARCH);
                }
                if (target == null) {
                    target = vg.findViewWithTag(TAG_DOCK_TIME);
                }
                if (target != null && target.requestFocus()) {
                    return true;
                }
                if (pill.requestFocus()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static View.OnKeyListener makeDockNavListener(final Activity act, final ControlCenter center, final View left, final View right) {
        return new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        center.returnTo = v;
                        center.callShow();
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        if (act instanceof MainActivity) {
                            MainActivity main = (MainActivity) act;
                            if (main.grid != null) {
                                main.focusTile(Math.max(0,MainActivity.lastFocused));
                                return true;
                            }
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        if (left != null) {
                            left.requestFocus();
                            return true;
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        if (right != null) {
                            right.requestFocus();
                            return true;
                        }
                    }
                }
                return false;
            }
        };
    }

    public static void ensureClockPillVisible(final Activity act) {
        if (act == null || act.isFinishing()) return;
        try {
            ControlCenter center = activeCenter != null ? activeCenter.get() : null;
            if (center != null && center.activity == act && center.open) return;
            View root = act.findViewById(act.getResources().getIdentifier("root", "id", act.getPackageName()));
            View pill = act.getWindow().getDecorView().findViewWithTag(TAG_CLOCK_PILL);
            if (pill == null && lastClockPill != null) {
                pill = lastClockPill.get();
            }

            if (pill == null && root instanceof FrameLayout) {
                if (center == null) {
                    center = new ControlCenter(act);
                    activeCenter = new WeakReference<>(center);
                }
                addClockPill(center);
                pill = center.clockPill;
            }

            final float dp = act.getResources().getDisplayMetrics().density;
            if (CrystalSoundEngine.isAmbientActive()) {
                return;
            }
            CrystalSoundEngine.scheduleAmbientCheck(act);
            if (pill != null) {
                pill.setAlpha(1.0f);
                pill.setVisibility(View.VISIBLE);
                pill.setElevation(dp * 24.0f);
                pill.setTranslationZ(dp * 24.0f);
                pill.bringToFront();
                pill.requestLayout();
                pill.invalidate();
            }
            if (center != null && center.clockPill != null && center.clockPill != pill) {
                center.clockPill.setAlpha(1.0f);
                center.clockPill.setVisibility(View.VISIBLE);
                center.clockPill.setElevation(dp * 24.0f);
                center.clockPill.setTranslationZ(dp * 24.0f);
                center.clockPill.bringToFront();
            }
            View topButtons = act.findViewById(act.getResources().getIdentifier("topButtons", "id", act.getPackageName()));
            if (topButtons != null) {
                topButtons.setVisibility(View.GONE);
                topButtons.setFocusable(false);
            }
            View leftBlock = act.findViewById(act.getResources().getIdentifier("leftBlock", "id", act.getPackageName()));
            if (leftBlock != null) {
                leftBlock.setVisibility(View.GONE);
                leftBlock.setFocusable(false);
            }
            if (center != null && center.header != null) {
                center.header.setVisibility(View.GONE);
                center.header.setFocusable(false);
            }

            DockStats.refreshVisibility(act);

            final View finalPill = pill;
            final View decor = act.getWindow() != null ? act.getWindow().getDecorView() : null;
            if (decor != null) {
                int[] delays = new int[]{0, 80, 200, 500};
                for (int d = 0; d < delays.length; d++) {
                    final int delay = delays[d];
                    decor.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (act.isFinishing() || CrystalSoundEngine.isAmbientActive()) return;
                                ControlCenter current = getActiveCenter();
                                if (current != null && current.activity == act && current.open) return;
                                View p = act.getWindow().getDecorView().findViewWithTag(TAG_CLOCK_PILL);
                                if (p == null && finalPill != null) p = finalPill;
                                if (p != null) {
                                    p.setAlpha(1.0f);
                                    p.setVisibility(View.VISIBLE);
                                    p.setElevation(dp * 24.0f);
                                    p.setTranslationZ(dp * 24.0f);
                                    p.bringToFront();
                                    DockStats.refreshVisibility(act);
                                }
                            } catch (Throwable ignored) {}
                        }
                    }, delay);
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void onDestroyControlCenter(ControlCenter center) {
        try {
            if (center != null) {
                center.open = false;
                DeviceInfoDialog.dismissForActivity(center.activity);
                CachedPanel cached = sCachedPanels.remove(center);
                if (cached != null) cached.detach();
                if (center.clockPill != null) {
                    center.clockPill.setAlpha(1.0f);
                    center.clockPill.setVisibility(View.VISIBLE);
                }
                if (center.shot != null && !center.shot.isRecycled()) {
                    center.shot.recycle();
                    center.shot = null;
                }
                if (center.backdrop != null) {
                    center.backdrop.setImageBitmap(null);
                }
            }
            if (activeCenter != null && activeCenter.get() == center) {
                activeCenter.clear();
            }
            activeCenterInstance = null;
        } catch (Throwable ignored) {}
    }

    public static void onDestroy(MainActivity activity) {
        cancelWallpaperImportPicker(activity);
        MiniPlayer.dismiss(activity);
        DockMusicPill.onDestroy(activity);
        FolderUi.dismiss(activity);
        AppSearch.dismiss(activity);
        DeviceInfoDialog.dismissForActivity(activity);
        DockStats.onDestroy(activity);
        WallpaperCollectionHelper.cancelActions(activity);
        WallpaperCollectionHelper.cancelPopup(activity);
        WallpaperIcons.destroy(activity);
        AvatarStore.cancelReturn(activity);
        CollectionDialog.dismissForActivity(activity);
        try {
            ControlCenter center = activeCenter != null ? activeCenter.get() : null;
            if (center != null && (activity == null || center.activity == activity)) {
                onDestroyControlCenter(center);
            }
            if (activity != null) {
                if (activity.openPopup != null) {
                    PopupOptimizer.dismissPopupImmediate(activity.openPopup);
                    activity.openPopup = null;
                }
                if (activity.appLibrary != null) {
                    try {
                        PopupOptimizer.dismissPopupImmediate(activity.appLibrary.popup);
                        activity.appLibrary.close();
                    } catch (Throwable ignored) {}
                    activity.appLibrary = null;
                }
            }
            WallpaperThumbLoader.trimMemory(80);
        } catch (Throwable ignored) {}
    }

    public static boolean onBackPressed(MainActivity main) {
        if (main == null) return false;
        if (WallpaperImportPicker.dismissFromBack(main)) return true;
        if (MiniPlayer.dismissFromBack(main)) return true;
        if (FolderUi.dismissFromBack(main)) return true;
        if (AppSearch.dismissFromBack(main)) return true;
        if (DeviceInfoDialog.dismissFromBack(main)) return true;
        boolean pendingWallpaper = WallpaperCollectionHelper.cancelActions(main);
        pendingWallpaper = WallpaperCollectionHelper.cancelPopup(main) || pendingWallpaper;
        if (AvatarStore.dismissChooserFromBack(main)) return true;
        if (CollectionDialog.dismissFromBack(main)) return true;
        try {
            // 1. Dismiss any open PopupWindow
            if (main.openPopup != null) {
                try {
                    if (main.openPopup.isShowing()) {
                        main.openPopup.dismiss();
                    }
                } catch (Throwable ignored) {}
                main.openPopup = null;
                ensureClockPillVisible(main);
                return true;
            }
            // 2. Close AppLibraryDialog if open
            if (main.appLibrary != null) {
                try {
                    main.appLibrary.close();
                } catch (Throwable ignored) {}
                main.appLibrary = null;
                main.openPopup = null;
                main.libraryFocusPending = false;
                MainActivity.lastFocused = 0;
                MainActivity.lastFocusedKey = null;
                if (main.grid != null) {
                    main.grid.scrollToPosition(0);
                }
                main.focusTile(0);
                ensureClockPillVisible(main);
                return true;
            }
            // 3. Close ControlCenter if open
            ControlCenter center = activeCenter != null ? activeCenter.get() : null;
            if (center != null && center.open) {
                try {
                    center.callClose();
                } catch (Throwable ignored) {}
                ensureClockPillVisible(main);
                return true;
            }
            // 4. Close SettingsPopup if open
            SettingsPopup sp = activeSettingsPopup.get();
            if (sp != null && sp.dialog != null) {
                try {
                    if (sp.dialog.isShowing()) {
                        CrystalSoundEngine.playClose(main);
                        PopupOptimizer.dismissAnimated(sp.dialog, true);
                        ensureClockPillVisible(main);
                        return true;
                    }
                } catch (Throwable ignored) {}
            }
            // 5. Cancel reordering if active
            if (main.reordering) {
                main.endReorder();
                ensureClockPillVisible(main);
                return true;
            }
            // 6. Return to top (Dock) if scrolled down or focused beyond top
            boolean canScrollUp = false;
            try {
                if (main.grid != null) {
                    canScrollUp = main.grid.canScrollVertically(-1);
                }
            } catch (Throwable ignored) {}

            boolean notAtTop = canScrollUp || MainActivity.lastFocused > 0 || main.libraryFocusPending;
            if (notAtTop) {
                main.libraryFocusPending = false;
                MainActivity.lastFocused = 0;
                MainActivity.lastFocusedKey = null;
                if (main.grid != null) {
                    main.grid.scrollToPosition(0);
                }
                main.focusTile(0);
                ensureClockPillVisible(main);
                return true;
            }
        } catch (Throwable ignored) {}
        ensureClockPillVisible(main);
        return pendingWallpaper;
    }

    public static final int REQ_WALLPAPER_IMPORT = 29036;
    private static long galleryLaunchEpoch;
    private static java.lang.ref.WeakReference<Activity> galleryOwner = new java.lang.ref.WeakReference<>(null);

    public static void cancelWallpaperImportPicker(Activity activity) {
        if (activity == null) return;
        if (galleryOwner.get() == activity) {
            galleryLaunchEpoch++;
            galleryOwner.clear();
            LiveWallpaper.cancelVideoPick(activity);
        }
        WallpaperImportPicker.dismissForActivity(activity);
    }

    public static void startWallpaperImport(final Object popupObj, final Activity activity) {
        if (activity == null) return;
        if (popupObj instanceof WallpaperPopup) {
            try {
                ((WallpaperPopup) popupObj).closeFromHelper(new Runnable() {
                    @Override
                    public void run() {
                        launchPicker(activity);
                    }
                });
                return;
            } catch (Throwable ignored) {}
        }
        launchPicker(activity);
    }

    private static void launchPicker(Activity activity) {
        launchWallpaperPicker(activity, REQ_WALLPAPER_IMPORT);
    }

    public static boolean launchWallpaperPicker(Activity activity, int requestCode) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        if (!(activity instanceof MainActivity) && !(activity instanceof com.gathin.tvhome.LauncherSettingsActivity))
            return launchSystemWallpaperPicker(activity, requestCode);
        final long token = ++galleryLaunchEpoch;
        galleryOwner = new java.lang.ref.WeakReference<>(activity);
        LiveWallpaper.beginVideoPick(activity);
        // Settings closes its current dialog after this method returns.
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (token != galleryLaunchEpoch || activity.isFinishing() || activity.isDestroyed()) {
                if (token == galleryLaunchEpoch) cancelWallpaperImportPicker(activity);
                return;
            }
            WallpaperImportPicker.Listener listener = new WallpaperImportPicker.Listener() {
                public void onPicked(android.net.Uri uri) { finish(Activity.RESULT_OK, new Intent().setData(uri)); }
                public void onCancelled() { finish(Activity.RESULT_CANCELED, null); }
                public void onExternal() {
                    if (token != galleryLaunchEpoch) return;
                    if (!launchSystemWallpaperPicker(activity, requestCode)) finish(Activity.RESULT_CANCELED, null);
                }
                private void finish(int result, Intent data) {
                    if (token != galleryLaunchEpoch) return;
                    galleryOwner.clear();
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        LiveWallpaper.cancelVideoPick(activity);
                        return;
                    }
                    if (activity instanceof MainActivity)
                        handleActivityResult((MainActivity) activity, requestCode, result, data);
                    else ((com.gathin.tvhome.LauncherSettingsActivity) activity).onActivityResult(requestCode, result, data);
                }
            };
            if (!WallpaperImportPicker.show(activity, listener)) listener.onCancelled();
        });
        return true;
    }

    private static boolean launchSystemWallpaperPicker(Activity activity, int requestCode) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/mp4"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            LiveWallpaper.beginVideoPick(activity);
            activity.startActivityForResult(intent, requestCode);
            return true;
        } catch (Throwable t) {
            try {
                intent.setAction(Intent.ACTION_GET_CONTENT);
                activity.startActivityForResult(intent, requestCode);
                return true;
            } catch (Throwable t2) {
                LiveWallpaper.cancelVideoPick(activity);
                boolean vi = "vi".equals(Locale.getDefault().getLanguage());
                Toast.makeText(activity, vi ? "Không tìm thấy trình chọn ảnh hoặc MP4 trên TV"
                    : "No image or MP4 picker on this TV", Toast.LENGTH_SHORT).show();
                return false;
            }
        }
    }

    public static boolean handleActivityResult(final MainActivity main, int requestCode, int resultCode, final Intent data) {
        if (FontImport.handleActivityResult(main, requestCode, resultCode, data)) return true;
        if (AvatarStore.handleActivityResult(main, requestCode, resultCode, data)) return true;
        if (requestCode != REQ_WALLPAPER_IMPORT) {
            return false;
        }
        LiveWallpaper.cancelVideoPick(main);
        if (main == null || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return true;
        }
        final android.net.Uri uri = data.getData();
        boolean vi = "vi".equals(Locale.getDefault().getLanguage());
        Toast.makeText(main, vi ? "Đang nhập ảnh hoặc MP4..." : "Importing image or MP4...", Toast.LENGTH_SHORT).show();

        WallpaperCollectionHelper.importWallpaper(main,uri);

        return true;
    }

    public static void onNewIntent(MainActivity main, Intent intent) {
        if (main == null) return;
        try {
            // Dismiss child settings activity if lingering
            main.finishActivity(0x2a);

            if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) {
                onHomePressed(main);
            }
            ensureClockPillVisible(main);
        } catch (Throwable ignored) {}
    }

    public static void onHomePressed(MainActivity main) {
        if (main == null) return;
        cancelWallpaperImportPicker(main);
        MiniPlayer.dismiss(main);
        FolderUi.dismiss(main);
        AppSearch.dismiss(main);
        DeviceInfoDialog.dismissForActivity(main);
        WallpaperCollectionHelper.cancelActions(main);
        WallpaperCollectionHelper.cancelPopup(main);
        AvatarStore.cancelReturn(main);
        CollectionDialog.dismissForActivity(main);
        try {
            // 1. Dismiss PopupWindow
            if (main.openPopup != null) {
                PopupOptimizer.dismissPopupImmediate(main.openPopup);
                main.openPopup = null;
            }
            // 2. Dismiss AppLibrary
            if (main.appLibrary != null) {
                try {
                    PopupOptimizer.dismissPopupImmediate(main.appLibrary.popup);
                    main.appLibrary.close();
                } catch (Throwable ignored) {}
                main.appLibrary = null;
            }
            // 3. Dismiss ControlCenter
            ControlCenter center = activeCenter.get();
            if (center != null && center.open) {
                try {
                    center.callClose();
                } catch (Throwable ignored) {}
            }
            // 4. Dismiss SettingsPopup
            SettingsPopup sp = activeSettingsPopup.get();
            if (sp != null && sp.dialog != null) {
                try {
                    if (sp.dialog.isShowing()) sp.dialog.dismiss();
                } catch (Throwable ignored) {}
            }
            // 5. Cancel reorder
            if (main.reordering) {
                main.endReorder();
            }
            // 6. Return focus to top dock
            main.libraryFocusPending = false;
            MainActivity.lastFocused = 0;
            MainActivity.lastFocusedKey = null;
            if (main.grid != null) {
                main.grid.scrollToPosition(0);
                final long interaction = TileLoader.interactionSerial(main);
                Runnable refocus = new Runnable() {
                    @Override
                    public void run() {
                        if (main.isFinishing() || main.isDestroyed() || interaction != TileLoader.interactionSerial(main)) return;
                        main.libraryFocusPending = false;
                        MainActivity.lastFocused = 0;
                        MainActivity.lastFocusedKey = null;
                        if (main.grid != null) main.grid.scrollToPosition(0);
                        main.focusTile(0);
                    }
                };
                main.grid.post(refocus);
                main.grid.postDelayed(refocus, 80L);
                main.grid.postDelayed(refocus, 160L);
            }
            main.focusTile(0);

            // 7. Guarantee clock dock pill is visible
            ensureClockPillVisible();
        } catch (Throwable ignored) {}
    }

    public static void ensureClockPillVisible() {
        try {
            ControlCenter center = activeCenter.get();
            if (center != null && center.activity != null) {
                ensureClockPillVisible(center.activity);
            } else if (center != null && center.clockPill != null) {
                center.clockPill.setAlpha(1.0f);
                center.clockPill.setVisibility(View.VISIBLE);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Smooth cross-fade transition from old wallpaper to new wallpaper (200ms)
     * matching the transition animation when entering settings in Control Panel.
     */
    public static void onWallpaperTransition(final WallpaperPlayer player, final Bitmap oldBitmap, final Bitmap newBitmap) {
        WallpaperIcons.onWallpaper(player,newBitmap);
        WallpaperTheme.onWallpaper(player,newBitmap);
        if (player != null && player.transitioning) return;
        if (player == null || player.root == null || player.image == null) return;
        if (oldBitmap == null || oldBitmap == newBitmap || oldBitmap.isRecycled()) return;
        if (newBitmap == null || newBitmap.isRecycled()) return;

        try {
            final FrameLayout root = player.root;
            CrystalSoundEngine.playWallpaperApply(root.getContext());
            final ImageView mainImage = player.image;

            // Remove any leftover transition overlays first
            View oldOverlay = root.findViewWithTag("wallpaper_transition_overlay");
            if (oldOverlay != null) {
                root.removeView(oldOverlay);
            }

            final ImageView overlay = new ImageView(root.getContext());
            overlay.setTag("wallpaper_transition_overlay");
            overlay.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ));
            overlay.setScaleType(mainImage.getScaleType());
            overlay.setImageBitmap(oldBitmap);
            overlay.setAlpha(1.0f);

            int idx = root.indexOfChild(mainImage);
            if (idx >= 0) {
                root.addView(overlay, idx + 1);
            } else {
                root.addView(overlay);
            }

            mainImage.setAlpha(1.0f);

            overlay.animate()
                .alpha(0.0f)
                .setDuration(200L)
                .withLayer()
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            root.removeView(overlay);
                            overlay.setImageDrawable(null);
                            LiquidGlassRoot.backdropChanged(root);
                        } catch (Throwable ignored) {}
                    }
                })
                .start();
        } catch (Throwable ignored) {}
    }

    /**
     * Smooth fade-in dialog opening animation for WallpaperPopup
     * matching SettingsPopup (180ms alpha fade-in via OnShowListener).
     */
    public static void showWallpaperPopup(final Dialog dialog, final View root) {
        PopupOptimizer.showWallpaperDialog(dialog, root);
    }

    // =========================================================================
    // Gentle app focus; binding and focus callbacks share the same targets.
    // =========================================================================
    public static final float TILE_FOCUS_SCALE = 1.04f;
    private static final float TILE_FOCUS_LIFT_DP = -2.0f;
    private static final float TILE_FOCUS_Z_DP = 6.0f;
    private static final float ICON_FOCUS_SCALE = 1.03f;
    private static final float ICON_FOCUS_LIFT_DP = 0.0f;
    private static final float BANNER_FOCUS_SCALE = 1.03f;
    private static final android.view.animation.Interpolator APP_FOCUS_EASE =
        new android.view.animation.DecelerateInterpolator(1.5f);

    public static void onAppTileFocusChange(
            AppAdapter.Holder holder,
            View card,
            boolean hasFocus,
            boolean animations,
            boolean reducedMotion,
            boolean focusOvershoot,
            float restNameAlpha,
            float density,
            Object listener) {
        if (holder == null || card == null) return;
        AppFocusGlass.focus(card,hasFocus);

        card.animate().cancel();
        if (holder.banner != null) holder.banner.animate().cancel();
        if (holder.icon != null) holder.icon.animate().cancel();
        if (holder.label != null) holder.label.animate().cancel();
        if (holder.name != null) holder.name.animate().cancel();

        if (!animations || reducedMotion) {
            card.setScaleX(1.0f);
            card.setScaleY(1.0f);
            card.setTranslationY(0.0f);
            card.setTranslationZ(0.0f);
            if (holder.banner != null) {
                holder.banner.setScaleX(1.0f);
                holder.banner.setScaleY(1.0f);
            }
            if (holder.icon != null) {
                holder.icon.setScaleX(1.0f);
                holder.icon.setScaleY(1.0f);
                holder.icon.setTranslationY(0.0f);
            }
            if (holder.label != null) {
                holder.label.setTranslationY(0.0f);
                holder.label.setAlpha(1.0f);
            }
            if (holder.name != null) {
                holder.name.setAlpha(hasFocus ? 1.0f : restNameAlpha);
                holder.name.setTranslationY(0.0f);
            }
        } else {
            // Smooth focus emphasis without a bounce, retargeted from current properties.
            float targetCardScale = hasFocus ? TILE_FOCUS_SCALE : 1.0f;
            float targetCardY = hasFocus ? (TILE_FOCUS_LIFT_DP * density) : 0.0f;
            float targetCardZ = hasFocus ? (TILE_FOCUS_Z_DP * density) : 0.0f;
            long cardDuration = hasFocus ? 270L : 180L;
            android.view.animation.Interpolator cardInterp = APP_FOCUS_EASE;

            card.animate()
                .scaleX(targetCardScale)
                .scaleY(targetCardScale)
                .translationY(targetCardY)
                .translationZ(targetCardZ)
                .setDuration(cardDuration)
                .setInterpolator(cardInterp)
                .withLayer()
                .start();

            // Inner icon moves less than the card, without a second strong pop.
            if (holder.icon != null) {
                if (holder.icon.getVisibility() == View.VISIBLE) {
                    float iconScale = hasFocus ? ICON_FOCUS_SCALE : 1.0f;
                    float iconY = hasFocus ? (ICON_FOCUS_LIFT_DP * density) : 0.0f;
                    long iconDuration = hasFocus ? 280L : 180L;
                    android.view.animation.Interpolator iconInterp = APP_FOCUS_EASE;
                    holder.icon.animate()
                        .scaleX(iconScale)
                        .scaleY(iconScale)
                        .translationY(iconY)
                        .setDuration(iconDuration)
                        .setInterpolator(iconInterp)
                        .withLayer()
                        .start();
                } else {
                    holder.icon.setScaleX(1.0f);
                    holder.icon.setScaleY(1.0f);
                    holder.icon.setTranslationY(0.0f);
                }
            }

            // Keep banner emphasis quieter than the icon.
            if (holder.banner != null) {
                if (holder.banner.getVisibility() == View.VISIBLE) {
                    float bannerScale = hasFocus ? BANNER_FOCUS_SCALE : 1.0f;
                    long bannerDuration = hasFocus ? 270L : 180L;
                    holder.banner.animate()
                        .scaleX(bannerScale)
                        .scaleY(bannerScale)
                        .setDuration(bannerDuration)
                        .setInterpolator(APP_FOCUS_EASE)
                        .withLayer()
                        .start();
                } else {
                    holder.banner.setScaleX(1.0f);
                    holder.banner.setScaleY(1.0f);
                }
            }

            // Layer 2c (Secondary): Inner Tile Label Micro-Lift
            if (holder.label != null && holder.label.getVisibility() == View.VISIBLE) {
                holder.label.animate()
                    .translationY(hasFocus ? (-0.5f * density) : 0.0f)
                    .alpha(hasFocus ? 1.0f : 0.88f)
                    .setDuration(hasFocus ? 220L : 160L)
                    .setInterpolator(APP_FOCUS_EASE)
                    .start();
            }

            // Keep the title close to its resting position.
            if (holder.name != null && holder.name.getVisibility() == View.VISIBLE) {
                holder.name.animate()
                    .alpha(hasFocus ? 1.0f : restNameAlpha)
                    .translationY(hasFocus ? (1.5f * density) : (-1.0f * density))
                    .setDuration(hasFocus ? 220L : 160L)
                    .setInterpolator(APP_FOCUS_EASE)
                    .start();
            }
        }

        int pos = holder.getBindingAdapterPosition();
        if (hasFocus && listener instanceof MainActivity && pos != -1) {
            MainActivity main = (MainActivity) listener;
            main.onTileFocused(card, pos);
            applyGlassAccentForTile(main, pos);
        }
    }

    public static void syncAppTileBindState(AppAdapter adapter, AppAdapter.Holder holder) {
        if (adapter == null || holder == null || holder.card == null) return;
        WallpaperIcons.bind(adapter,holder);
        FontChoice.apply(holder.label);
        FontChoice.apply(holder.name);
        AppFocusGlass.bind(holder.card);
        try {
            View card = holder.card;
            boolean focused = card.isFocused();
            boolean anim = adapter.animations && !adapter.reducedMotion;
            float density = card.getResources().getDisplayMetrics().density;

            card.animate().cancel();
            if (holder.banner != null) holder.banner.animate().cancel();
            if (holder.icon != null) holder.icon.animate().cancel();
            if (holder.label != null) holder.label.animate().cancel();
            if (holder.name != null) holder.name.animate().cancel();

            float cardScale = (focused && anim) ? TILE_FOCUS_SCALE : 1.0f;
            card.setScaleX(cardScale);
            card.setScaleY(cardScale);
            card.setTranslationY((focused && anim) ? (TILE_FOCUS_LIFT_DP * density) : 0.0f);
            card.setTranslationZ((focused && anim) ? (TILE_FOCUS_Z_DP * density) : 0.0f);

            if (holder.icon != null) {
                boolean iconVis = holder.icon.getVisibility() == View.VISIBLE;
                float iconScale = (focused && anim && iconVis) ? ICON_FOCUS_SCALE : 1.0f;
                holder.icon.setScaleX(iconScale);
                holder.icon.setScaleY(iconScale);
                holder.icon.setTranslationY((focused && anim && iconVis) ? (ICON_FOCUS_LIFT_DP * density) : 0.0f);
            }
            if (holder.banner != null) {
                boolean bannerVis = holder.banner.getVisibility() == View.VISIBLE;
                float bannerScale = (focused && anim && bannerVis) ? BANNER_FOCUS_SCALE : 1.0f;
                holder.banner.setScaleX(bannerScale);
                holder.banner.setScaleY(bannerScale);
            }
            if (holder.label != null) {
                boolean labelVis = holder.label.getVisibility() == View.VISIBLE;
                holder.label.setTranslationY((focused && anim && labelVis) ? (-0.5f * density) : 0.0f);
                holder.label.setAlpha(focused ? 1.0f : 0.88f);
            }
            if (holder.name != null) {
                holder.name.setAlpha(focused ? 1.0f : adapter.restNameAlpha);
                if (holder.name.getVisibility() == View.VISIBLE && anim) {
                    holder.name.setTranslationY(focused ? (1.5f * density) : (-1.0f * density));
                } else {
                    holder.name.setTranslationY(0.0f);
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void applyGlassAccentForTile(MainActivity main, int pos) {
        if (main == null || main.glass == null || main.adapter == null || pos < 0) return;
        try {
            int color = main.adapter.ambientColorAt(pos);
            if (color != main.glassAccent) {
                main.glassAccent = color;
                main.glass.setAccent(color);
            }
        } catch (Throwable ignored) {}
    }

}
