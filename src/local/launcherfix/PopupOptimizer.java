package local.launcherfix;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupWindow;
import com.gathin.tvhome.MainActivity;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * Unified lightweight popup motion & backdrop helper (fix.128):
 * - Single-pass GPU hardware-layer (.withLayer()) floating entrance & exit across all launcher popups
 * - Downscaled 240x135 backdrop capture with pre-baked 0x48000000 veil (eliminates 1080p shader overdraw)
 * - Automatic Bitmap recycling on dialog detach (zero native Bitmap or GPU texture leaks)
 * - SmoothPopupWindow for PopupWindow-hosted panels (All Apps library & Home tile context menus)
 */
public final class PopupOptimizer {
    private static final int CAPTURE_WIDTH = 240;
    private static final int BACKDROP_DIM_COLOR = 0x48000000;

    public static final Interpolator ENTRANCE_EASE = new PathInterpolator(0.20f, 0.92f, 0.28f, 1.0f);
    public static final Interpolator EXIT_EASE = new PathInterpolator(0.32f, 0.0f, 0.60f, 1.0f);

    private static volatile Handler sMain;
    private static Handler sWorker;
    private static final WeakHashMap<Dialog, PopupRecord> RECORDS = new WeakHashMap<>();

    private static final class PopupRecord {
        final WeakReference<View> backdropRef;
        final WeakReference<View> panelRef;
        final float shiftSign;
        final boolean customSelfManaged;
        boolean closing;

        PopupRecord(View backdrop, View panel, float shiftSign, boolean customSelfManaged) {
            this.backdropRef = new WeakReference<>(backdrop);
            this.panelRef = new WeakReference<>(panel);
            this.shiftSign = shiftSign;
            this.customSelfManaged = customSelfManaged;
        }
    }

    private PopupOptimizer() {}

    private static Handler mainHandler() {
        Handler h = sMain;
        if (h == null) {
            Looper l = Looper.getMainLooper();
            if (l != null) {
                h = new Handler(l);
                sMain = h;
            }
        }
        return h;
    }

    public static synchronized Handler worker() {
        if (sWorker == null) {
            HandlerThread ht = new HandlerThread("popup-backdrop", Process.THREAD_PRIORITY_BACKGROUND);
            ht.start();
            sWorker = new Handler(ht.getLooper());
        }
        return sWorker;
    }

    public static Activity unwrapActivity(Context ctx) {
        while (ctx instanceof ContextWrapper) {
            if (ctx instanceof Activity) return (Activity) ctx;
            Context next = ((ContextWrapper) ctx).getBaseContext();
            if (next == ctx) break;
            ctx = next;
        }
        return null;
    }

    public static boolean isActivityInactive(Context ctx) {
        Activity act = unwrapActivity(ctx);
        if (act == null) return false;
        if (act.isFinishing() || act.isDestroyed()) return true;
        if (act instanceof MainActivity) {
            return !((MainActivity) act).started;
        }
        return false;
    }

    public static boolean motionEnabled(Context ctx) {
        if (!ValueAnimator.areAnimatorsEnabled()) return false;
        Activity act = unwrapActivity(ctx);
        if (act instanceof MainActivity) {
            MainActivity ma = (MainActivity) act;
            return ma.animations && !ma.reducedMotion;
        }
        return true;
    }

    public static int captureWidth() {
        return CAPTURE_WIDTH;
    }

    public static int captureHeight(int screenW, int screenH) {
        int max = Math.max(1, Math.max(screenW, screenH));
        int min = Math.max(1, Math.min(screenW, screenH));
        return Math.max(1, Math.round((min * (float) CAPTURE_WIDTH) / max));
    }

    public static void blurAndDimInPlace(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        try {
            FastPixelOps.blur(bitmap);
            Canvas c = new Canvas(bitmap);
            c.drawColor(BACKDROP_DIM_COLOR);
        } catch (Throwable ignored) {}
    }

    public static void captureBackdrop(Activity activity, final ImageView target, final int screenW, final int screenH) {
        if (activity == null || target == null || activity.isFinishing() || activity.isDestroyed()) return;
        target.setColorFilter(null);
        Window window = activity.getWindow();
        if (window == null) return;

        int h = captureHeight(screenW, screenH);
        final Bitmap shot;
        try {
            shot = Bitmap.createBitmap(CAPTURE_WIDTH, h, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError oom) {
            return;
        }

        try {
            PixelCopy.request(window, shot, new PixelCopy.OnPixelCopyFinishedListener() {
                @Override
                public void onPixelCopyFinished(int copyResult) {
                    if (copyResult != PixelCopy.SUCCESS) {
                        if (!shot.isRecycled()) shot.recycle();
                        return;
                    }
                    blurAndDimInPlace(shot);
                    Handler mh = mainHandler();
                    if (mh == null) {
                        if (!shot.isRecycled()) shot.recycle();
                        return;
                    }
                    mh.post(new Runnable() {
                        @Override
                        public void run() {
                            bindBackdropBitmap(target, shot, screenW, screenH, true);
                        }
                    });
                }
            }, worker());
        } catch (IllegalArgumentException | IllegalStateException error) {
            if (!shot.isRecycled()) shot.recycle();
        }
    }

    private static void bindBackdropBitmap(final ImageView imageView, final Bitmap bitmap,
                                           final int screenW, final int screenH, boolean allowDefer) {
        if (bitmap == null || bitmap.isRecycled()) return;
        if (imageView == null) {
            bitmap.recycle();
            return;
        }
        if (!imageView.isAttachedToWindow()) {
            Handler mh = mainHandler();
            if (allowDefer && imageView.getParent() != null && mh != null) {
                mh.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        bindBackdropBitmap(imageView, bitmap, screenW, screenH, false);
                    }
                }, 48L);
            } else {
                bitmap.recycle();
            }
            return;
        }
        View parent = imageView.getParent() instanceof View ? (View) imageView.getParent() : null;
        if (parent != null && Boolean.TRUE.equals(parent.getTag())) {
            bitmap.recycle();
            return;
        }
        Matrix matrix = new Matrix();
        matrix.setScale(screenW / (float) bitmap.getWidth(), screenH / (float) bitmap.getHeight());
        imageView.setColorFilter(null);
        imageView.setImageMatrix(matrix);
        imageView.setImageBitmap(bitmap);

        imageView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {}

            @Override
            public void onViewDetachedFromWindow(View v) {
                v.removeOnAttachStateChangeListener(this);
                imageView.animate().cancel();
                imageView.setImageDrawable(null);
                if (!bitmap.isRecycled()) {
                    bitmap.recycle();
                }
            }
        });

        if (motionEnabled(imageView.getContext())) {
            imageView.animate().cancel();
            imageView.animate().alpha(1f).setDuration(220L).setInterpolator(ENTRANCE_EASE).start();
        } else {
            imageView.setAlpha(1f);
        }
    }

    public static void registerDialog(Dialog dialog, View backdrop, View panel, float shiftSign, boolean customSelfManaged) {
        if (dialog == null || panel == null) return;
        synchronized (RECORDS) {
            RECORDS.put(dialog, new PopupRecord(backdrop, panel, shiftSign, customSelfManaged));
        }
    }

    private static PopupRecord getOrResolveRecord(Dialog dialog) {
        if (dialog == null) return null;
        synchronized (RECORDS) {
            PopupRecord existing = RECORDS.get(dialog);
            if (existing != null) return existing;
        }
        Window w = dialog.getWindow();
        if (w == null) return null;
        View content = w.findViewById(android.R.id.content);
        View panel = content;
        if (content instanceof ViewGroup && ((ViewGroup) content).getChildCount() > 0) {
            panel = ((ViewGroup) content).getChildAt(0);
        }
        if (panel == null) return null;
        PopupRecord fallback = new PopupRecord(null, panel, 1f, false);
        synchronized (RECORDS) {
            RECORDS.put(dialog, fallback);
        }
        return fallback;
    }

    public static boolean isClosing(Dialog dialog) {
        if (dialog == null) return false;
        synchronized (RECORDS) {
            PopupRecord rec = RECORDS.get(dialog);
            return rec != null && rec.closing;
        }
    }

    public static boolean shouldInterceptBack(Dialog dialog) {
        if (dialog == null || !dialog.isShowing()) return false;
        synchronized (RECORDS) {
            PopupRecord rec = RECORDS.get(dialog);
            if (rec != null && rec.customSelfManaged) return false;
        }
        if (ControlPanelHelper.ownsControlCenterDialog(dialog)) return false;
        if (FolderUi.ownsDialog(dialog)) return false;
        return true;
    }

    public static void onBackdropShow(final FrameLayout flat, final View shell, DialogInterface dialogInterface) {
        if (flat == null || shell == null) return;
        boolean animate = motionEnabled(shell.getContext());
        boolean customPanelAnim = hasMusicPopupChild(shell);
        float shiftSign = 1f;
        if (shell.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            int grav = ((FrameLayout.LayoutParams) shell.getLayoutParams()).gravity & Gravity.VERTICAL_GRAVITY_MASK;
            if (grav == Gravity.TOP) shiftSign = -1f;
        }
        if (dialogInterface instanceof Dialog) {
            registerDialog((Dialog) dialogInterface, flat, shell, shiftSign, customPanelAnim);
        }
        flat.setTag(null);

        if (!animate) {
            flat.animate().cancel();
            flat.setAlpha(1f);
            if (!customPanelAnim) {
                shell.animate().cancel();
                shell.setAlpha(1f);
                shell.setScaleX(1f);
                shell.setScaleY(1f);
                shell.setTranslationY(0f);
            }
            return;
        }

        float dp = shell.getResources().getDisplayMetrics().density;
        flat.animate().cancel();
        flat.setAlpha(0f);
        if (!customPanelAnim) {
            shell.animate().cancel();
            shell.setAlpha(0f);
            shell.setScaleX(0.956f);
            shell.setScaleY(0.956f);
            shell.setTranslationY(shiftSign * Math.round(11f * dp));
        }

        final long flatDuration = customPanelAnim ? 310L : 255L;
        flat.postOnAnimation(new Runnable() {
            @Override
            public void run() {
                if (!flat.isAttachedToWindow() || Boolean.TRUE.equals(flat.getTag())) return;
                flat.animate()
                    .alpha(1f)
                    .setDuration(flatDuration)
                    .setInterpolator(ENTRANCE_EASE)
                    .start();
                if (!customPanelAnim && shell.isAttachedToWindow()) {
                    shell.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationY(0f)
                        .setDuration(285L)
                        .setInterpolator(ENTRANCE_EASE)
                        .withLayer()
                        .start();
                }
            }
        });
    }

    public static void showWallpaperDialog(final Dialog dialog, final View root) {
        if (dialog == null || root == null) return;
        Context ctx = root.getContext();
        CrystalSoundEngine.attachDialog(ctx, dialog);
        View card = root;
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            if (vg.getChildCount() >= 2) {
                card = vg.getChildAt(vg.getChildCount() - 1);
            }
        }
        final View animCard = card;
        final View animBg = animCard != root ? root : null;
        registerDialog(dialog, animBg, animCard, 1f, false);
        root.setTag(null);

        if (!motionEnabled(ctx)) {
            root.setAlpha(1f);
            animCard.setAlpha(1f);
            animCard.setScaleX(1f);
            animCard.setScaleY(1f);
            animCard.setTranslationY(0f);
            dialog.show();
            return;
        }

        float dp = root.getResources().getDisplayMetrics().density;
        root.setAlpha(0f);
        if (animCard != root) {
            animCard.setAlpha(0f);
            animCard.setScaleX(0.956f);
            animCard.setScaleY(0.956f);
            animCard.setTranslationY(Math.round(11f * dp));
        } else {
            root.setScaleX(0.962f);
            root.setScaleY(0.962f);
            root.setTranslationY(Math.round(10f * dp));
        }

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                root.postOnAnimation(new Runnable() {
                    @Override
                    public void run() {
                        if (!root.isAttachedToWindow() || Boolean.TRUE.equals(root.getTag())) return;
                        if (animCard != root) {
                            root.animate()
                                .alpha(1f)
                                .setDuration(255L)
                                .setInterpolator(ENTRANCE_EASE)
                                .start();
                            animCard.animate()
                                .alpha(1f)
                                .scaleX(1f)
                                .scaleY(1f)
                                .translationY(0f)
                                .setDuration(285L)
                                .setInterpolator(ENTRANCE_EASE)
                                .withLayer()
                                .start();
                        } else {
                            root.animate()
                                .alpha(1f)
                                .scaleX(1f)
                                .scaleY(1f)
                                .translationY(0f)
                                .setDuration(280L)
                                .setInterpolator(ENTRANCE_EASE)
                                .withLayer()
                                .start();
                        }
                    }
                });
            }
        });
        dialog.show();
    }

    public static void showPlainDialog(final Activity activity, final Dialog dialog) {
        if (dialog == null) return;
        if (activity != null) {
            CrystalSoundEngine.attachDialog(activity, dialog);
        }
        Window w = dialog.getWindow();
        View content = w == null ? null : w.findViewById(android.R.id.content);
        final View card = (content instanceof ViewGroup && ((ViewGroup) content).getChildCount() > 0)
            ? ((ViewGroup) content).getChildAt(0) : content;
        if (card != null) {
            registerDialog(dialog, null, card, 1f, false);
            if (motionEnabled(card.getContext())) {
                float dp = card.getResources().getDisplayMetrics().density;
                card.setAlpha(0f);
                card.setScaleX(0.956f);
                card.setScaleY(0.956f);
                card.setTranslationY(Math.round(10f * dp));
                dialog.setOnShowListener(new DialogInterface.OnShowListener() {
                    @Override
                    public void onShow(DialogInterface d) {
                        card.postOnAnimation(new Runnable() {
                            @Override
                            public void run() {
                                if (!card.isAttachedToWindow() || isClosing(dialog)) return;
                                card.animate()
                                    .alpha(1f)
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .translationY(0f)
                                    .setDuration(275L)
                                    .setInterpolator(ENTRANCE_EASE)
                                    .withLayer()
                                    .start();
                            }
                        });
                    }
                });
            }
        }
        dialog.show();
    }

    public static void dismissForAction(Dialog dialog, Object afterRunnable) {
        if (dialog == null || !dialog.isShowing()) return;
        if (afterRunnable != null) {
            dismissImmediate(dialog, false);
        } else {
            CrystalSoundEngine.playClose(dialog.getContext());
            dismissAnimated(dialog, false);
        }
    }

    public static void dismissImmediate(Dialog dialog, boolean asCancel) {
        if (dialog == null) return;
        PopupRecord rec;
        synchronized (RECORDS) {
            rec = RECORDS.remove(dialog);
        }
        if (rec != null) {
            rec.closing = false;
            View bg = rec.backdropRef.get();
            View pn = rec.panelRef.get();
            if (bg != null) bg.animate().cancel();
            if (pn != null) pn.animate().cancel();
        }
        try {
            if (dialog.isShowing()) {
                if (asCancel) dialog.cancel();
                else dialog.dismiss();
            }
        } catch (Throwable ignored) {}
    }

    public static boolean dismissAnimated(final Dialog dialog, final boolean asCancel) {
        if (dialog == null || !dialog.isShowing()) return false;
        final PopupRecord rec = getOrResolveRecord(dialog);
        if (rec == null) {
            dismissImmediate(dialog, asCancel);
            return true;
        }
        synchronized (RECORDS) {
            if (rec.closing) return true;
        }
        final View panel = rec.panelRef.get();
        final View backdrop = rec.backdropRef.get();
        Context ctx = panel != null ? panel.getContext() : dialog.getContext();
        if (panel == null || !panel.isAttachedToWindow() || !motionEnabled(ctx) || isActivityInactive(ctx)) {
            dismissImmediate(dialog, asCancel);
            return true;
        }
        synchronized (RECORDS) {
            rec.closing = true;
        }

        try {
            InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && panel.getWindowToken() != null) {
                imm.hideSoftInputFromWindow(panel.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {}

        float dp = panel.getResources().getDisplayMetrics().density;
        if (backdrop != null && backdrop != panel) {
            backdrop.setTag(Boolean.TRUE);
            backdrop.animate().cancel();
            backdrop.animate()
                .alpha(0f)
                .setDuration(200L)
                .setInterpolator(EXIT_EASE)
                .start();
        }

        panel.animate().cancel();
        panel.animate()
            .alpha(0f)
            .scaleX(0.964f)
            .scaleY(0.964f)
            .translationY(rec.shiftSign * Math.round(8f * dp))
            .setDuration(205L)
            .setInterpolator(EXIT_EASE)
            .withLayer()
            .withEndAction(new Runnable() {
                @Override
                public void run() {
                    finishDialogDismiss(dialog, rec, asCancel);
                }
            })
            .start();

        Handler mh = mainHandler();
        if (mh != null) {
            mh.postDelayed(new Runnable() {
                @Override
                public void run() {
                    finishDialogDismiss(dialog, rec, asCancel);
                }
            }, 260L);
        }
        return true;
    }

    private static void finishDialogDismiss(Dialog dialog, PopupRecord rec, boolean asCancel) {
        synchronized (RECORDS) {
            if (rec != null) rec.closing = false;
            RECORDS.remove(dialog);
        }
        try {
            if (dialog != null && dialog.isShowing()) {
                if (asCancel) dialog.cancel();
                else dialog.dismiss();
            }
        } catch (Throwable ignored) {}
    }

    public static void animatePopupWindowIn(final View panel, boolean reducedMotion) {
        if (panel == null) return;
        Context ctx = panel.getContext();
        if (reducedMotion || !motionEnabled(ctx)) {
            panel.animate().cancel();
            panel.setAlpha(1f);
            panel.setScaleX(1f);
            panel.setScaleY(1f);
            panel.setTranslationY(0f);
            return;
        }
        float dp = panel.getResources().getDisplayMetrics().density;
        panel.animate().cancel();
        panel.setAlpha(0f);
        panel.setScaleX(0.962f);
        panel.setScaleY(0.962f);
        panel.setTranslationY(Math.round(10f * dp));
        panel.postOnAnimation(new Runnable() {
            @Override
            public void run() {
                if (!panel.isAttachedToWindow()) return;
                panel.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(260L)
                    .setInterpolator(ENTRANCE_EASE)
                    .withLayer()
                    .start();
            }
        });
    }

    public static void dismissPopupImmediate(PopupWindow popup) {
        if (popup == null) return;
        if (popup instanceof SmoothPopupWindow) {
            ((SmoothPopupWindow) popup).dismissImmediate();
        } else {
            try {
                if (popup.isShowing()) popup.dismiss();
            } catch (Throwable ignored) {}
        }
    }

    public static final class SmoothPopupWindow extends PopupWindow {
        private boolean bypassAnim;
        private boolean closing;

        public SmoothPopupWindow(View contentView, int width, int height, boolean focusable) {
            super(contentView, width, height, focusable);
        }

        public void dismissImmediate() {
            bypassAnim = true;
            closing = false;
            View content = getContentView();
            if (content != null) content.animate().cancel();
            try {
                if (isShowing()) super.dismiss();
            } catch (Throwable ignored) {}
        }

        private void finishSuperDismiss() {
            bypassAnim = false;
            closing = false;
            try {
                if (isShowing()) super.dismiss();
            } catch (Throwable ignored) {}
        }

        @Override
        public void dismiss() {
            if (bypassAnim || !isShowing()) {
                finishSuperDismiss();
                return;
            }
            if (closing) return;
            View content = getContentView();
            Context ctx = content == null ? null : content.getContext();
            if (content == null || !content.isAttachedToWindow() || !motionEnabled(ctx)
                    || AppLibraryBridge.launchedFromLibrary || isActivityInactive(ctx)) {
                finishSuperDismiss();
                return;
            }
            closing = true;
            CrystalSoundEngine.playClose(ctx);
            float dp = content.getResources().getDisplayMetrics().density;
            content.animate().cancel();
            content.animate()
                .alpha(0f)
                .scaleX(0.964f)
                .scaleY(0.964f)
                .translationY(Math.round(8f * dp))
                .setDuration(195L)
                .setInterpolator(EXIT_EASE)
                .withLayer()
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        finishSuperDismiss();
                    }
                })
                .start();
            Handler mh = mainHandler();
            if (mh != null) {
                mh.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (closing) finishSuperDismiss();
                    }
                }, 245L);
            }
        }
    }

    private static boolean hasMusicPopupChild(View shell) {
        if ("music_popup".equals(shell.getTag())) return true;
        if (shell instanceof FrameLayout) {
            FrameLayout group = (FrameLayout) shell;
            for (int i = 0; i < group.getChildCount(); i++) {
                if ("music_popup".equals(group.getChildAt(i).getTag())) return true;
            }
        }
        return false;
    }

    public static void trimMemory(int level) {
        if (level >= 20 && sWorker != null) {
            sWorker.removeCallbacksAndMessages(null);
        }
    }
}
