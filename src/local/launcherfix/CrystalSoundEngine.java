package local.launcherfix;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import com.gathin.tvhome.Backdrop;
import com.gathin.tvhome.ClearGlass;
import com.gathin.tvhome.MainActivity;
import com.gathin.tvhome.PaperTransition;
import com.gathin.tvhome.SettingsPopup;
import com.gathin.tvhome.WallpaperPopup;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Zero-latency Multi-Pack UI Sound Engine (6 procedural packs + Steam Big Picture WAV pack + Off).
 * Synthesizes 44.1 kHz 16-bit PCM acoustic waveforms once into cache and plays them via
 * native SoundPool with directional stereo panning and harmonic micro-pitch variation.
 */
public final class CrystalSoundEngine {
    public static final String PREFS = "crystal_ui_sound";
    public static final String KEY_MODE = "mode";
    public static final String TAG_SETTING = "sp_crystal_ui_sound";
    public static final String TAG_TRANSITION_SETTING = "sp_wallpaper_transition";
    public static final String TAG_AMBIENT_SETTING = "sp_ambient_mode";
    public static final String TAG_AMBIENT_HUD = "crystal_ambient_hud";
    public static final String TAG_POPUP_SHELL = "crystal_popup_double_bezel";
    public static final String KEY_AMBIENT_TIMEOUT = "ambient_timeout_sec";
    public static final int DEFAULT_AMBIENT_TIMEOUT_SEC = 180;

    public static final int MODE_OFF = 0;
    public static final int MODE_CRYSTAL = 1; // Pack 1: Pha lê PS5 (PS5 Cosmic Glass)
    public static final int MODE_VELVET = 2;  // Pack 2: Nhung Apple TV (Apple tvOS Velvet)
    public static final int MODE_SWITCH = 3;  // Pack 3: Nảy Switch (Switch Joy-Con Pop)
    public static final int MODE_DROPLET = 4; // Pack 4: Giọt nước Zen (Zen Water Droplet)
    public static final int MODE_CYBER = 5;   // Pack 5: Quang học Sci-Fi (Cyber Hologram)
    public static final int MODE_KALIMBA = 6; // Pack 6: Phím cơ & Kalimba (Hi-Fi Mech & Kalimba)
    public static final int MODE_STEAM = 7;   // Pack 7: Steam Big Picture (WAV assets, not synthesized)
    public static final int MODE_MAX = 7;

    private static final int SAMPLE_RATE = 44100;
    private static final int SND_TICK = 0;
    private static final int SND_SELECT = 1;
    private static final int SND_OPEN = 2;
    private static final int SND_CLOSE = 3;
    private static final int SND_WALLPAPER = 4;
    private static final int SND_HOLD = 5;
    private static final int SND_COUNT = 6;

    private static final String[] PACK_PREFIX = {
        "", "p1_crystal", "p2_velvet", "p3_switch", "p4_droplet", "p5_cyber", "p6_kalimba", "p7_steam"
    };
    private static final String[] SND_NAMES = {
        "tick.wav", "select.wav", "open.wav", "close.wav", "wall.wav", "hold.wav"
    };

    private static final Object LOCK = new Object();
    private static volatile int sMode = -1;
    private static volatile boolean sInitialized = false;
    private static volatile boolean sReady = false;
    private static SoundPool sPool;
    private static File sSfxDir;
    private static Context sApp;
    private static final int[][] sSoundIds = new int[MODE_MAX + 1][SND_COUNT];
    private static final boolean[] sPackLoaded = new boolean[MODE_MAX + 1];

    private static long sLastKeyEventDownTime = -1L;
    private static int sLastKeyEventCode = -1;
    private static int sLastKeyEventRepeat = -1;
    private static long sLastTickMs = 0L;
    private static long sLastOpenMs = 0L;
    private static long sLastCloseMs = 0L;
    private static long sLastWallMs = 0L;
    private static int sRapidStepCounter = 0;

    private CrystalSoundEngine() {}

    public static int getMode(Context context) {
        int m = sMode;
        if (m >= 0) return m;
        if (context == null) return MODE_CRYSTAL;
        SharedPreferences sp = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        m = sp.getInt(KEY_MODE, MODE_CRYSTAL);
        if (m < MODE_OFF || m > MODE_MAX) m = MODE_CRYSTAL;
        sMode = m;
        return m;
    }

    public static void setMode(Context context, int mode) {
        if (mode < MODE_OFF || mode > MODE_MAX) mode = MODE_CRYSTAL;
        sMode = mode;
        if (context != null) {
            context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_MODE, mode)
                .apply();
            if (mode != MODE_OFF) {
                ensureInit(context);
                ensurePackLoadedAsync(mode);
            }
            unloadInactivePacks(mode);
        }
    }

    public static int cycleMode(Context context) {
        int cur = getMode(context);
        int next;
        if (cur >= MODE_CRYSTAL && cur < MODE_MAX) {
            next = cur + 1;
        } else if (cur == MODE_MAX) {
            next = MODE_OFF;
        } else {
            next = MODE_CRYSTAL;
        }
        setMode(context, next);
        if (next != MODE_OFF) {
            playSelect(context);
        }
        return next;
    }

    public static String modeLabel(int mode, boolean vi) {
        switch (mode) {
            case MODE_OFF:
                return vi ? "Tắt" : "Off";
            case MODE_VELVET:
                return vi ? "Nhung Apple TV" : "tvOS Velvet";
            case MODE_SWITCH:
                return vi ? "Nảy Switch" : "Switch Pop";
            case MODE_DROPLET:
                return vi ? "Giọt nước Zen" : "Zen Droplet";
            case MODE_CYBER:
                return vi ? "Quang học Sci-Fi" : "Cyber Sci-Fi";
            case MODE_KALIMBA:
                return vi ? "Phím cơ & Kalimba" : "Mech & Kalimba";
            case MODE_STEAM:
                return "Steam Big Picture";
            case MODE_CRYSTAL:
            default:
                return vi ? "Pha lê PS5" : "PS5 Crystal";
        }
    }

    public static void ensureInit(final Context context) {
        if (sInitialized || context == null) return;
        final Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (sInitialized) return;
            sInitialized = true;
            getMode(app);
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    initSoundPoolBackground(app);
                }
            }, "crystal-sfx-init");
            worker.setDaemon(true);
            worker.setPriority(Thread.NORM_PRIORITY - 1);
            worker.start();
        }
    }

    private static void initSoundPoolBackground(Context app) {
        try {
            File dir = new File(app.getCacheDir(), "crystal_sfx_v2");
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            sSfxDir = dir;
            sApp = app;

            AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
            SoundPool pool = new SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(attrs)
                .build();

            sPool = pool;
            int active = getMode(app);
            if (active >= MODE_CRYSTAL && active <= MODE_MAX) {
                ensurePackLoadedAsync(active);
            }
            sReady = true;
        } catch (Throwable ignored) {}
    }

    private static void ensurePackLoadedSync(int pack) {
        if (pack < MODE_CRYSTAL || pack > MODE_MAX) return;
        synchronized (LOCK) {
            if (sPackLoaded[pack] || sPool == null || sSfxDir == null) return;
            try {
                File[] files = new File[SND_COUNT];
                for (int s = 0; s < SND_COUNT; s++) {
                    files[s] = new File(sSfxDir, PACK_PREFIX[pack] + "_" + SND_NAMES[s]);
                }
                if (!allValid(files)) {
                    synthesizePack(pack, files);
                }
                for (int s = 0; s < SND_COUNT; s++) {
                    sSoundIds[pack][s] = sPool.load(files[s].getAbsolutePath(), 1);
                }
                sPackLoaded[pack] = true;
            } catch (Throwable ignored) {}
        }
    }

    private static void ensurePackLoadedAsync(final int pack) {
        if (pack < MODE_CRYSTAL || pack > MODE_MAX || sPackLoaded[pack]) return;
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                ensurePackLoadedSync(pack);
            }
        }, "crystal-sfx-pack");
        worker.setDaemon(true);
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        worker.start();
    }

    private static void preloadAllPacksAsync() {
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int p = MODE_CRYSTAL; p <= MODE_MAX; p++) {
                    ensurePackLoadedSync(p);
                }
            }
        }, "crystal-sfx-preview");
        worker.setDaemon(true);
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        worker.start();
    }

    public static void unloadInactivePacks(int keepPack) {
        synchronized (LOCK) {
            SoundPool pool = sPool;
            if (pool == null) return;
            for (int p = MODE_CRYSTAL; p <= MODE_MAX; p++) {
                if (p == keepPack || !sPackLoaded[p]) continue;
                for (int s = 0; s < SND_COUNT; s++) {
                    int id = sSoundIds[p][s];
                    if (id != 0) {
                        try {
                            pool.unload(id);
                        } catch (Throwable ignored) {}
                        sSoundIds[p][s] = 0;
                    }
                }
                sPackLoaded[p] = false;
            }
        }
    }

    public static void trimMemory(int level) {
        if (level >= 20 || level == 10 || level == 15) {
            unloadInactivePacks(sMode);
        }
    }

    private static void synthesizePack(int pack, File[] files) throws IOException {
        switch (pack) {
            case MODE_CRYSTAL:
                writeWav(files[SND_TICK], synthCrystalTick());
                writeWav(files[SND_SELECT], synthCrystalSelect());
                writeWav(files[SND_OPEN], synthCrystalOpen());
                writeWav(files[SND_CLOSE], synthCrystalClose());
                writeWav(files[SND_WALLPAPER], synthCrystalWallpaper());
                writeWav(files[SND_HOLD], synthCrystalHold());
                break;
            case MODE_VELVET:
                writeWav(files[SND_TICK], synthVelvetTick());
                writeWav(files[SND_SELECT], synthVelvetSelect());
                writeWav(files[SND_OPEN], synthVelvetOpen());
                writeWav(files[SND_CLOSE], synthVelvetClose());
                writeWav(files[SND_WALLPAPER], synthVelvetWallpaper());
                writeWav(files[SND_HOLD], synthVelvetHold());
                break;
            case MODE_SWITCH:
                writeWav(files[SND_TICK], synthSwitchTick());
                writeWav(files[SND_SELECT], synthSwitchSelect());
                writeWav(files[SND_OPEN], synthSwitchOpen());
                writeWav(files[SND_CLOSE], synthSwitchClose());
                writeWav(files[SND_WALLPAPER], synthSwitchWallpaper());
                writeWav(files[SND_HOLD], synthSwitchHold());
                break;
            case MODE_DROPLET:
                writeWav(files[SND_TICK], synthDropletTick());
                writeWav(files[SND_SELECT], synthDropletSelect());
                writeWav(files[SND_OPEN], synthDropletOpen());
                writeWav(files[SND_CLOSE], synthDropletClose());
                writeWav(files[SND_WALLPAPER], synthDropletWallpaper());
                writeWav(files[SND_HOLD], synthDropletHold());
                break;
            case MODE_CYBER:
                writeWav(files[SND_TICK], synthCyberTick());
                writeWav(files[SND_SELECT], synthCyberSelect());
                writeWav(files[SND_OPEN], synthCyberOpen());
                writeWav(files[SND_CLOSE], synthCyberClose());
                writeWav(files[SND_WALLPAPER], synthCyberWallpaper());
                writeWav(files[SND_HOLD], synthCyberHold());
                break;
            case MODE_KALIMBA:
                writeWav(files[SND_TICK], synthKalimbaTick());
                writeWav(files[SND_SELECT], synthKalimbaSelect());
                writeWav(files[SND_OPEN], synthKalimbaOpen());
                writeWav(files[SND_CLOSE], synthKalimbaClose());
                writeWav(files[SND_WALLPAPER], synthKalimbaWallpaper());
                writeWav(files[SND_HOLD], synthKalimbaHold());
                break;
            case MODE_STEAM:
                copySteamAssets(files);
                break;
            default:
                break;
        }
    }

    /** Copies the bundled Steam pack WAVs (assets/sfx_steam/p7_steam_*.wav) into the cache dir. */
    private static void copySteamAssets(File[] files) throws IOException {
        Context app = sApp;
        if (app == null) throw new IOException("no context");
        byte[] buf = new byte[16384];
        for (File out : files) {
            File tmp = new File(out.getPath() + ".tmp");
            java.io.InputStream in = app.getAssets().open("sfx_steam/" + out.getName());
            try {
                FileOutputStream os = new FileOutputStream(tmp);
                try {
                    int r;
                    while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
                } finally {
                    os.close();
                }
            } finally {
                in.close();
            }
            if (!tmp.renameTo(out)) throw new IOException("rename " + out);
        }
    }

    private static boolean allValid(File[] files) {
        for (File f : files) {
            if (!f.exists() || f.length() < 128L) return false;
        }
        return true;
    }

    // =========================================================================
    // Event Hooks (MainActivity, Dialogs, Popups, Wallpaper Transitions)
    // =========================================================================

    public static void onKeyEvent(Context context, KeyEvent event) {
        if (event == null || context == null || sAmbientActive) return;
        int mode = getMode(context);
        if (mode == MODE_OFF) return;
        ensureInit(context);

        if (event.getAction() != KeyEvent.ACTION_DOWN) return;
        int keyCode = event.getKeyCode();
        int repeat = event.getRepeatCount();
        long downTime = event.getDownTime();

        // Deduplicate identical KeyEvent dispatches across nested callbacks
        if (downTime == sLastKeyEventDownTime && keyCode == sLastKeyEventCode && repeat == sLastKeyEventRepeat) {
            return;
        }
        sLastKeyEventDownTime = downTime;
        sLastKeyEventCode = keyCode;
        sLastKeyEventRepeat = repeat;

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                playDirectionalTick(mode, keyCode, repeat);
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (repeat == 0) {
                    playInternal(mode, SND_SELECT, 0.25f, 0.25f, 1.0f);
                } else if (repeat == 1 || event.isLongPress()) {
                    playInternal(mode, SND_HOLD, 0.26f, 0.26f, 1.0f);
                }
                break;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (repeat == 0) {
                    playClose(context);
                }
                break;
            default:
                break;
        }
    }

    private static void playDirectionalTick(int mode, int keyCode, int repeat) {
        long now = SystemClock.uptimeMillis();
        if (repeat > 0) {
            if (now - sLastTickMs < 56L) return;
            sRapidStepCounter = (sRapidStepCounter + 1) & 7;
        } else {
            if (now - sLastTickMs < 18L) return;
            sRapidStepCounter = 0;
        }
        sLastTickMs = now;

        float left = 0.22f;
        float right = 0.22f;
        float rate = 1.0f;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            left = 0.25f;
            right = 0.16f;
            rate = 0.985f;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            left = 0.16f;
            right = 0.25f;
            rate = 1.015f;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            rate = 1.045f;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            rate = 0.955f;
        }

        if (repeat > 0) {
            left *= 0.68f;
            right *= 0.68f;
            rate += (sRapidStepCounter - 3.5f) * 0.006f;
        }
        playInternal(mode, SND_TICK, left, right, rate);
    }

    public static void playSelect(Context context) {
        int mode = getMode(context);
        if (mode == MODE_OFF) return;
        ensureInit(context);
        playInternal(mode, SND_SELECT, 0.25f, 0.25f, 1.0f);
    }

    public static void playHold(Context context) {
        int mode = getMode(context);
        if (mode == MODE_OFF) return;
        ensureInit(context);
        playInternal(mode, SND_HOLD, 0.26f, 0.26f, 1.0f);
    }

    public static void playOpen(Context context) {
        int mode = getMode(context);
        if (mode == MODE_OFF) return;
        ensureInit(context);
        long now = SystemClock.uptimeMillis();
        if (now - sLastOpenMs < 140L) return;
        sLastOpenMs = now;
        playInternal(mode, SND_OPEN, 0.24f, 0.24f, 1.0f);
    }

    public static void playClose(Context context) {
        int mode = getMode(context);
        if (mode == MODE_OFF) return;
        ensureInit(context);
        long now = SystemClock.uptimeMillis();
        if (now - sLastCloseMs < 140L) return;
        sLastCloseMs = now;
        playInternal(mode, SND_CLOSE, 0.22f, 0.22f, 1.0f);
    }

    public static void playWallpaperApply(Context context) {
        if (isAmbientActive()) return;
        int mode = getMode(context);
        if (mode == MODE_OFF) return;
        ensureInit(context);
        long now = SystemClock.uptimeMillis();
        if (now - sLastWallMs < 350L) return;
        sLastWallMs = now;
        playInternal(mode, SND_WALLPAPER, 0.28f, 0.28f, 1.0f);
    }

    private static void playInternal(int mode, int sndIndex, float leftVol, float rightVol, float rate) {
        if (!sReady) return;
        SoundPool pool = sPool;
        if (pool == null || mode <= MODE_OFF || mode > MODE_MAX) return;
        if (!sPackLoaded[mode]) {
            ensurePackLoadedAsync(mode);
            return;
        }
        int id = sSoundIds[mode][sndIndex];
        if (id != 0) {
            try {
                pool.play(id, leftVol, rightVol, 1, 0, rate);
            } catch (Throwable ignored) {}
        }
    }

    public static void attachDialog(final Context context, final Dialog dialog) {
        attachDialog(context, dialog, true);
    }

    public static void attachDialog(final Context context, final Dialog dialog, boolean applyFonts) {
        if (context == null || dialog == null) return;
        ensureInit(context);
        playOpen(context);
        try {
            Window window = dialog.getWindow();
            if (window == null) return;
            if (applyFonts) {
                FontChoice.apply(window.getDecorView());
            }
            Window.Callback existing = window.getCallback();
            if (existing instanceof SoundWindowCallback) return;
            if (existing != null) {
                window.setCallback(new SoundWindowCallback(context.getApplicationContext(), dialog, existing));
            }
        } catch (Throwable ignored) {}
    }

    public static void playPreview(Context context, int mode) {
        if (context == null || mode <= MODE_OFF || mode > MODE_MAX) return;
        ensureInit(context);
        playInternal(mode, SND_SELECT, 0.26f, 0.26f, 1.0f);
    }

    /**
     * Attaches the Multi-Pack UI Sound row and Wallpaper Transition Quick-Select row
     * inside SettingsPopup (Launcher Settings).
     */
    public static void attachSetting(final SettingsPopup popup, final LinearLayout rows) {
        if (popup == null || rows == null || rows.findViewWithTag(TAG_SETTING) != null) return;
        final Context context = popup.activity;
        ensureInit(context);

        final TextView row = new TextView(context);
        row.setTag(TAG_SETTING);
        row.setId(View.generateViewId());
        row.setFocusable(true);
        row.setClickable(true);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = Math.round(14 * popup.dp);
        row.setPadding(padH, 0, padH, 0);
        row.setTextSize(13);
        row.setTextColor(0xffffffff);
        row.setSingleLine(true);
        row.setEllipsize(TextUtils.TruncateAt.END);
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(
            null, null, new TrailingChevronBadgeDrawable(popup.dp), null
        );
        row.setCompoundDrawablePadding(Math.round(8 * popup.dp));
        ClearGlass.shadow(row, popup.dp);
        row.setBackground(new ClearGlass.Plate(14 * popup.dp, popup.dp, -1));

        final Runnable updateLabel = new Runnable() {
            @Override
            public void run() {
                int m = getMode(context);
                String state = modeLabel(m, popup.vi);
                String text = popup.vi
                    ? ("Âm thanh giao diện · " + state)
                    : ("UI sound · " + state);
                row.setText(text);
                row.setContentDescription(text);
            }
        };
        updateLabel.run();

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSoundPackDialog(popup, row, updateLabel);
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            Math.round(44 * popup.dp)
        );
        lp.topMargin = Math.round(6 * popup.dp);
        rows.addView(row, lp);

        attachTransitionSetting(popup);
        attachAmbientSetting(popup);
    }

    /**
     * Keeps spacing around Backdrop-hosted popup panels without a visible outer bezel.
     */
    public static View wrapPopupPanel(Activity activity, View panel) {
        if (activity == null || panel == null || panel.getParent() != null || TAG_POPUP_SHELL.equals(panel.getTag())) {
            return panel;
        }
        float dp = activity.getResources().getDisplayMetrics().density;
        FrameLayout outerShell = new FrameLayout(activity);
        outerShell.setTag(TAG_POPUP_SHELL);
        int shellPad = Math.round(5 * dp);
        outerShell.setPadding(shellPad, shellPad, shellPad, shellPad);
        float outerRadiusPx = Math.round(29 * dp);
        GradientDrawable outerBg = new GradientDrawable();
        outerBg.setColor(Color.TRANSPARENT);
        outerBg.setCornerRadius(outerRadiusPx);
        outerShell.setBackground(outerBg);
        outerShell.addView(panel, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ));
        return outerShell;
    }

    private static void attachTransitionSetting(final SettingsPopup popup) {
        if (popup == null || popup.advancedRow == null) return;
        ViewParent parent = popup.advancedRow.getParent();
        if (!(parent instanceof LinearLayout)) return;
        final LinearLayout col2 = (LinearLayout) parent;
        if (col2.findViewWithTag(TAG_TRANSITION_SETTING) != null) return;

        final Context context = popup.activity;
        final TextView transRow = new TextView(context);
        transRow.setTag(TAG_TRANSITION_SETTING);
        transRow.setId(View.generateViewId());
        transRow.setFocusable(true);
        transRow.setClickable(true);
        transRow.setGravity(Gravity.CENTER_VERTICAL);
        int padH = Math.round(14 * popup.dp);
        transRow.setPadding(padH, 0, padH, 0);
        transRow.setTextSize(13);
        transRow.setTextColor(0xffffffff);
        transRow.setSingleLine(true);
        transRow.setEllipsize(TextUtils.TruncateAt.END);
        transRow.setCompoundDrawablesRelativeWithIntrinsicBounds(
            null, null, new TrailingChevronBadgeDrawable(popup.dp), null
        );
        transRow.setCompoundDrawablePadding(Math.round(8 * popup.dp));
        ClearGlass.shadow(transRow, popup.dp);
        transRow.setBackground(new ClearGlass.Plate(14 * popup.dp, popup.dp, -1));

        final Runnable updateTransLabel = new Runnable() {
            @Override
            public void run() {
                String key = PaperTransition.chosen(context);
                String state = WallpaperTransitionCrop.label(key, popup.vi);
                String text = popup.vi
                    ? ("Hiệu ứng chuyển ảnh · " + state)
                    : ("Transition · " + state);
                transRow.setText(text);
                transRow.setContentDescription(text);
            }
        };
        updateTransLabel.run();

        transRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTransitionEffectDialog(popup, transRow, updateTransLabel);
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            Math.round(44 * popup.dp)
        );
        lp.topMargin = Math.round(6 * popup.dp);
        col2.addView(transRow, lp);
    }

    private static void attachAmbientSetting(final SettingsPopup popup) {
        if (popup == null || popup.advancedRow == null) return;
        ViewParent parent = popup.advancedRow.getParent();
        if (!(parent instanceof LinearLayout)) return;
        final LinearLayout col1 = (LinearLayout) parent;
        if (col1.findViewWithTag(TAG_AMBIENT_SETTING) != null) return;

        final Context context = popup.activity;
        final TextView ambientRow = new TextView(context);
        ambientRow.setTag(TAG_AMBIENT_SETTING);
        ambientRow.setId(View.generateViewId());
        ambientRow.setFocusable(true);
        ambientRow.setClickable(true);
        ambientRow.setGravity(Gravity.CENTER_VERTICAL);
        int padH = Math.round(14 * popup.dp);
        ambientRow.setPadding(padH, 0, padH, 0);
        ambientRow.setTextSize(13);
        ambientRow.setTextColor(0xffffffff);
        ambientRow.setSingleLine(true);
        ambientRow.setEllipsize(TextUtils.TruncateAt.END);
        ambientRow.setCompoundDrawablesRelativeWithIntrinsicBounds(
            null, null, new TrailingChevronBadgeDrawable(popup.dp), null
        );
        ambientRow.setCompoundDrawablePadding(Math.round(8 * popup.dp));
        ClearGlass.shadow(ambientRow, popup.dp);
        ambientRow.setBackground(new ClearGlass.Plate(14 * popup.dp, popup.dp, -1));

        final Runnable updateAmbientLabel = new Runnable() {
            @Override
            public void run() {
                int sec = getAmbientTimeoutSec(context);
                String state = ambientTimeoutLabel(sec, popup.vi);
                String text = popup.vi
                    ? ("Chế độ chờ Ambient · " + state)
                    : ("Ambient mode · " + state);
                ambientRow.setText(text);
                ambientRow.setContentDescription(text);
            }
        };
        updateAmbientLabel.run();

        ambientRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAmbientModeDialog(popup, ambientRow, updateAmbientLabel);
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            Math.round(44 * popup.dp)
        );
        lp.topMargin = Math.round(6 * popup.dp);
        col1.addView(ambientRow, lp);
    }

    public static void showAmbientModeDialog(final SettingsPopup popup, final View anchor, final Runnable onUpdated) {
        if (popup == null || popup.activity == null) return;
        final Activity activity = popup.activity;
        final boolean vi = popup.vi;
        final String[] keys = new String[]{
            "preview", "60",
            "180", "300",
            "600", "0"
        };
        String currentKey = String.valueOf(getAmbientTimeoutSec(activity));
        String title = vi ? "Chế độ chờ Ambient & Tự động đổi nền" : "Ambient Screensaver & Auto Wallpaper";

        showQuickSelectDialog(
            activity,
            popup.dp,
            anchor,
            title,
            keys,
            currentKey,
            new Function<String, String>() {
                @Override
                public String apply(String k) {
                    if ("preview".equals(k)) {
                        return vi ? "Xem thử ngay" : "Preview now";
                    }
                    try {
                        int sec = Integer.parseInt(k);
                        return ambientOptionLabel(sec, vi);
                    } catch (NumberFormatException e) {
                        return k;
                    }
                }
            },
            null,
            new Consumer<String>() {
                @Override
                public void accept(String k) {
                    playSelect(activity);
                    if ("preview".equals(k)) {
                        if (popup.dialog != null && popup.dialog.isShowing()) {
                            popup.dialog.dismiss();
                        }
                        sAmbientHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                enterAmbientMode(activity);
                            }
                        }, 180L);
                        return;
                    }
                    try {
                        int sec = Integer.parseInt(k);
                        setAmbientTimeoutSec(activity, sec);
                    } catch (NumberFormatException ignored) {}
                    if (onUpdated != null) {
                        onUpdated.run();
                    }
                    scheduleAmbientCheck(activity);
                }
            },
            true
        );
    }

    public static void showSoundPackDialog(final SettingsPopup popup, final View anchor, final Runnable onUpdated) {
        if (popup == null || popup.activity == null) return;
        final Context context = popup.activity;
        preloadAllPacksAsync();
        final boolean vi = popup.vi;
        final int[] modes = new int[]{
            MODE_CRYSTAL, MODE_VELVET,
            MODE_SWITCH, MODE_DROPLET,
            MODE_CYBER, MODE_KALIMBA,
            MODE_STEAM, MODE_OFF
        };
        final String[] keys = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            keys[i] = String.valueOf(modes[i]);
        }
        String currentKey = String.valueOf(getMode(context));
        String title = vi ? "Chọn gói âm thanh giao diện" : "Select UI Sound Pack";

        showQuickSelectDialog(
            popup.activity,
            popup.dp,
            anchor,
            title,
            keys,
            currentKey,
            new Function<String, String>() {
                @Override
                public String apply(String k) {
                    int m = Integer.parseInt(k);
                    return modeLabel(m, vi);
                }
            },
            new Consumer<String>() {
                @Override
                public void accept(String k) {
                    int m = Integer.parseInt(k);
                    if (m > MODE_OFF) {
                        playPreview(context, m);
                    }
                }
            },
            new Consumer<String>() {
                @Override
                public void accept(String k) {
                    int m = Integer.parseInt(k);
                    setMode(context, m);
                    if (m > MODE_OFF) {
                        playSelect(context);
                    }
                    if (onUpdated != null) {
                        onUpdated.run();
                    }
                }
            },
            false
        );
    }

    public static void showTransitionEffectDialog(final SettingsPopup popup, final View anchor, final Runnable onUpdated) {
        if (popup == null || popup.activity == null) return;
        final Context context = popup.activity;
        final boolean vi = popup.vi;
        final String[] keys = WallpaperTransitionCrop.ALL_KEYS;
        String currentKey = PaperTransition.chosen(context);
        String title = vi ? "Chọn hiệu ứng chuyển hình nền" : "Select Wallpaper Transition";

        showQuickSelectDialog(
            popup.activity,
            popup.dp,
            anchor,
            title,
            keys,
            currentKey,
            new Function<String, String>() {
                @Override
                public String apply(String k) {
                    return WallpaperTransitionCrop.label(k, vi);
                }
            },
            null,
            new Consumer<String>() {
                @Override
                public void accept(String k) {
                    context.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)
                        .edit()
                        .putString("wallpaper_transition", k)
                        .apply();
                    playSelect(context);
                    if (onUpdated != null) {
                        onUpdated.run();
                    }
                }
            },
            true
        );
    }

    static void showQuickSelectDialog(
        final Activity activity,
        final float dp,
        final View anchor,
        final String titleText,
        final String[] keys,
        final String currentKey,
        final Function<String, String> labelFn,
        final Consumer<String> onFocusPreview,
        final Consumer<String> onSelect,
        final boolean attachWindowSound
    ) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setClipChildren(false);

        card.setBackground(ClearGlass.panel(Math.round(24 * dp), dp));

        int padH = Math.round(20 * dp);
        int padV = Math.round(18 * dp);
        card.setPadding(padH, padV, padH, padV);

        TextView titleView = new TextView(activity);
        titleView.setText(titleText);
        titleView.setTextSize(16.0f);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        titleView.setLetterSpacing(-0.015f);
        titleView.setTextColor(0xFFFAFBFF);
        titleView.setSingleLine(true);
        ClearGlass.shadow(titleView, dp);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleLp.bottomMargin = Math.round(12 * dp);
        card.addView(titleView, titleLp);

        final int count = keys.length;
        final int cols = 2;
        final int rowsCount = (count + cols - 1) / cols;
        final TextView[] cells = new TextView[count];
        final boolean[] focusPreviewReady = new boolean[]{false};
        TextView initialFocus = null;

        int cellW = Math.round(218 * dp);
        int cellH = Math.round(38 * dp);
        int colGap = Math.round(8 * dp);
        int rowGap = Math.round(6 * dp);
        int cellPadH = Math.round(12 * dp);

        for (int r = 0; r < rowsCount; r++) {
            LinearLayout rowLayout = new LinearLayout(activity);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setClipChildren(false);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            if (r > 0) {
                rowLp.topMargin = rowGap;
            }

            for (int c = 0; c < cols; c++) {
                final int idx = r * cols + c;
                if (idx >= count) break;
                final String key = keys[idx];
                final boolean isSelected = key.equals(currentKey);

                final TextView cell = new TextView(activity);
                String rawLabel = labelFn.apply(key);
                cell.setText(rawLabel);
                cell.setContentDescription(rawLabel);
                cell.setTextSize(13.0f);
                cell.setTypeface(Typeface.create(isSelected ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
                cell.setFontFeatureSettings("tnum");
                cell.setTextColor(isSelected ? 0xFFFFFFFF : 0xE6F4F7FC);
                cell.setSingleLine(true);
                cell.setEllipsize(TextUtils.TruncateAt.END);
                cell.setIncludeFontPadding(false);
                cell.setGravity(Gravity.CENTER_VERTICAL);
                cell.setPadding(cellPadH, 0, cellPadH, 0);
                cell.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    new StatusIndicatorDrawable(dp, isSelected), null, null, null
                );
                cell.setCompoundDrawablePadding(Math.round(9 * dp));
                ClearGlass.shadow(cell, dp);
                cell.setFocusable(true);
                cell.setClickable(true);
                cell.setSelected(isSelected);
                cell.setBackground(new ClearGlass.Plate(Math.round(12 * dp), dp, -1.0f));

                LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(cellW, cellH);
                if (c > 0) {
                    cellLp.leftMargin = colGap;
                }
                rowLayout.addView(cell, cellLp);
                cells[idx] = cell;

                if (isSelected) {
                    initialFocus = cell;
                }

                cell.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                    @Override
                    public void onFocusChange(View v, boolean hasFocus) {
                        if (hasFocus && focusPreviewReady[0] && onFocusPreview != null) {
                            onFocusPreview.accept(key);
                        }
                    }
                });

                final boolean[] armed = new boolean[]{false};
                cell.setOnKeyListener(new View.OnKeyListener() {
                    @Override
                    public boolean onKey(View v, int keyCode, KeyEvent event) {
                        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                                playClose(activity);
                                PopupOptimizer.dismissAnimated(dialog, true);
                            }
                            return true;
                        }
                        if (isConfirmKey(keyCode)) {
                            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                                armed[0] = true;
                                v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(75L).start();
                            } else if (event.getAction() == KeyEvent.ACTION_UP && armed[0] && !event.isCanceled()) {
                                armed[0] = false;
                                if ("preview".equals(key)) {
                                    PopupOptimizer.dismissImmediate(dialog, false);
                                } else {
                                    PopupOptimizer.dismissAnimated(dialog, false);
                                }
                                onSelect.accept(key);
                            }
                            return true;
                        }
                        if (event.getAction() != KeyEvent.ACTION_DOWN) {
                            return false;
                        }
                        int col = idx % cols;
                        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                            if (col > 0 && cells[idx - 1] != null) {
                                cells[idx - 1].requestFocus();
                            }
                            return true;
                        }
                        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                            if (col + 1 < cols && idx + 1 < count && cells[idx + 1] != null) {
                                cells[idx + 1].requestFocus();
                            }
                            return true;
                        }
                        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                            if (idx - cols >= 0 && cells[idx - cols] != null) {
                                cells[idx - cols].requestFocus();
                            }
                            return true;
                        }
                        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                            if (idx + cols < count && cells[idx + cols] != null) {
                                cells[idx + cols].requestFocus();
                            } else if (col == 1 && idx + 1 < count && cells[idx + 1] != null) {
                                cells[idx + 1].requestFocus();
                            }
                            return true;
                        }
                        return false;
                    }
                });

                cell.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if ("preview".equals(key)) {
                            PopupOptimizer.dismissImmediate(dialog, false);
                        } else {
                            PopupOptimizer.dismissAnimated(dialog, false);
                        }
                        onSelect.accept(key);
                    }
                });
            }

            card.addView(rowLayout, rowLp);
        }

        Window window = dialog.getWindow();
        Window.Callback originalCallback = window == null ? null : window.getCallback();
        Backdrop.wrap(activity, dialog, card, true, 0);
        // Sound-pack navigation auditions the pack instead of playing generic D-pad ticks.
        if (!attachWindowSound && window != null && originalCallback != null) {
            window.setCallback(originalCallback);
        }
        View content = dialog.findViewById(android.R.id.content);
        if (content != null) content.setOnClickListener(v -> {
            playClose(activity);
            PopupOptimizer.dismissAnimated(dialog, true);
        });

        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                if (anchor != null) {
                    anchor.requestFocus();
                }
                unloadInactivePacks(getMode(activity));
            }
        });

        dialog.show();

        final TextView targetFocus = initialFocus != null ? initialFocus : (cells.length > 0 ? cells[0] : null);
        if (targetFocus != null) {
            targetFocus.requestFocus();
        }
        card.post(new Runnable() {
            @Override
            public void run() {
                focusPreviewReady[0] = true;
            }
        });
    }

    /**
     * Replaces WallpaperPopup.listMenu with a shared-glass 2-column grid when keys.length > 6
     * so 12-item transition menus and 13-item picker menus never overflow the 540dp TV screen.
     */
    public static void showWallpaperListMenu(
        final WallpaperPopup popup,
        final View anchor,
        final String[] keys,
        final String current,
        final Function<String, String> labelFn,
        final Consumer<String> onChoose
    ) {
        if (popup == null || popup.effectMenu != null || !(popup.root instanceof FrameLayout)) {
            return;
        }
        final FrameLayout rootLayout = (FrameLayout) popup.root;
        final Activity activity = popup.activity;
        final float dp = popup.dp;
        final int count = keys != null ? keys.length : 0;
        if (count == 0) return;

        final int cols = count > 6 ? 2 : 1;
        final int rowsCount = (count + cols - 1) / cols;

        // Keep the menu's existing spacing with a borderless outer shell.
        final FrameLayout outerShell = new FrameLayout(activity);
        outerShell.setClipChildren(false);
        int shellPad = Math.round(5 * dp);
        outerShell.setPadding(shellPad, shellPad, shellPad, shellPad);
        float outerCornerPx = Math.round(22 * dp);
        GradientDrawable shellBg = new GradientDrawable();
        shellBg.setColor(Color.TRANSPARENT);
        shellBg.setCornerRadius(outerCornerPx);
        outerShell.setBackground(shellBg);

        final LinearLayout menuBox = new LinearLayout(activity);
        menuBox.setOrientation(LinearLayout.VERTICAL);
        menuBox.setClipChildren(false);

        float cornerPx = Math.round(17 * dp);
        menuBox.setBackground(ClearGlass.panel(cornerPx, dp));

        int boxPad = Math.round(8 * dp);
        menuBox.setPadding(boxPad, boxPad, boxPad, boxPad);

        final TextView[] cells = new TextView[count];
        TextView selectedView = null;

        int cellW = Math.round((cols == 2 ? 198 : 202) * dp);
        int cellH = Math.round(34 * dp);
        int colGap = Math.round(6 * dp);
        int rowGap = Math.round(4 * dp);
        int cellPadH = Math.round(12 * dp);

        for (int r = 0; r < rowsCount; r++) {
            LinearLayout rowLayout = new LinearLayout(activity);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setClipChildren(false);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            if (r > 0) {
                rowLp.topMargin = rowGap;
            }

            for (int c = 0; c < cols; c++) {
                final int idx = r * cols + c;
                if (idx >= count) break;
                final String key = keys[idx];
                final boolean isSelected = key.equals(current);

                final TextView cell = new TextView(activity);
                String rawLabel = labelFn.apply(key);
                cell.setText(rawLabel);
                cell.setContentDescription(rawLabel);
                cell.setTextSize(13.0f);
                cell.setTypeface(Typeface.create(isSelected ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
                cell.setFontFeatureSettings("tnum");
                cell.setTextColor(isSelected ? 0xFFFFFFFF : 0xE6F4F7FC);
                cell.setSingleLine(true);
                cell.setEllipsize(TextUtils.TruncateAt.END);
                cell.setIncludeFontPadding(false);
                cell.setGravity(Gravity.CENTER_VERTICAL);
                cell.setPadding(cellPadH, 0, cellPadH, 0);
                cell.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    new StatusIndicatorDrawable(dp, isSelected), null, null, null
                );
                cell.setCompoundDrawablePadding(Math.round(8 * dp));
                ClearGlass.shadow(cell, dp);
                cell.setFocusable(true);
                cell.setClickable(true);
                cell.setSelected(isSelected);
                cell.setBackground(new ClearGlass.Plate(Math.round(11 * dp), dp, -1.0f));

                LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(cellW, cellH);
                if (c > 0) {
                    cellLp.leftMargin = colGap;
                }
                rowLayout.addView(cell, cellLp);
                cells[idx] = cell;

                if (isSelected) {
                    selectedView = cell;
                }

                final boolean[] armed = new boolean[]{false};
                cell.setOnKeyListener(new View.OnKeyListener() {
                    @Override
                    public boolean onKey(View v, int keyCode, KeyEvent event) {
                        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                                closeWallpaperListMenu(popup, anchor);
                            }
                            return true;
                        }
                        if (isConfirmKey(keyCode)) {
                            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                                armed[0] = true;
                                v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(75L).start();
                            } else if (event.getAction() == KeyEvent.ACTION_UP && armed[0] && !event.isCanceled()) {
                                armed[0] = false;
                                closeWallpaperListMenu(popup, anchor);
                                onChoose.accept(key);
                            }
                            return true;
                        }
                        if (event.getAction() != KeyEvent.ACTION_DOWN) {
                            return false;
                        }
                        int col = idx % cols;
                        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                            if (col > 0 && cells[idx - 1] != null) {
                                cells[idx - 1].requestFocus();
                            }
                            return true;
                        }
                        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                            if (col + 1 < cols && idx + 1 < count && cells[idx + 1] != null) {
                                cells[idx + 1].requestFocus();
                            }
                            return true;
                        }
                        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                            if (idx - cols >= 0 && cells[idx - cols] != null) {
                                cells[idx - cols].requestFocus();
                            }
                            return true;
                        }
                        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                            if (idx + cols < count && cells[idx + cols] != null) {
                                cells[idx + cols].requestFocus();
                            } else if (col == 1 && idx + 1 < count && cells[idx + 1] != null) {
                                cells[idx + 1].requestFocus();
                            }
                            return true;
                        }
                        return false;
                    }
                });

                cell.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        closeWallpaperListMenu(popup, anchor);
                        onChoose.accept(key);
                    }
                });
            }

            rowLayout.setAlpha(0f);
            rowLayout.setTranslationY(6 * dp);
            rowLayout.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(r * 18L)
                .setDuration(165L)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();

            menuBox.addView(rowLayout, rowLp);
        }

        outerShell.addView(menuBox, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ));

        FontChoice.apply(outerShell);
        outerShell.measure(0, 0);
        int[] anchorLoc = new int[2];
        int[] rootLoc = new int[2];
        anchor.getLocationInWindow(anchorLoc);
        rootLayout.getLocationInWindow(rootLoc);

        int marginEdge = Math.round(16 * dp);
        int gapAbove = Math.round(12 * dp);
        int desiredLeft = (anchorLoc[0] - rootLoc[0]) + anchor.getWidth() / 2 - outerShell.getMeasuredWidth() / 2;
        int maxLeft = rootLayout.getWidth() > 0
            ? Math.max(marginEdge, rootLayout.getWidth() - outerShell.getMeasuredWidth() - marginEdge)
            : desiredLeft;
        int clampedLeft = Math.max(marginEdge, Math.min(desiredLeft, maxLeft));
        int clampedTop = Math.max(marginEdge, (anchorLoc[1] - rootLoc[1]) - outerShell.getMeasuredHeight() - gapAbove);

        // Reuse the popup's blurred screenshot so sharp thumbnails do not show through the glass.
        View backdrop = rootLayout.getChildAt(0);
        if (backdrop instanceof ImageView && ((ImageView) backdrop).getScaleType() == ImageView.ScaleType.FIT_XY) {
            ImageView image = (ImageView) backdrop;
            if (image.getDrawable() instanceof BitmapDrawable) {
                Bitmap bitmap = ((BitmapDrawable) image.getDrawable()).getBitmap();
                if (bitmap != null && !bitmap.isRecycled() && rootLayout.getWidth() > 0 && rootLayout.getHeight() > 0) {
                    menuBox.setBackground(new WallpaperMenuGlass(bitmap, image.getColorFilter(), cornerPx, dp,
                        rootLayout.getWidth(), rootLayout.getHeight(), clampedLeft + shellPad, clampedTop + shellPad));
                }
            }
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        lp.leftMargin = clampedLeft;
        lp.topMargin = clampedTop;

        rootLayout.addView(outerShell, lp);
        popup.effectMenu = outerShell;
        playOpen(activity);

        outerShell.setAlpha(0f);
        outerShell.setScaleX(0.95f);
        outerShell.setScaleY(0.95f);
        outerShell.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(190L)
            .setInterpolator(new OvershootInterpolator(1.12f))
            .withLayer()
            .start();

        TextView focusTarget = selectedView != null ? selectedView : cells[0];
        if (focusTarget != null) {
            focusTarget.requestFocus();
        }
    }

    private static final class WallpaperMenuGlass extends Drawable {
        final Bitmap bitmap;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final RectF rect = new RectF();
        final Drawable glass;
        final float radius;
        WallpaperMenuGlass(Bitmap bitmap, ColorFilter tint, float radius, float dp, int width, int height, int left, int top) {
            this.bitmap = bitmap; this.radius = radius; glass = ClearGlass.panel(radius, dp);
            BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix matrix = new Matrix();
            matrix.setScale(width / (float) bitmap.getWidth(), height / (float) bitmap.getHeight());
            matrix.postTranslate(-left, -top); shader.setLocalMatrix(matrix);
            paint.setShader(shader); paint.setColorFilter(tint);
        }
        public void draw(Canvas canvas) {
            if (!bitmap.isRecycled()) {
                rect.set(getBounds()); canvas.drawRoundRect(rect, radius, radius, paint);
            }
            glass.setBounds(getBounds()); glass.draw(canvas);
        }
        public void setAlpha(int alpha) { paint.setAlpha(alpha); glass.setAlpha(alpha); invalidateSelf(); }
        public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * Custom vector StatusIndicatorDrawable (Taste-Skill Anti-Emoji / Vector Primitive Rule).
     * Draws a precision concentric hardware indicator instead of text characters.
     */
    private static final class StatusIndicatorDrawable extends Drawable {
        private final float dp;
        private final boolean active;
        private final int sizePx;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path checkPath = new Path();

        StatusIndicatorDrawable(float dp, boolean active) {
            this.dp = dp;
            this.active = active;
            this.sizePx = Math.max(12, Math.round(15 * dp));
            this.paint.setStrokeCap(Paint.Cap.ROUND);
            this.paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public int getIntrinsicWidth() {
            return sizePx;
        }

        @Override
        public int getIntrinsicHeight() {
            return sizePx;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float r = Math.min(b.width(), b.height()) * 0.44f;

            if (active) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x36D8ECFF);
                canvas.drawCircle(cx, cy, r, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1.2f, 1.35f * dp));
                paint.setColor(0xF2FFFFFF);
                canvas.drawCircle(cx, cy, r, paint);

                checkPath.reset();
                checkPath.moveTo(cx - r * 0.42f, cy + r * 0.03f);
                checkPath.lineTo(cx - r * 0.08f, cy + r * 0.36f);
                checkPath.lineTo(cx + r * 0.46f, cy - r * 0.28f);
                paint.setStrokeWidth(Math.max(1.4f, 1.55f * dp));
                paint.setColor(0xFFFFFFFF);
                canvas.drawPath(checkPath, paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1.0f, 1.15f * dp));
                paint.setColor(0x42FFFFFF);
                canvas.drawCircle(cx, cy, r * 0.86f, paint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return -3;
        }
    }

    /**
     * Taste-Skill Section 4.B: "Button-in-Button" Trailing Icon Badge.
     * Renders a nested circular glass wrapper with a crisp vector chevron inside.
     */
    private static final class TrailingChevronBadgeDrawable extends Drawable {
        private final float dp;
        private final int sizePx;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path chevron = new Path();

        TrailingChevronBadgeDrawable(float dp) {
            this.dp = dp;
            this.sizePx = Math.max(16, Math.round(21 * dp));
            this.paint.setStrokeCap(Paint.Cap.ROUND);
            this.paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public int getIntrinsicWidth() {
            return sizePx;
        }

        @Override
        public int getIntrinsicHeight() {
            return sizePx;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float r = Math.min(b.width(), b.height()) * 0.46f;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x1EFFFFFF);
            canvas.drawCircle(cx, cy, r, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.0f, 1.0f * dp));
            paint.setColor(0x38FFFFFF);
            canvas.drawCircle(cx, cy, r, paint);

            chevron.reset();
            chevron.moveTo(cx - r * 0.16f, cy - r * 0.34f);
            chevron.lineTo(cx + r * 0.22f, cy);
            chevron.lineTo(cx - r * 0.16f, cy + r * 0.34f);
            paint.setStrokeWidth(Math.max(1.3f, 1.45f * dp));
            paint.setColor(0xEEFFFFFF);
            canvas.drawPath(chevron, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return -3;
        }
    }

    public static void closeWallpaperListMenu(WallpaperPopup popup, View anchor) {
        if (popup == null) return;
        View menu = popup.effectMenu;
        popup.effectMenu = null;
        if (menu != null) {
            ViewParent parent = menu.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(menu);
            }
        }
        if (anchor != null) {
            anchor.requestFocus();
        }
    }

    private static boolean isConfirmKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
            || keyCode == KeyEvent.KEYCODE_ENTER
            || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            || keyCode == KeyEvent.KEYCODE_SPACE;
    }

    // =========================================================================
    // Apple tvOS Ambient Screensaver Engine (Double-Bezel Glass HUD + OLED Care)
    // =========================================================================

    private static final Handler sAmbientHandler = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> sAmbientActivityRef;
    private static volatile long sLastUserActivityMs = SystemClock.uptimeMillis();
    private static volatile boolean sAmbientActive = false;
    private static int sConsumeWakeKeyCode = -1;
    private static int sBurnInStep = 0;

    private static final Runnable sAmbientCheckRunnable = new Runnable() {
        @Override
        public void run() {
            Activity act = sAmbientActivityRef != null ? sAmbientActivityRef.get() : null;
            if (act == null || act.isFinishing() || act.isDestroyed()) return;
            int timeoutSec = getAmbientTimeoutSec(act);
            if (timeoutSec <= 0) return;

            if (sAmbientActive) {
                if (!act.hasWindowFocus()) {
                    exitAmbientMode(act, false);
                }
                return;
            }

            long now = SystemClock.uptimeMillis();
            long idleMs = now - sLastUserActivityMs;
            long targetMs = timeoutSec * 1000L;
            if (idleMs >= targetMs) {
                if (canEnterAmbient(act)) {
                    enterAmbientMode(act);
                } else {
                    sLastUserActivityMs = now;
                    sAmbientHandler.postDelayed(this, 15000L);
                }
            } else {
                long remaining = Math.max(5000L, targetMs - idleMs);
                sAmbientHandler.postDelayed(this, remaining);
            }
        }
    };

    private static final Runnable sAmbientRotateRunnable = new Runnable() {
        @Override
        public void run() {
            Activity act = sAmbientActivityRef != null ? sAmbientActivityRef.get() : null;
            if (!sAmbientActive || act == null || act.isFinishing() || act.isDestroyed()) {
                return;
            }
            if (!act.hasWindowFocus()) {
                exitAmbientMode(act, false);
                return;
            }
            WallpaperCollectionHelper.rotateWallpaperSilent(act);

            if (act instanceof MainActivity) {
                FrameLayout root = ((MainActivity) act).root;
                if (root != null) {
                    View hud = root.findViewWithTag(TAG_AMBIENT_HUD);
                    if (hud != null) {
                        float dp = act.getResources().getDisplayMetrics().density;
                        sBurnInStep = (sBurnInStep + 1) & 3;
                        float dx = ((sBurnInStep == 1 || sBurnInStep == 2) ? 6.0f : -4.0f) * dp;
                        float dy = ((sBurnInStep >= 2) ? -4.0f : 3.0f) * dp;
                        hud.animate()
                            .translationX(dx)
                            .translationY(dy)
                            .setDuration(900L)
                            .setInterpolator(new DecelerateInterpolator(1.5f))
                            .withLayer()
                            .start();
                    }
                }
            }
            sAmbientHandler.postDelayed(this, 25000L);
        }
    };

    public static boolean isAmbientActive() {
        return sAmbientActive;
    }

    public static int getAmbientTimeoutSec(Context context) {
        if (context == null) return DEFAULT_AMBIENT_TIMEOUT_SEC;
        return context.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)
            .getInt(KEY_AMBIENT_TIMEOUT, DEFAULT_AMBIENT_TIMEOUT_SEC);
    }

    public static void setAmbientTimeoutSec(Context context, int sec) {
        if (context == null) return;
        context.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_AMBIENT_TIMEOUT, Math.max(0, sec))
            .apply();
    }

    public static String ambientTimeoutLabel(int sec, boolean vi) {
        if (sec <= 0) return vi ? "Tắt" : "Off";
        int min = Math.max(1, sec / 60);
        return vi ? (min + " phút") : (min + " min");
    }

    public static String ambientOptionLabel(int sec, boolean vi) {
        if (sec <= 0) return vi ? "Tắt chế độ chờ" : "Off";
        int min = Math.max(1, sec / 60);
        return vi ? ("Sau " + min + " phút") : ("After " + min + " min");
    }

    public static void scheduleAmbientCheck(Activity activity) {
        if (activity == null) return;
        sAmbientActivityRef = new WeakReference<>(activity);
        sLastUserActivityMs = SystemClock.uptimeMillis();
        sAmbientHandler.removeCallbacks(sAmbientCheckRunnable);
        int timeoutSec = getAmbientTimeoutSec(activity);
        if (timeoutSec > 0 && !sAmbientActive) {
            sAmbientHandler.postDelayed(sAmbientCheckRunnable, timeoutSec * 1000L);
        }
    }

    private static boolean canEnterAmbient(Activity activity) {
        if (!(activity instanceof MainActivity)) return false;
        if (activity.isFinishing() || activity.isDestroyed() || !activity.hasWindowFocus()) return false;
        MainActivity main = (MainActivity) activity;
        if (!main.started || main.reordering || main.dockEditing || main.openPopup != null) return false;
        if (main.appLibrary != null && !main.appLibrary.closed
                && main.appLibrary.popup != null && main.appLibrary.popup.isShowing()) {
            return false;
        }
        if (main.cardPrompt != null && main.cardPrompt.isShowing()) {
            return false;
        }
        return main.root != null && main.grid != null;
    }

    /**
     * Hooked in MainActivity.dispatchKeyEvent. Tracks user activity for idle timer and
     * intercepts the first remote key press to wake smoothly from Ambient Screensaver Mode.
     */
    public static boolean interceptAmbientKeyEvent(Activity activity, KeyEvent event) {
        if (activity == null || event == null) return false;
        sAmbientActivityRef = new WeakReference<>(activity);
        sLastUserActivityMs = SystemClock.uptimeMillis();

        int keyCode = event.getKeyCode();
        int action = event.getAction();

        if (sAmbientActive) {
            if (action == KeyEvent.ACTION_DOWN) {
                sConsumeWakeKeyCode = keyCode;
                exitAmbientMode(activity, true);
            }
            return true;
        }

        if (sConsumeWakeKeyCode != -1 && keyCode == sConsumeWakeKeyCode) {
            if (action == KeyEvent.ACTION_UP) {
                sConsumeWakeKeyCode = -1;
            }
            return true;
        }

        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            scheduleAmbientCheck(activity);
        }
        return false;
    }

    public static void enterAmbientMode(final Activity activity) {
        if (!(activity instanceof MainActivity)) return;
        final MainActivity main = (MainActivity) activity;
        if (main.isFinishing() || main.isDestroyed() || main.root == null || main.grid == null) return;

        sAmbientActivityRef = new WeakReference<>(activity);
        sAmbientActive = true;
        DockStats.setAmbient(activity, true);
        HomeDepthMotion.reset(main);
        sBurnInStep = 0;
        sAmbientHandler.removeCallbacks(sAmbientCheckRunnable);
        sAmbientHandler.removeCallbacks(sAmbientRotateRunnable);

        final FrameLayout root = main.root;
        final float dp = activity.getResources().getDisplayMetrics().density;

        View existingHud = root.findViewWithTag(TAG_AMBIENT_HUD);
        if (existingHud != null) {
            root.removeView(existingHud);
        }

        main.grid.animate()
            .alpha(0f)
            .setDuration(520L)
            .setInterpolator(new DecelerateInterpolator(1.6f))
            .withLayer()
            .start();

        if (main.glass != null) {
            // Delayed tile focus callbacks can restore alpha while Ambient is active.
            main.glass.animate().cancel();
            main.glass.setAlpha(0f);
            main.glass.setVisibility(View.INVISIBLE);
        }

        View clockPill = root.findViewWithTag("clock_pill_dock");
        if (clockPill != null) {
            clockPill.animate().cancel();
            clockPill.setAlpha(0f);
        }

        // Transparent glass clock over the current wallpaper.
        final FrameLayout hudOuter = new FrameLayout(activity);
        hudOuter.setTag(TAG_AMBIENT_HUD);
        int shellPad = Math.round(5 * dp);
        hudOuter.setPadding(shellPad, shellPad, shellPad, shellPad);
        float outerRadiusPx = Math.round(24 * dp);
        GradientDrawable outerBg = new GradientDrawable();
        outerBg.setColor(0x00FFFFFF);
        outerBg.setCornerRadius(outerRadiusPx);
        hudOuter.setBackground(outerBg);

        final LinearLayout hudCard = new LinearLayout(activity);
        hudCard.setOrientation(LinearLayout.VERTICAL);
        float innerRadiusPx = Math.round(19 * dp);
        hudCard.setBackground(ClearGlass.panel(innerRadiusPx, dp));
        int padH = Math.round(22 * dp);
        int padV = Math.round(16 * dp);
        hudCard.setPadding(padH, padV, padH, padV);

        // Large Tabular Hero Clock
        TextClock bigClock = new TextClock(activity);
        bigClock.setFormat12Hour("HH:mm:ss");
        bigClock.setFormat24Hour("HH:mm:ss");
        bigClock.setTextSize(42.0f);
        bigClock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        bigClock.setFontFeatureSettings("tnum");
        bigClock.setLetterSpacing(-0.02f);
        bigClock.setTextColor(0xFFFAFBFF);
        bigClock.setIncludeFontPadding(false);
        bigClock.setSingleLine(true);
        ClearGlass.shadow(bigClock, dp);
        hudCard.addView(bigClock, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        // Date
        LinearLayout metaRow = new LinearLayout(activity);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        metaLp.topMargin = Math.round(4 * dp);

        TextClock dateClock = new TextClock(activity);
        String datePattern = "dd/MM/yyyy";
        dateClock.setFormat12Hour(datePattern);
        dateClock.setFormat24Hour(datePattern);
        dateClock.setTextSize(12.0f);
        dateClock.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        dateClock.setFontFeatureSettings("tnum");
        dateClock.setTextColor(0xE6E8F0FA);
        dateClock.setSingleLine(true);
        metaRow.addView(dateClock);

        hudCard.addView(metaRow, metaLp);

        hudOuter.addView(hudCard, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ));

        FrameLayout.LayoutParams hudLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM | Gravity.START
        );
        hudLp.leftMargin = Math.round(38 * dp);
        hudLp.bottomMargin = Math.round(34 * dp);
        FontChoice.apply(hudOuter);
        root.addView(hudOuter, hudLp);

        hudOuter.setAlpha(0f);
        hudOuter.setTranslationY(12 * dp);
        hudOuter.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(460L)
            .setInterpolator(new DecelerateInterpolator(1.7f))
            .withLayer()
            .start();

        sAmbientHandler.postDelayed(sAmbientRotateRunnable, 25000L);
    }

    public static void exitAmbientMode(final Activity activity, boolean playSound) {
        sAmbientActive = false;
        sAmbientHandler.removeCallbacks(sAmbientRotateRunnable);
        if (activity == null) return;

        if (playSound) {
            playOpen(activity);
        }

        if (activity instanceof MainActivity) {
            final MainActivity main = (MainActivity) activity;
            if (main.grid != null) {
                main.grid.animate()
                    .alpha(1f)
                    .setDuration(220L)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .withLayer()
                    .start();
            }
            if (main.glass != null) {
                boolean showFocus = main.grid != null && main.grid.hasFocus() && main.glassShown;
                main.glass.animate().cancel();
                main.glass.setAlpha(0f);
                main.glass.setVisibility(View.VISIBLE);
                main.glass.invalidate();
                main.glass.animate()
                    .alpha(showFocus ? 1f : 0f)
                    .setDuration(220L)
                    .withLayer()
                    .start();
            }
            if (main.root != null) {
                View clockPill = main.root.findViewWithTag("clock_pill_dock");
                if (clockPill != null) {
                    clockPill.animate()
                        .alpha(1f)
                        .setDuration(220L)
                        .withLayer()
                        .start();
                }
                final View hud = main.root.findViewWithTag(TAG_AMBIENT_HUD);
                if (hud != null) {
                    hud.animate()
                        .alpha(0f)
                        .setDuration(180L)
                        .withLayer()
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                if (main.root != null) {
                                    main.root.removeView(hud);
                                }
                            }
                        })
                        .start();
                }
            }
        }
        ControlPanelHelper.ensureClockPillVisible(activity);
        DockStats.setAmbient(activity, false);
        scheduleAmbientCheck(activity);
    }

    // =========================================================================
    // Procedural Acoustic Synthesis (44.1 kHz 16-bit PCM)
    // =========================================================================

    // --- Pack 1: PS5 Cosmic Glass ---

    static short[] synthCrystalTick() {
        int n = (int) (SAMPLE_RATE * 0.045);
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / SAMPLE_RATE;
            double env = Math.sin(Math.min(1.0, t / 0.0025) * Math.PI * 0.5) * Math.exp(-t * 95.0);
            double f = 587.33;
            double v = 0.55 * Math.sin(2.0 * Math.PI * f * t)
                     + 0.30 * Math.sin(2.0 * Math.PI * f * 2.0 * t) * Math.exp(-t * 140.0)
                     + 0.15 * Math.sin(2.0 * Math.PI * f * 0.5 * t);
            raw[i] = v * env;
        }
        return normalizePeak(raw, 0.80);
    }

    static short[] synthCrystalSelect() {
        int n = (int) (SAMPLE_RATE * 0.135);
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / SAMPLE_RATE;
            double e1 = Math.sin(Math.min(1.0, t / 0.003) * Math.PI * 0.5) * Math.exp(-t * 28.0);
            double v = (0.45 * Math.sin(2.0 * Math.PI * 587.33 * t)
                      + 0.25 * Math.sin(2.0 * Math.PI * 1174.66 * t) * Math.exp(-t * 45.0)) * e1;
            if (t >= 0.038) {
                double t2 = t - 0.038;
                double e2 = Math.sin(Math.min(1.0, t2 / 0.003) * Math.PI * 0.5) * Math.exp(-t2 * 22.0);
                v += (0.50 * Math.sin(2.0 * Math.PI * 880.0 * t2)
                    + 0.25 * Math.sin(2.0 * Math.PI * 1760.0 * t2) * Math.exp(-t2 * 35.0)) * e2;
            }
            raw[i] = v;
        }
        return normalizePeak(raw, 0.82);
    }

    static short[] synthCrystalOpen() {
        return synthCrystalArp(new double[]{523.25, 659.25, 783.99, 1046.50}, 0.240, 0.032, 14.0);
    }

    static short[] synthCrystalClose() {
        return synthCrystalArp(new double[]{1046.50, 783.99, 659.25, 523.25}, 0.155, 0.026, 18.0);
    }

    static short[] synthCrystalWallpaper() {
        return synthCrystalArp(new double[]{523.25, 659.25, 783.99, 987.77, 1174.66}, 0.380, 0.045, 7.0);
    }

    static short[] synthCrystalHold() {
        return synthCrystalArp(new double[]{659.25, 987.77}, 0.120, 0.032, 22.0);
    }

    private static short[] synthCrystalArp(double[] notes, double dur, double step, double decay) {
        int n = (int) (SAMPLE_RATE * dur);
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / SAMPLE_RATE;
            double v = 0.0;
            for (int k = 0; k < notes.length; k++) {
                double dt = t - k * step;
                if (dt > 0.0) {
                    double env = Math.sin(Math.min(1.0, dt / 0.006) * Math.PI * 0.5) * Math.exp(-dt * decay);
                    double f = notes[k];
                    v += (0.30 * Math.sin(2.0 * Math.PI * f * dt)
                        + 0.12 * Math.sin(2.0 * Math.PI * f * 2.0 * dt)) * env;
                }
            }
            raw[i] = v;
        }
        return normalizePeak(raw, 0.82);
    }

    // --- Pack 2: Apple tvOS Velvet ---

    static short[] synthVelvetTick() {
        int n = (int) (SAMPLE_RATE * 0.040);
        double[] raw = new double[n];
        double phase = 0.0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SAMPLE_RATE;
            double f = 110.0 + 190.0 * Math.exp(-t * 260.0);
            phase += 2.0 * Math.PI * f / SAMPLE_RATE;
            double env = Math.sin(Math.min(1.0, t / 0.0014) * Math.PI * 0.5) * Math.exp(-t * 135.0);
            raw[i] = (Math.sin(phase) + 0.22 * Math.sin(2.0 * Math.PI * 1350.0 * t) * Math.exp(-t * 320.0)) * env;
        }
        return normalizePeak(raw, 0.82);
    }

    static short[] synthVelvetSelect() {
        int n = (int) (SAMPLE_RATE * 0.135);
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / SAMPLE_RATE;
            double e1 = Math.sin(Math.min(1.0, t / 0.002) * Math.PI * 0.5) * Math.exp(-t * 50.0);
            double v = (0.65 * Math.sin(2.0 * Math.PI * 246.94 * t) + 0.25 * Math.sin(2.0 * Math.PI * 493.88 * t)) * e1;
            if (t >= 0.036) {
                double t2 = t - 0.036;
                double e2 = Math.sin(Math.min(1.0, t2 / 0.002) * Math.PI * 0.5) * Math.exp(-t2 * 32.0);
                v += (0.70 * Math.sin(2.0 * Math.PI * 369.99 * t2) + 0.30 * Math.sin(2.0 * Math.PI * 739.99 * t2)) * e2;
            }
            raw[i] = v;
        }
        return normalizePeak(raw, 0.82);
    }

    static short[] synthVelvetOpen() {
        return synthVelvetGlide(220.0, 329.63, 0.230);
    }

    static short[] synthVelvetClose() {
        return synthVelvetGlide(329.63, 220.0, 0.150);
    }

    static short[] synthVelvetWallpaper() {
        int n = (int) (SAMPLE_RATE * 0.370);
        double[] raw = new double[n];
        double[] chord = {261.63, 329.63, 392.00, 493.88, 587.33};
        for (int i = 0; i < n; i++) {
            double t = (double) i / SAMPLE_RATE;
            double env = Math.sin(Math.min(1.0, t / 0.025) * Math.PI * 0.5) * Math.exp(-t * 6.5);
            double v = 0.0;
            for (int k = 0; k < chord.length; k++) {
                double dt = t - k * 0.024;
                if (dt > 0.0) {
                    double f = chord[k];
                    double e = Math.sin(Math.min(1.0, dt / 0.015) * Math.PI * 0.5) * Math.exp(-dt * 6.8);
                    double tine = 0.15 * Math.sin(2.0 * Math.PI * f * 4.0 * dt) * Math.exp(-dt * 24.0);
                    v += (Math.sin(2.0 * Math.PI * f * dt) + tine) * e;
                }
            }
            raw[i] = v * env;
        }
        return normalizePeak(raw, 0.82);
    }

    static short[] synthVelvetHold() {
        return synthVelvetGlide(261.63, 392.00, 0.120);
    }

    private static short[] synthVelvetGlide(double f0, double f1, double dur) {
        int n = (int) (SAMPLE_RATE * dur);
        double[] raw = new double[n];
        double ph = 0.0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SAMPLE_RATE;
            double p = Math.min(1.0, t / (dur * 0.55));
            double f = f0 + (f1 - f0) * (p * p * (3.0 - 2.0 * p));
            ph += 2.0 * Math.PI * f / SAMPLE_RATE;
            double env = Math.sin(Math.min(1.0, t / 0.012) * Math.PI * 0.5) * Math.exp(-t * 14.0);
            raw[i] = (0.60 * Math.sin(ph) + 0.25 * Math.sin(ph * 1.5) + 0.15 * Math.sin(ph * 2.0)) * env;
        }
        return normalizePeak(raw, 0.82);
    }

    // --- Pack 3: Nintendo Switch Joy-Con Pop ---

    static short[] synthSwitchTick() {
        int n = (int) (SAMPLE_RATE * 0.040);
        double[] raw = new double[n];
        double ph = 0.0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SAMPLE_RATE;
            double f = 920.0 + 680.0 * Math.exp(-t * 450.0);
            ph += 2.0 * Math.PI * f / SAMPLE_RATE;
            double att = Math.sin(Math.min(1.0, t / 0.001) * Math.PI * 0.5);
            double env = att * Math.exp(-t * 185.0);
            raw[i] = (Math.sin(ph) + 0.45 * Math.sin(ph * 2.3) * Math.exp(-t * 450.0)) * env;
        }
        return normalizePeak(raw, 0.82);
    }

    static short[] synthSwitchSelect() {
        int n = (int) (SAMPLE_RATE * 0.135);
        double[] raw = new double[n];
        double ph1 = 0.0, ph2 = 0.0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SAMPLE_RATE;
            double att = Math.sin(Math.min(1.0, t / 0.0012) * Math.PI * 0.5);
            double f1 = 587.33 + 400.0 * Math.exp(-t * 300.0);
            ph1 += 2.0 * Math.PI * f1 / SAMPLE_RATE;
            double v = Math.sin(ph1) * att * Math.exp(-t * 60.0) * 0.55;
            if (t >= 0.036) {
                double t2 = t - 0.036;
                double att2 = Math.sin(Math.min(1.0, t2 / 0.0012) * Math.PI * 0.5);
                double f2 = 880.0 + 320.0 * (1.0 - Math.exp(-t2 * 90.0));
                ph2 += 2.0 * Math.PI * f2 / SAMPLE_RATE;
                v += (Math.sin(ph2) + 0.30 * Math.sin(ph2 * 2.0)) * att2 * Math.exp(-t2 * 34.0) * 0.65;
            }
            raw[i] = v;
        }
        return normalizePeak(raw, 0.82);
    }

    static short[] synthSwitchOpen() {
        return synthSwitchArp(new double[]{587.33, 739.99, 880.0, 1174.66}, 0.230, 0.034, 26.0);
    }

    static short[] synthSwitchClose() {
        return synthSwitchArp(new double[]{1174.66, 880.0, 739.99, 587.33}, 0.150, 0.026, 30.0);
    }

    static short[] synthSwitchWallpaper() {
        return synthSwitchArp(new double[]{587.33, 739.99, 880.0, 1174.66, 1479.98}, 0.370, 0.045, 9.5);
    }

    static short[] synthSwitchHold() {
        return synthSwitchArp(new double[]{739.99, 1174.66}, 0.120, 0.034, 26.0);
    }

    private static short[] synthSwitchArp(double[] notes, double dur, double step, double decay) {
        int n = (int) (SAMPLE_RATE * dur);
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / SAMPLE_RATE;
            double v = 0.0;
            for (int k = 0; k < notes.length; k++) {
                double dt = t - k * step;
                if (dt > 0.0) {
                    double env = Math.sin(Math.min(1.0, dt / 0.002) * Math.PI * 0.5) * Math.exp(-dt * decay);
                    double f = notes[k];
                    v += (Math.sin(2.0 * Math.PI * f * dt) + 0.25 * Math.sin(2.0 * Math.PI * f * 2.0 * dt)) * env;
                }
            }
            raw[i] = v;
        }
        return normalizePeak(raw, 0.82);
    }

    // --- Pack 4: Zen Water Droplet ---

    static short[] synthDropletTick() {
        return synthDropletSequence(0.042, new double[]{0.0}, new double[]{480.0}, new double[]{1020.0}, 0.014, 0.011);
    }

    static short[] synthDropletSelect() {
        return synthDropletSequence(0.135, new double[]{0.0, 0.038}, new double[]{480.0, 680.0}, new double[]{980.0, 1400.0}, 0.016, 0.022);
    }

    static short[] synthDropletOpen() {
        return synthDropletSequence(0.230, new double[]{0.0, 0.038, 0.076, 0.114}, new double[]{420.0, 540.0, 680.0, 860.0}, new double[]{860.0, 1100.0, 1380.0, 1740.0}, 0.018, 0.026);
    }

    static short[] synthDropletClose() {
        return synthDropletSequence(0.150, new double[]{0.0, 0.032, 0.064}, new double[]{860.0, 640.0, 460.0}, new double[]{620.0, 460.0, 340.0}, 0.016, 0.024);
    }

    static short[] synthDropletWallpaper() {
        return synthDropletSequence(0.365, new double[]{0.0, 0.048, 0.096, 0.144, 0.192}, new double[]{440.0, 554.37, 659.25, 880.0, 1108.73}, new double[]{880.0, 1108.73, 1318.51, 1760.0, 2217.46}, 0.022, 0.042);
    }

    static short[] synthDropletHold() {
        return synthDropletSequence(0.120, new double[]{0.0, 0.036}, new double[]{560.0, 780.0}, new double[]{1160.0, 1600.0}, 0.014, 0.022);
    }

    private static short[] synthDropletSequence(
            double durationSec,
            double[] starts,
            double[] f0s,
            double[] f1s,
            double tauSweep,
            double tauDecay) {
        int n = (int) (SAMPLE_RATE * durationSec);
        double[] raw = new double[n];
        int count = starts.length;
        double[] phases = new double[count];
        double dt = 1.0 / SAMPLE_RATE;

        for (int i = 0; i < n; i++) {
            double t = i * dt;
            double sum = 0.0;
            for (int k = 0; k < count; k++) {
                double tk = t - starts[k];
                if (tk >= 0.0) {
                    double freq = f0s[k] + (f1s[k] - f0s[k]) * (1.0 - Math.exp(-tk / tauSweep));
                    phases[k] += 2.0 * Math.PI * freq * dt;
                    double attack = Math.sin(Math.min(1.0, tk / 0.002) * Math.PI * 0.5);
                    double env = attack * Math.exp(-tk / tauDecay);
                    sum += (Math.sin(phases[k]) * 0.82 + Math.sin(phases[k] * 2.0) * 0.18) * env;
                }
            }
            raw[i] = sum;
        }
        return normalizePeak(raw, 0.82);
    }

    // --- Pack 5: Cyber Hologram Sci-Fi ---

    static short[] synthCyberTick() {
        int n = (int) (SAMPLE_RATE * 0.040);
        double[] raw = new double[n];
        double ph = 0.0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SAMPLE_RATE;
            double f = 1650.0 * Math.exp(-t * 55.0) + 520.0;
            ph += 2.0 * Math.PI * f / SAMPLE_RATE;
            double env = Math.sin(Math.min(1.0, t / 0.0012) * Math.PI * 0.5) * Math.exp(-t * 165.0);
            double mod = Math.sin(2.0 * Math.PI * 2400.0 * t) * 0.35 * Math.exp(-t * 240.0);
            raw[i] = Math.sin(ph + mod) * env;
        }
        return normalizePeak(raw, 0.80);
    }

    static short[] synthCyberSelect() {
        int n = (int) (SAMPLE_RATE * 0.135);
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / SAMPLE_RATE;
            double att = Math.sin(Math.min(1.0, t / 0.0015) * Math.PI * 0.5);
            double e1 = att * Math.exp(-t * 42.0);
            double v = Math.sin(2.0 * Math.PI * 784.0 * t + 0.5 * Math.sin(2.0 * Math.PI * 1568.0 * t) * e1) * e1 * 0.45;
            if (t >= 0.035) {
                double t2 = t - 0.035;
                double att2 = Math.sin(Math.min(1.0, t2 / 0.0015) * Math.PI * 0.5);
                double e2 = att2 * Math.exp(-t2 * 24.0);
                v += Math.sin(2.0 * Math.PI * 1174.66 * t2 + 0.4 * Math.sin(2.0 * Math.PI * 2349.32 * t2) * e2) * e2 * 0.55;
            }
            raw[i] = v;
        }
        return normalizePeak(raw, 0.82);
    }

    static short[] synthCyberOpen() {
        return synthCyberSweep(true, 0.230);
    }

    static short[] synthCyberClose() {
        return synthCyberSweep(false, 0.150);
    }

    static short[] synthCyberWallpaper() {
        int n = (int) (SAMPLE_RATE * 0.370);
        double[] raw = new double[n];
        double[] freqs = {392.00, 587.33, 783.99, 1046.50, 1567.98};
        for (int i = 0; i < n; i++) {
            double t = (double) i / SAMPLE_RATE;
            double v = 0.0;
            for (int k = 0; k < freqs.length; k++) {
                double dt = t - k * 0.042;
                if (dt > 0.0) {
                    double f = freqs[k];
                    double env = Math.sin(Math.min(1.0, dt / 0.006) * Math.PI * 0.5) * Math.exp(-dt * 7.2);
                    double fm = 0.45 * Math.sin(2.0 * Math.PI * f * 2.0 * dt) * Math.exp(-dt * 14.0);
                    v += Math.sin(2.0 * Math.PI * f * dt + fm) * env;
                }
            }
            raw[i] = v;
        }
        return normalizePeak(raw, 0.82);
    }

    static short[] synthCyberHold() {
        return synthCyberSweep(true, 0.120);
    }

    private static short[] synthCyberSweep(boolean up, double dur) {
        int n = (int) (SAMPLE_RATE * dur);
        double[] raw = new double[n];
        double ph = 0.0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SAMPLE_RATE;
            double p = Math.min(1.0, t / (dur * 0.78));
            double f = up ? (380.0 + 760.0 * Math.pow(p, 1.5)) : (1140.0 - 760.0 * Math.pow(p, 1.3));
            ph += 2.0 * Math.PI * f / SAMPLE_RATE;
            double env = Math.sin(Math.min(1.0, t / 0.008) * Math.PI * 0.5) * Math.exp(-t * 14.0);
            raw[i] = (0.65 * Math.sin(ph) + 0.35 * Math.sin(ph * 1.5)) * env;
        }
        return normalizePeak(raw, 0.82);
    }

    // --- Pack 6: Hi-Fi Mechanical & Kalimba ---

    static short[] synthKalimbaTick() {
        int n = (int) (SAMPLE_RATE * 0.040);
        double[] raw = new double[n];
        double ph1 = 0.0, ph2 = 0.0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SAMPLE_RATE;
            double att = Math.sin(Math.min(1.0, t / 0.001) * Math.PI * 0.5);
            double f1 = 155.0 + 120.0 * Math.exp(-t * 320.0);
            double f2 = 820.0;
            ph1 += 2.0 * Math.PI * f1 / SAMPLE_RATE;
            ph2 += 2.0 * Math.PI * f2 / SAMPLE_RATE;
            raw[i] = (0.72 * Math.sin(ph1) * Math.exp(-t * 155.0)
                    + 0.28 * Math.sin(ph2) * Math.exp(-t * 360.0)) * att;
        }
        return normalizePeak(raw, 0.82);
    }

    static short[] synthKalimbaSelect() {
        return synthKalimbaSequence(new double[]{523.25, 783.99}, 0.135, 0.038, 18.0);
    }

    static short[] synthKalimbaOpen() {
        return synthKalimbaSequence(new double[]{440.0, 554.37, 659.25, 880.0}, 0.230, 0.034, 15.0);
    }

    static short[] synthKalimbaClose() {
        return synthKalimbaSequence(new double[]{880.0, 659.25, 554.37, 440.0}, 0.150, 0.026, 18.0);
    }

    static short[] synthKalimbaWallpaper() {
        return synthKalimbaSequence(new double[]{440.0, 554.37, 659.25, 830.61, 987.77, 1318.51}, 0.370, 0.042, 8.5);
    }

    static short[] synthKalimbaHold() {
        return synthKalimbaSequence(new double[]{587.33, 880.0}, 0.120, 0.034, 18.0);
    }

    private static short[] synthKalimbaSequence(double[] notes, double dur, double step, double decay) {
        int n = (int) (SAMPLE_RATE * dur);
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / SAMPLE_RATE;
            double v = 0.0;
            for (int k = 0; k < notes.length; k++) {
                double dt = t - k * step;
                if (dt > 0.0) {
                    double f = notes[k];
                    double env = Math.sin(Math.min(1.0, dt / 0.002) * Math.PI * 0.5) * Math.exp(-dt * decay);
                    double tine = 0.28 * Math.sin(2.0 * Math.PI * f * 2.92 * dt) * Math.exp(-dt * 55.0);
                    v += (Math.sin(2.0 * Math.PI * f * dt) + tine) * env;
                }
            }
            raw[i] = v;
        }
        return normalizePeak(raw, 0.82);
    }

    private static short[] normalizePeak(double[] raw, double targetPeak) {
        int n = raw.length;
        short[] pcm = new short[n];
        if (n == 0) return pcm;
        raw[0] = 0.0;
        double max = 1e-9;
        for (int i = 1; i < n; i++) {
            double a = Math.abs(raw[i]);
            if (a > max) max = a;
        }
        double scale = targetPeak / max;
        pcm[0] = 0;
        for (int i = 1; i < n; i++) {
            pcm[i] = clamp16(raw[i] * scale);
        }
        return pcm;
    }

    private static short clamp16(double sample) {
        if (sample > 0.98) sample = 0.98;
        else if (sample < -0.98) sample = -0.98;
        return (short) Math.round(sample * 32767.0);
    }

    static void writeWav(File file, short[] pcm) throws IOException {
        int dataBytes = pcm.length * 2;
        int totalBytes = 44 + dataBytes;
        byte[] buf = new byte[totalBytes];

        // RIFF header
        buf[0] = 'R'; buf[1] = 'I'; buf[2] = 'F'; buf[3] = 'F';
        writeIntLE(buf, 4, 36 + dataBytes);
        buf[8] = 'W'; buf[9] = 'A'; buf[10] = 'V'; buf[11] = 'E';

        // fmt chunk (16-bit mono PCM @ 44100 Hz)
        buf[12] = 'f'; buf[13] = 'm'; buf[14] = 't'; buf[15] = ' ';
        writeIntLE(buf, 16, 16);
        writeShortLE(buf, 20, (short) 1); // PCM
        writeShortLE(buf, 22, (short) 1); // 1 channel
        writeIntLE(buf, 24, SAMPLE_RATE);
        writeIntLE(buf, 28, SAMPLE_RATE * 2);
        writeShortLE(buf, 32, (short) 2);
        writeShortLE(buf, 34, (short) 16);

        // data chunk
        buf[36] = 'd'; buf[37] = 'a'; buf[38] = 't'; buf[39] = 'a';
        writeIntLE(buf, 40, dataBytes);

        int offset = 44;
        for (short s : pcm) {
            buf[offset] = (byte) (s & 0xFF);
            buf[offset + 1] = (byte) ((s >>> 8) & 0xFF);
            offset += 2;
        }

        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(buf);
        }
    }

    private static void writeIntLE(byte[] b, int off, int val) {
        b[off] = (byte) (val & 0xFF);
        b[off + 1] = (byte) ((val >>> 8) & 0xFF);
        b[off + 2] = (byte) ((val >>> 16) & 0xFF);
        b[off + 3] = (byte) ((val >>> 24) & 0xFF);
    }

    private static void writeShortLE(byte[] b, int off, short val) {
        b[off] = (byte) (val & 0xFF);
        b[off + 1] = (byte) ((val >>> 8) & 0xFF);
    }

    private static final class SoundWindowCallback implements Window.Callback {
        private final Context appContext;
        private final WeakReference<Dialog> dialogRef;
        private final Window.Callback delegate;
        private boolean trackingBack;

        SoundWindowCallback(Context appContext, Dialog dialog, Window.Callback delegate) {
            this.appContext = appContext;
            this.dialogRef = new WeakReference<>(dialog);
            this.delegate = delegate;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            Dialog dialog = dialogRef.get();
            if (dialog != null && PopupOptimizer.isClosing(dialog)) {
                return true;
            }
            CrystalSoundEngine.onKeyEvent(appContext, event);
            if (event != null && dialog != null) {
                int code = event.getKeyCode();
                if ((code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE)
                        && PopupOptimizer.shouldInterceptBack(dialog)) {
                    Window w = dialog.getWindow();
                    if (w != null && w.superDispatchKeyEvent(event)) {
                        trackingBack = false;
                        return true;
                    }
                    int action = event.getAction();
                    if (action == KeyEvent.ACTION_DOWN) {
                        if (event.getRepeatCount() == 0) {
                            trackingBack = true;
                        }
                        return true;
                    }
                    if (action == KeyEvent.ACTION_UP) {
                        boolean wasTracking = trackingBack;
                        trackingBack = false;
                        if (wasTracking && !event.isCanceled()) {
                            PopupOptimizer.dismissAnimated(dialog, true);
                        }
                        return true;
                    }
                } else if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    trackingBack = false;
                }
            }
            return delegate.dispatchKeyEvent(event);
        }

        @Override
        public boolean dispatchKeyShortcutEvent(KeyEvent event) {
            return delegate.dispatchKeyShortcutEvent(event);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            return delegate.dispatchTouchEvent(event);
        }

        @Override
        public boolean dispatchTrackballEvent(MotionEvent event) {
            return delegate.dispatchTrackballEvent(event);
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            return delegate.dispatchGenericMotionEvent(event);
        }

        @Override
        public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
            return delegate.dispatchPopulateAccessibilityEvent(event);
        }

        @Override
        public View onCreatePanelView(int featureId) {
            return delegate.onCreatePanelView(featureId);
        }

        @Override
        public boolean onCreatePanelMenu(int featureId, Menu menu) {
            return delegate.onCreatePanelMenu(featureId, menu);
        }

        @Override
        public boolean onPreparePanel(int featureId, View view, Menu menu) {
            return delegate.onPreparePanel(featureId, view, menu);
        }

        @Override
        public boolean onMenuOpened(int featureId, Menu menu) {
            return delegate.onMenuOpened(featureId, menu);
        }

        @Override
        public boolean onMenuItemSelected(int featureId, MenuItem item) {
            return delegate.onMenuItemSelected(featureId, item);
        }

        @Override
        public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {
            delegate.onWindowAttributesChanged(attrs);
        }

        @Override
        public void onContentChanged() {
            delegate.onContentChanged();
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            delegate.onWindowFocusChanged(hasFocus);
        }

        @Override
        public void onAttachedToWindow() {
            delegate.onAttachedToWindow();
        }

        @Override
        public void onDetachedFromWindow() {
            delegate.onDetachedFromWindow();
        }

        @Override
        public void onPanelClosed(int featureId, Menu menu) {
            delegate.onPanelClosed(featureId, menu);
        }

        @Override
        public boolean onSearchRequested() {
            return delegate.onSearchRequested();
        }

        @Override
        public boolean onSearchRequested(SearchEvent searchEvent) {
            return delegate.onSearchRequested(searchEvent);
        }

        @Override
        public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) {
            return delegate.onWindowStartingActionMode(callback);
        }

        @Override
        public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) {
            return delegate.onWindowStartingActionMode(callback, type);
        }

        @Override
        public void onActionModeStarted(ActionMode mode) {
            delegate.onActionModeStarted(mode);
        }

        @Override
        public void onActionModeFinished(ActionMode mode) {
            delegate.onActionModeFinished(mode);
        }
    }
}
