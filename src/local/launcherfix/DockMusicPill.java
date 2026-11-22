package local.launcherfix;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.gathin.tvhome.ClearGlass;
import com.gathin.tvhome.MainActivity;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * Dynamic Dock media pill: compact 24x22dp music glyph when idle, expanding cleanly
 * to show circular mini cover art + track title + 60fps Liquid Glass visualizer while music is active.
 * Polls only while the Home window holds focus and Ambient mode is inactive.
 */
final class DockMusicPill extends LinearLayout {
    static final class Snapshot {
        final SpotifyMetadata.Response response;
        final boolean connected;
        final WallpaperTheme.Tone tone;
        Snapshot(SpotifyMetadata.Response response, boolean connected, WallpaperTheme.Tone tone) {
            this.response = response;
            this.connected = connected;
            this.tone = tone;
        }
    }

    private static final long PREFETCH_DEBOUNCE_MS = 2000L;
    private static final WeakHashMap<Activity, WeakReference<DockMusicPill>> HOSTS = new WeakHashMap<>();

    static Snapshot latestSnapshot(Activity owner) {
        if (owner == null) return null;
        WeakReference<DockMusicPill> ref = HOSTS.get(owner);
        DockMusicPill pill = ref == null ? null : ref.get();
        return pill == null || pill.disposed ? null : pill.snapshot;
    }

    static View create(Activity owner, float dp, Typeface font, boolean vi, Drawable noteGlyph, Runnable onOpen) {
        WeakReference<DockMusicPill> prevRef = HOSTS.remove(owner);
        if (prevRef != null && prevRef.get() != null) prevRef.get().dispose();
        DockMusicPill pill = new DockMusicPill(owner, dp, font, vi, noteGlyph, onOpen);
        HOSTS.put(owner, new WeakReference<>(pill));
        return pill;
    }

    static void onDestroy(Activity owner) {
        WeakReference<DockMusicPill> ref = HOSTS.remove(owner);
        if (ref != null && ref.get() != null) ref.get().dispose();
    }

    private final WeakReference<Activity> ownerRef;
    private final float dp;
    private final boolean vi;
    private final Drawable noteGlyph;
    private final ImageView coverView;
    private final TextView titleView;
    private final MusicVisualizerView eqView;
    private final SpotifyMetadata metadata;

    private boolean expanded;
    private boolean playing;
    private boolean disposed;
    private Bitmap lastBitmap;
    private volatile Snapshot snapshot;
    private String pendingPrefetchKey = "";
    private SpotifyMetadata.Response pendingPrefetchResponse;
    private final Runnable prefetchTask = new Runnable() {
        @Override
        public void run() {
            SpotifyMetadata.Response track = pendingPrefetchResponse;
            if (!shouldRun() || !playing || track == null) return;
            Activity owner = ownerRef.get();
            if (owner == null) return;
            LiveLyrics.prefetch(owner, track.title, track.artist, track.album,
                track.spotifyId, track.youtubeId, track.playback.duration);
        }
    };

    private DockMusicPill(Activity owner, float dp, Typeface font, boolean vi, Drawable noteGlyph, Runnable onOpen) {
        super(owner);
        this.ownerRef = new WeakReference<>(owner);
        this.dp = dp;
        this.vi = vi;
        this.noteGlyph = noteGlyph;

        setTag(MiniPlayer.TAG);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBaselineAligned(false);
        setFocusable(true);
        setClickable(true);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setContentDescription(vi ? "Điều khiển nhạc" : "Music controls");
        setBackground(ControlPanelHelper.makeDockItemHighlight(dp));
        ControlPanelHelper.attachDockFocusMotion(this, dp, 1.06f);
        setOnClickListener(v -> onOpen.run());

        LayoutParams selfLp = new LayoutParams(px(24f), px(22f));
        selfLp.setMarginStart(px(3f));
        setLayoutParams(selfLp);

        final int artSize = px(15f);
        coverView = new ImageView(owner);
        coverView.setImageDrawable(noteGlyph);
        coverView.setScaleType(ImageView.ScaleType.CENTER);
        coverView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        coverView.setClipToOutline(false);
        coverView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(coverView, new LayoutParams(px(16f), px(16f)));

        titleView = new TextView(owner);
        titleView.setTextSize(10f);
        titleView.setTextColor(0xf2ffffff);
        if (font != null) titleView.setTypeface(font);
        titleView.setSingleLine(true);
        titleView.setIncludeFontPadding(false);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setMaxWidth(px(110f));
        titleView.setVisibility(GONE);
        ClearGlass.shadow(titleView, dp);
        LayoutParams titleLp = new LayoutParams(-2, -2);
        titleLp.setMarginStart(px(5f));
        addView(titleView, titleLp);

        eqView = new MusicVisualizerView(owner, dp, MusicVisualizerView.MODE_DOCK);
        eqView.setTag("dock_music_visualizer");
        eqView.setVisibility(GONE);
        LayoutParams eqLp = new LayoutParams(px(16f), px(13f));
        eqLp.setMarginStart(px(5.5f));
        addView(eqView, eqLp);

        applyCompactLayout();

        final int maxThumbPx = Math.max(64, Math.min(128, artSize * 3));
        metadata = new SpotifyMetadata(owner, (response, connected) -> {
            if (disposed) return;
            boolean hasTrack = connected && response != null && !response.title.isEmpty();
            if (hasTrack && !response.source.isEmpty() && response.playback.playing()) {
                RecentApps.record(owner, response.source);
            }
            WallpaperTheme.Tone tone = hasTrack && response.art != null && !response.art.isRecycled()
                ? WallpaperTheme.sample(response.art) : null;
            snapshot = connected && response != null ? new Snapshot(response, true, tone) : null;
            String seed = hasTrack ? (response.revision.isEmpty() ? response.title : response.revision) : "";
            updateState(hasTrack, hasTrack ? response.title : "", hasTrack ? response.art : null,
                hasTrack && response.playback.playing(), tone, seed, artSize);
            updatePrefetch(hasTrack ? response : null);
        }, 4000L, maxThumbPx);
    }

    private void applyCompactLayout() {
        expanded = false;
        setPadding(0, 0, 0, 0);
        setGravity(Gravity.CENTER);
        coverView.setClipToOutline(false);
        coverView.setScaleType(ImageView.ScaleType.CENTER);
        coverView.setImageDrawable(noteGlyph);
        coverView.getLayoutParams().width = px(16f);
        coverView.getLayoutParams().height = px(16f);
        titleView.setVisibility(GONE);
        eqView.setVisibility(GONE);
        eqView.setState(false, false);
        if (getLayoutParams() != null) {
            getLayoutParams().width = px(24f);
            getLayoutParams().height = px(22f);
        }
    }

    private void updateState(boolean active, String trackTitle, Bitmap art, boolean isPlaying,
                             WallpaperTheme.Tone tone, String trackSeed, int artSize) {
        playing = isPlaying;
        if (!active) {
            lastBitmap = null;
            if (expanded) {
                applyCompactLayout();
                setContentDescription(vi ? "Điều khiển nhạc" : "Music controls");
                requestLayout();
                invalidateDock();
            }
            return;
        }

        boolean layoutChanged = false;
        if (!expanded) {
            expanded = true;
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(px(5f), 0, px(6f), 0);
            coverView.getLayoutParams().width = artSize;
            coverView.getLayoutParams().height = artSize;
            titleView.setVisibility(VISIBLE);
            eqView.setVisibility(VISIBLE);
            if (getLayoutParams() != null) {
                getLayoutParams().width = LayoutParams.WRAP_CONTENT;
                getLayoutParams().height = px(22f);
            }
            layoutChanged = true;
        }

        if (!TextUtils.equals(titleView.getText(), trackTitle)) {
            titleView.setText(trackTitle);
            setContentDescription((vi ? "Đang phát: " : "Now playing: ") + trackTitle);
            layoutChanged = true;
        }

        if (art != lastBitmap) {
            lastBitmap = art;
            if (art != null && !art.isRecycled()) {
                coverView.setClipToOutline(true);
                coverView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                coverView.setImageBitmap(art);
            } else {
                coverView.setClipToOutline(false);
                coverView.setScaleType(ImageView.ScaleType.CENTER);
                coverView.setImageDrawable(noteGlyph);
            }
        }

        eqView.setTone(tone);
        eqView.setTrackSeed(trackSeed);
        eqView.setState(isPlaying, canAnimateEq());
        if (layoutChanged) {
            requestLayout();
            invalidateDock();
        }
    }

    private void invalidateDock() {
        View root = getRootView();
        if (root != null) {
            View pill = root.findViewWithTag(ControlPanelHelper.TAG_CLOCK_PILL);
            if (pill != null) {
                pill.requestLayout();
                pill.invalidate();
            }
        }
    }

    private boolean shouldRun() {
        Activity a = ownerRef.get();
        return !disposed && a != null && !a.isFinishing() && !a.isDestroyed()
            && isAttachedToWindow() && hasWindowFocus() && getVisibility() == VISIBLE
            && !CrystalSoundEngine.isAmbientActive();
    }

    private boolean canAnimateEq() {
        Activity a = ownerRef.get();
        boolean motion = ValueAnimator.areAnimatorsEnabled()
            && (!(a instanceof MainActivity) || (((MainActivity) a).animations && !((MainActivity) a).reducedMotion));
        return shouldRun() && expanded && motion;
    }

    private void updatePrefetch(SpotifyMetadata.Response response) {
        if (!shouldRun() || response == null || !response.playback.playing()) {
            cancelPrefetch();
            return;
        }
        String key = LiveLyrics.buildKey(response.title, response.artist, response.album,
            response.spotifyId, response.youtubeId, response.playback.duration);
        if (key.isEmpty()) {
            cancelPrefetch();
            return;
        }
        if (LiveLyrics.hasFreshCache(key)) {
            removeCallbacks(prefetchTask);
            pendingPrefetchKey = key;
            pendingPrefetchResponse = response;
            return;
        }
        if (key.equals(pendingPrefetchKey)) return;
        removeCallbacks(prefetchTask);
        LiveLyrics.cancelPrefetch();
        pendingPrefetchKey = key;
        pendingPrefetchResponse = response;
        postDelayed(prefetchTask, PREFETCH_DEBOUNCE_MS);
    }

    private void cancelPrefetch() {
        removeCallbacks(prefetchTask);
        pendingPrefetchKey = "";
        pendingPrefetchResponse = null;
        LiveLyrics.cancelPrefetch();
    }

    private void reconcile() {
        if (shouldRun()) {
            metadata.start();
            eqView.setState(playing, canAnimateEq());
            Snapshot current = snapshot;
            if (current != null && current.connected) updatePrefetch(current.response);
        } else {
            cancelPrefetch();
            metadata.stop();
            eqView.setState(playing, false);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        reconcile();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelPrefetch();
        metadata.stop();
        eqView.setState(false, false);
        super.onDetachedFromWindow();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        reconcile();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        reconcile();
    }

    void dispose() {
        if (disposed) return;
        disposed = true;
        cancelPrefetch();
        eqView.setState(false, false);
        metadata.close();
        lastBitmap = null;
        snapshot = null;
    }

    private int px(float v) { return Math.round(v * dp); }
}
