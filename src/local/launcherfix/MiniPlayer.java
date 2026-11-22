package local.launcherfix;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.animation.Interpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.gathin.tvhome.Backdrop;
import com.gathin.tvhome.MainActivity;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Large borderless floating music card with full-bleed left artwork dissolving into an
 * atmospheric color-matched backdrop, word-aligned ruby romaji on all visible lines,
 * real word-timed karaoke, and an auto-resting minimal Liquid Glass HUD.
 */
public final class MiniPlayer {
    public static final String TAG = "dock_mini_player";
    private static final long HUD_IDLE_MS = 5000L;
    private static final Interpolator ENTRANCE_EASE = new PathInterpolator(0.20f, 0.92f, 0.28f, 1.0f);
    private static final Interpolator EXIT_EASE = new PathInterpolator(0.32f, 0.0f, 0.60f, 1.0f);
    private static final Interpolator HUD_WAKE_EASE = new PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f);
    private static final Interpolator HUD_REST_EASE = new PathInterpolator(0.33f, 0.0f, 0.20f, 1.0f);
    private static WeakReference<Session> active = new WeakReference<>(null);

    private MiniPlayer() {}

    public static View create(Activity owner, View dock, float dp, Typeface font, boolean vi) {
        final View[] ref = new View[1];
        View pill = DockMusicPill.create(owner, dp, font, vi,
            new Glyph(Math.round(12 * dp), 0), () -> show(owner, ref[0], vi));
        ref[0] = pill;
        return pill;
    }

    private static void show(Activity owner, View origin, boolean vi) {
        if (owner.isFinishing() || owner.isDestroyed() || CrystalSoundEngine.isAmbientActive()) return;
        Session previous = active.get();
        if (previous != null && previous.dialog.isShowing()) {
            if (!previous.closing) return;
            previous.finishDismiss();
        }
        Session session = new Session(owner, origin, vi);
        active = new WeakReference<>(session);
        session.show();
    }

    public static boolean dismiss(Activity owner) { return dismiss(owner, true); }
    public static boolean dismissFromBack(Activity owner) {
        Session session = active.get();
        if (session == null || session.owner != owner || session.closed) return false;
        CrystalSoundEngine.playClose(owner);
        session.dismissAnimated();
        return true;
    }

    private static boolean dismiss(Activity owner, boolean lifecycle) {
        Session session = active.get();
        if (session == null || session.owner != owner) return false;
        session.lifecycleClose = lifecycle;
        session.finishDismiss();
        return true;
    }

    static final class ConfirmPress {
        int key = -1;
        long down;
        void reset() { key = -1; }
        boolean accept(int code, int action, long time, int repeat, boolean canceled) {
            if (action == KeyEvent.ACTION_DOWN && repeat == 0) { key = code; down = time; }
            if (action != KeyEvent.ACTION_UP) return false;
            boolean accepted = key == code && down == time && !canceled;
            reset();
            return accepted;
        }
    }

    private static final class Session {
        final Activity owner;
        final Dialog dialog;
        final FrameLayout panel;
        final MusicBackdropView backdrop;
        final LinearLayout trackHeader;
        final MusicVisualizerView visualizer;
        final LinearLayout controlsHud;
        final LinearLayout creditRow;
        final View previousBtn, play, nextBtn;
        final TextView title, subtitle, sourceButton, lyricFind, openAppButton;
        final TextView elapsed, total;
        final Timeline timeline;
        final RubyLyricsView rubyStage;
        final ScrollView plainScroll;
        final RubyLyricsView.Plain plainView;
        final TextView lyricCredit, lyricOffsetButton, lyricAccess, spotifyAccess;

        final SpotifyMetadata metadata;
        final LiveLyrics lyrics;
        final AppLiveLyrics appLiveLyrics;
        final ArrayList<View> mediaButtons = new ArrayList<>(3);
        final ArrayList<ConfirmPress> presses = new ArrayList<>(3);
        final Handler clock = new Handler(Looper.getMainLooper());
        final Runnable clockTick = this::renderPlayback;
        final Runnable restHudTask = this::restHud;
        final WeakReference<View> origin;
        final float dp;
        final boolean vi;

        WallpaperTheme.Tone baseTone;
        WallpaperTheme.Tone activeTone;
        SpotifyMetadata.Response currentMedia;
        Dialog sourceDialog, lyricPicker, accessDialog, offsetDialog;
        LyricCandidates candidateSearch;
        String lyricPickerKey = "";
        String currentSpotifyId = "";
        String offsetTrackKey = "", offsetTitle = "", offsetArtist = "";
        long offsetDuration;
        long lyricOffsetMs;
        Lyrics lyricData;
        Lyrics appLiveData;
        int appLiveIndex = -1;
        boolean usingAppLive;
        String lyricStatus = "waiting";
        int lyricIndex = Integer.MIN_VALUE;
        MusicPlayback playback = MusicPlayback.UNKNOWN;
        boolean commandPending;
        boolean motionReported;
        boolean focusedWindow;
        boolean entranceActive;
        boolean hudAwake = true;
        int absorbedWakeKey = -1;
        int playGlyph = 1;
        boolean closing, closed, lifecycleClose;

        Session(Activity owner, View anchor, boolean vi) {
            this.owner = owner;
            this.vi = vi;
            dp = owner.getResources().getDisplayMetrics().density;
            origin = new WeakReference<>(anchor);
            baseTone = WallpaperTheme.sample(owner instanceof MainActivity
                && ((MainActivity) owner).wallpaperPlayer != null ? ((MainActivity) owner).wallpaperPlayer.current : null);
            activeTone = baseTone;

            dialog = new Dialog(owner) {
                @Override
                public void onWindowFocusChanged(boolean focused) {
                    super.onWindowFocusChanged(focused);
                    if (!focused) {
                        resetPresses();
                        resetLyricMotion();
                        clock.removeCallbacks(restHudTask);
                    }
                    boolean live = !closed && !closing;
                    if (metadata != null && live) { if (focused) metadata.start(); else metadata.stop(); }
                    if (lyrics != null && live) { if (focused) lyrics.start(); else lyrics.stop(); }
                    focusedWindow = focused;
                    clock.removeCallbacks(clockTick);
                    if (rubyStage != null) rubyStage.removeCallbacks(clockTick);
                    syncVisualizerState();
                    if (timeline != null && live && focused) {
                        renderPlayback();
                        if (hudAwake) scheduleHudRest();
                    }
                }

                @Override
                public void onBackPressed() {
                    dismissAnimated();
                }

                @Override
                public boolean dispatchKeyEvent(KeyEvent event) {
                    if (closing || closed) return true;
                    int action = event.getAction();
                    int code = event.getKeyCode();
                    if (action == KeyEvent.ACTION_DOWN) owner.onUserInteraction();

                    if (absorbedWakeKey != -1) {
                        if (code == absorbedWakeKey && action == KeyEvent.ACTION_UP) {
                            absorbedWakeKey = -1;
                            return true;
                        }
                        if (action == KeyEvent.ACTION_DOWN) absorbedWakeKey = -1;
                    }

                    if (isVolumeKey(code)) {
                        return super.dispatchKeyEvent(event);
                    }

                    if (!hudAwake) {
                        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                            return super.dispatchKeyEvent(event);
                        }
                        if (isDirectionalDpad(code)) {
                            if (action == KeyEvent.ACTION_DOWN) {
                                absorbedWakeKey = code;
                                wakeHud(true);
                                View focused = getCurrentFocus();
                                if (focused == null || !focused.isShown()) play.requestFocus();
                            }
                            return true;
                        }
                        if (action == KeyEvent.ACTION_DOWN) {
                            wakeHud(true);
                        }
                    } else if (action == KeyEvent.ACTION_DOWN) {
                        scheduleHudRest();
                    }
                    return super.dispatchKeyEvent(event);
                }
            };
            dialog.setOwnerActivity(owner);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            final int cardRadius = px(26);
            panel = new FrameLayout(owner) {
                @Override
                protected void onMeasure(int widthSpec, int heightSpec) {
                    int availW = MeasureSpec.getSize(widthSpec);
                    int availH = MeasureSpec.getSize(heightSpec);
                    int targetW = px(816);
                    int targetH = px(458);
                    if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED && availW > 0) {
                        targetW = Math.min(targetW, Math.max(px(560), availW - px(48)));
                    }
                    if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED && availH > 0) {
                        targetH = Math.min(targetH, Math.max(px(340), availH - px(36)));
                    }
                    super.onMeasure(
                        MeasureSpec.makeMeasureSpec(targetW, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(targetH, MeasureSpec.EXACTLY)
                    );
                }
            };
            panel.setTag("music_popup");
            panel.setClipChildren(true);
            panel.setClipToPadding(true);
            panel.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cardRadius);
                }
            });
            panel.setClipToOutline(true);

            // 1. Seamless full-bleed left artwork + blurred atmospheric right backdrop.
            backdrop = new MusicBackdropView(owner, dp, baseTone);
            panel.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));

            // 2. Bottom-left track identity + sleek borderless Liquid Glass controls.
            LinearLayout leftBottom = new LinearLayout(owner);
            leftBottom.setOrientation(LinearLayout.VERTICAL);
            leftBottom.setGravity(Gravity.BOTTOM | Gravity.START);
            leftBottom.setPadding(px(34), px(20), px(16), px(24));
            leftBottom.setClipChildren(false);
            leftBottom.setClipToPadding(false);
            FrameLayout.LayoutParams leftLp = new FrameLayout.LayoutParams(px(438), -1, Gravity.START | Gravity.BOTTOM);
            panel.addView(leftBottom, leftLp);

            trackHeader = new LinearLayout(owner);
            trackHeader.setOrientation(LinearLayout.VERTICAL);
            trackHeader.setGravity(Gravity.START);
            title = text(vi ? "Nhạc" : "Music", 23, Color.WHITE);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setGravity(Gravity.START);
            title.setSingleLine(false);
            title.setMaxLines(2);
            title.setLineSpacing(px(2), 1f);
            title.setShadowLayer(px(6), 0f, px(2), 0x8a000000);
            trackHeader.addView(title, new LinearLayout.LayoutParams(-1, -2));

            subtitle = text(vi ? "Điều khiển ứng dụng đang phát" : "Control your media app", 14, 0xdcffffff);
            subtitle.setGravity(Gravity.START);
            subtitle.setShadowLayer(px(5), 0f, px(1.5f), 0x80000000);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
            subLp.topMargin = px(4);
            trackHeader.addView(subtitle, subLp);

            visualizer = new MusicVisualizerView(owner, dp, MusicVisualizerView.MODE_STAGE);
            visualizer.setTag("music_visualizer");
            visualizer.setVisibility(View.GONE);
            LinearLayout.LayoutParams visLp = new LinearLayout.LayoutParams(-1, px(28));
            visLp.topMargin = px(10);
            trackHeader.addView(visualizer, visLp);
            leftBottom.addView(trackHeader, new LinearLayout.LayoutParams(-1, -2));

            controlsHud = new LinearLayout(owner);
            controlsHud.setOrientation(LinearLayout.VERTICAL);
            controlsHud.setGravity(Gravity.START);
            LinearLayout.LayoutParams hudLp = new LinearLayout.LayoutParams(-1, -2);
            hudLp.topMargin = px(12);
            leftBottom.addView(controlsHud, hudLp);

            LinearLayout progressRow = new LinearLayout(owner);
            progressRow.setOrientation(LinearLayout.HORIZONTAL);
            progressRow.setGravity(Gravity.CENTER_VERTICAL);
            elapsed = text("--:--", 10, 0xc8ffffff);
            elapsed.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            elapsed.setFontFeatureSettings("tnum");
            elapsed.setShadowLayer(px(3), 0f, px(1f), 0x66000000);
            timeline = new Timeline(owner, dp);
            timeline.setId(View.generateViewId());
            timeline.setTag("music_timeline");
            timeline.setFocusable(false);
            timeline.setContentDescription(vi ? "Thanh tiến trình; dùng trái phải để tua 10 giây" : "Playback timeline; use left and right to seek 10 seconds");
            timeline.setOnFocusChangeListener((v, f) -> v.invalidate());
            timeline.setOnKeyListener((v, code, event) -> {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        sendSelected(code == KeyEvent.KEYCODE_DPAD_RIGHT
                            ? KeyEvent.KEYCODE_MEDIA_FAST_FORWARD : KeyEvent.KEYCODE_MEDIA_REWIND);
                    }
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                        || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A) {
                    if (event.getAction() == KeyEvent.ACTION_UP && event.getRepeatCount() == 0 && !event.isCanceled()) {
                        sendSelected(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                    }
                    return true;
                }
                return false;
            });
            total = text("--:--", 10, 0xc8ffffff);
            total.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            total.setFontFeatureSettings("tnum");
            total.setShadowLayer(px(3), 0f, px(1f), 0x66000000);
            progressRow.addView(elapsed, new LinearLayout.LayoutParams(-2, px(16)));
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(0, px(16), 1f);
            barLp.setMarginStart(px(8));
            barLp.setMarginEnd(px(8));
            progressRow.addView(timeline, barLp);
            progressRow.addView(total, new LinearLayout.LayoutParams(-2, px(16)));
            controlsHud.addView(progressRow, new LinearLayout.LayoutParams(-1, px(20)));

            LinearLayout actionRow = new LinearLayout(owner);
            actionRow.setOrientation(LinearLayout.HORIZONTAL);
            actionRow.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            actionRow.setBaselineAligned(false);
            previousBtn = button(2, KeyEvent.KEYCODE_MEDIA_PREVIOUS, vi ? "Bài trước" : "Previous track", false);
            play = button(1, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, vi ? "Phát / tạm dừng" : "Play / pause", true);
            nextBtn = button(3, KeyEvent.KEYCODE_MEDIA_NEXT, vi ? "Bài sau" : "Next track", false);
            actionRow.addView(previousBtn);
            actionRow.addView(play);
            actionRow.addView(nextBtn);

            sourceButton = pillButton(vi ? "Nguồn ▾" : "Source ▾", "music_source",
                vi ? "Chọn nguồn phát nhạc" : "Choose media source", this::chooseSource);
            LinearLayout.LayoutParams srcLp = new LinearLayout.LayoutParams(-2, px(30));
            srcLp.setMarginStart(px(8));
            sourceButton.setMaxWidth(px(108));
            actionRow.addView(sourceButton, srcLp);

            lyricFind = pillButton(vi ? "Đổi lời" : "Lyrics", "music_lyrics_find",
                vi ? "Tìm và chọn bản lời khác" : "Find and choose other lyrics", this::chooseLyrics);
            LinearLayout.LayoutParams findLp = new LinearLayout.LayoutParams(-2, px(30));
            findLp.setMarginStart(px(5));
            actionRow.addView(lyricFind, findLp);

            openAppButton = pillButton(vi ? "↗ Mở app" : "↗ Open", "music_open_app",
                vi ? "Mở ứng dụng nhạc đang phát" : "Open active music app", this::openMusicApp);
            openAppButton.setVisibility(View.GONE);
            LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(-2, px(30));
            openLp.setMarginStart(px(5));
            actionRow.addView(openAppButton, openLp);

            LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(-1, px(44));
            actLp.topMargin = px(6);
            controlsHud.addView(actionRow, actLp);

            // 3. Right lyric stage (word-aligned ruby romaji + real word karaoke + smooth scroll).
            FrameLayout rightStage = new FrameLayout(owner);
            rightStage.setClipChildren(true);
            rightStage.setClipToPadding(false);
            FrameLayout.LayoutParams rightLp = new FrameLayout.LayoutParams(-1, -1);
            rightLp.setMarginStart(px(494));
            rightLp.setMarginEnd(px(24));
            panel.addView(rightStage, rightLp);

            rubyStage = new RubyLyricsView(owner, dp, vi);
            rightStage.addView(rubyStage, new FrameLayout.LayoutParams(-1, -1));

            plainScroll = new ScrollView(owner);
            plainScroll.setId(View.generateViewId());
            plainScroll.setTag("music_plain_lyrics");
            plainScroll.setFocusable(true);
            plainScroll.setContentDescription(vi ? "Lời chưa đồng bộ; dùng lên xuống để cuộn" : "Unsynced lyrics; use up and down to scroll");
            plainScroll.setVerticalScrollBarEnabled(false);
            plainScroll.setClipToPadding(false);
            plainScroll.setPadding(px(4), px(12), px(4), px(40));
            plainScroll.setVisibility(View.GONE);
            StateListDrawable plainFocus = new StateListDrawable();
            GradientDrawable plainFocusBg = new GradientDrawable();
            plainFocusBg.setColor(0x14ffffff);
            plainFocusBg.setCornerRadius(px(12));
            plainFocus.addState(new int[]{android.R.attr.state_focused}, plainFocusBg);
            plainFocus.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
            plainScroll.setBackground(plainFocus);
            plainView = new RubyLyricsView.Plain(owner, dp);
            plainScroll.addView(plainView, new ScrollView.LayoutParams(-1, -2));
            rightStage.addView(plainScroll, new FrameLayout.LayoutParams(-1, -1));

            creditRow = new LinearLayout(owner);
            creditRow.setOrientation(LinearLayout.HORIZONTAL);
            creditRow.setGravity(Gravity.CENTER_VERTICAL);
            lyricCredit = text("Better Lyrics · LyricsPlus · LrcRed · Unison · LRCLIB", 9, 0x66ffffff);
            lyricCredit.setId(View.generateViewId());
            lyricCredit.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            creditRow.addView(lyricCredit, new LinearLayout.LayoutParams(0, -1, 1f));

            lyricOffsetButton = pillButton(LyricOffsetStore.formatPill(0L, vi), "music_lyrics_offset",
                vi ? "Chỉnh độ trễ đồng bộ lời bài hát" : "Adjust lyric timing offset", this::adjustLyricOffset);
            lyricOffsetButton.setTextSize(9.5f);
            lyricOffsetButton.setPadding(px(9), 0, px(9), 0);
            lyricOffsetButton.setVisibility(View.GONE);
            LinearLayout.LayoutParams offLp = new LinearLayout.LayoutParams(-2, px(24));
            offLp.setMarginStart(px(6));
            creditRow.addView(lyricOffsetButton, offLp);

            lyricAccess = pillButton(vi ? "Nguồn +" : "Sources +", "music_lyrics_access",
                vi ? "Kết nối thêm nguồn lời" : "Connect more lyrics sources", this::connectSources);
            lyricAccess.setTextSize(9.5f);
            lyricAccess.setPadding(px(9), 0, px(9), 0);
            LinearLayout.LayoutParams accLp = new LinearLayout.LayoutParams(-2, px(24));
            accLp.setMarginStart(px(6));
            creditRow.addView(lyricAccess, accLp);
            lyricAccess.setVisibility(LyricsAccess.token(owner).isEmpty() ? View.VISIBLE : View.GONE);

            spotifyAccess = pillButton("Spotify +", "music_spotify_access",
                vi ? "Kết nối lời Spotify" : "Connect Spotify lyrics", this::connectSpotify);
            spotifyAccess.setTextSize(9.5f);
            spotifyAccess.setPadding(px(9), 0, px(9), 0);
            spotifyAccess.setVisibility(View.GONE);
            LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(-2, px(24));
            spLp.setMarginStart(px(6));
            creditRow.addView(spotifyAccess, spLp);

            FrameLayout.LayoutParams creditLp = new FrameLayout.LayoutParams(-1, px(26), Gravity.BOTTOM);
            creditLp.bottomMargin = px(10);
            rightStage.addView(creditRow, creditLp);

            applyControlTint(activeTone);
            wireFocus();

            appLiveLyrics = new AppLiveLyrics(vi, (data, idx, appended) -> {
                if (closed) return;
                appLiveData = data;
                appLiveIndex = idx;
                syncLyricsStage(appended);
            });
            LiveLyrics.cancelPrefetch();
            lyrics = new LiveLyrics(owner, (data, status) -> {
                if (closed) return;
                lyricData = data;
                lyricStatus = status;
                syncLyricsStage(false);
            });
            rubyStage.setLyrics(null, "waiting");

            Backdrop.wrap(owner, dialog, panel, true, 0);

            metadata = new SpotifyMetadata(owner, (response, connected) -> {
                if (closed || closing) return;
                applyMetadataResponse(response, connected, null, motionEnabled(), true);
            }, 2000L, 1024);

            DockMusicPill.Snapshot seeded = DockMusicPill.latestSnapshot(owner);
            if (seeded != null && seeded.response != null) {
                applyMetadataResponse(seeded.response, seeded.connected, seeded.tone, false, false);
            }
            dialog.setOnDismissListener(ignored -> close());
        }

        private void applyMetadataResponse(SpotifyMetadata.Response response, boolean connected,
                                           WallpaperTheme.Tone preSampledTone, boolean allowBackdropCrossfade,
                                           boolean fullResArtwork) {
            String name = response.title, artist = response.artist, album = response.album, spotifyId = response.spotifyId;
            android.graphics.Bitmap bitmap = response.art;
            MusicPlayback state = response.playback;

            title.setText(name.isEmpty() ? (vi ? "Nhạc" : "Music") : name);
            subtitle.setText(!connected ? (vi ? "Chưa kết nối thông tin nhạc" : "Music info disconnected")
                : name.isEmpty() ? (vi ? "Mở ứng dụng nhạc và phát một bài" : "Play a track in your music app")
                : artist.isEmpty() ? response.sourceLabel() : artist);
            // Dock artwork can seed the popup before its own transport session connects.
            currentMedia = connected && fullResArtwork ? response : null;

            String sourceLabel = response.sourceLabel();
            String shortSource = sourceLabel.isEmpty() ? (vi ? "Nguồn" : "Source") : sourceLabel;
            sourceButton.setText((metadataAutomatic() ? "" : "✓ ") + shortSource + " ▾");
            openAppButton.setVisibility(connected && !response.source.isEmpty() ? View.VISIBLE : View.GONE);
            if (connected && !response.source.isEmpty() && state.playing()) {
                RecentApps.record(owner, response.source);
            }

            spotifyAccess.setVisibility("com.spotify.tv.android".equals(response.source) ? View.VISIBLE : View.GONE);
            for (View control : mediaButtons) {
                int key = Integer.parseInt(control.getTag().toString().substring("music_control_".length()));
                control.setAlpha(currentMedia != null && MediaSources.supports(response.actions, key, state.playing()) ? 1f : .35f);
            }
            boolean canSeek = currentMedia != null && state.duration > 0
                && MediaSources.supports(response.actions, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, state.playing());
            if (!canSeek && timeline.hasFocus()) play.requestFocus();
            timeline.setFocusable(canSeek);

            WallpaperTheme.Tone cachedTone = connected ? MusicBackdropView.cachedToneFor(response.revision) : null;
            WallpaperTheme.Tone nextTone = cachedTone != null ? cachedTone
                : preSampledTone != null ? preSampledTone
                : (bitmap != null && !bitmap.isRecycled() ? WallpaperTheme.sample(bitmap) : baseTone);
            if (nextTone != activeTone) {
                activeTone = nextTone;
                applyControlTint(activeTone);
            }
            backdrop.setArtwork(connected ? response.revision : "", connected ? bitmap : null,
                activeTone, allowBackdropCrossfade, fullResArtwork);
            boolean hasTrack = connected && !name.isEmpty();
            visualizer.setVisibility(hasTrack ? View.VISIBLE : View.GONE);
            visualizer.setTrackSeed(hasTrack ? (response.revision.isEmpty() ? name + "|" + artist : response.revision) : "");

            playback = connected ? state : MusicPlayback.UNKNOWN;
            currentSpotifyId = connected ? spotifyId : "";
            if (!playback.playing()) resetLyricMotion();
            String nextOffsetKey = connected ? LyricChoiceStore.songKey(name, artist, playback.duration) : "";
            if (!nextOffsetKey.equals(offsetTrackKey)) {
                offsetTrackKey = nextOffsetKey;
                offsetTitle = connected ? name : "";
                offsetArtist = connected ? artist : "";
                offsetDuration = connected ? playback.duration : 0L;
                lyricOffsetMs = connected ? LyricOffsetStore.get(owner, name, artist, playback.duration) : 0L;
                lyricOffsetButton.setText(LyricOffsetStore.formatPill(lyricOffsetMs, vi));
                if (offsetDialog != null && offsetDialog.isShowing()) offsetDialog.dismiss();
            }
            appLiveLyrics.update(connected, response.source, sourceLabel, name, artist,
                response.youtubeId, playback.duration, response.liveLyric);
            lyrics.setTrack(connected ? name : "", connected ? artist : "", connected ? album : "",
                connected ? spotifyId : "", connected ? response.youtubeId : "", playback.duration);
            if (metadata != null) metadata.setFastPoll(showingAppLive() && playback.playing());
            if (lyricPicker != null && !lyricPickerKey.equals(lyrics.trackKey())) {
                Toast.makeText(owner, vi ? "Bài đã đổi, hãy tìm lại lời" : "Track changed; search again", Toast.LENGTH_SHORT).show();
                lyricPicker.dismiss();
            }
            wireFocus();
            renderPlayback();
        }

        private static boolean isDirectionalDpad(int code) {
            return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        }

        private static boolean isVolumeKey(int code) {
            return code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_CAPTIONS;
        }

        private boolean hasChildDialog() {
            return (sourceDialog != null && sourceDialog.isShowing())
                || (lyricPicker != null && lyricPicker.isShowing())
                || (accessDialog != null && accessDialog.isShowing())
                || (offsetDialog != null && offsetDialog.isShowing());
        }

        private void scheduleHudRest() {
            clock.removeCallbacks(restHudTask);
            if (!closed && focusedWindow) {
                clock.postDelayed(restHudTask, HUD_IDLE_MS);
            }
        }

        private void wakeHud(boolean animate) {
            clock.removeCallbacks(restHudTask);
            if (!hudAwake) {
                hudAwake = true;
                rubyStage.setHudAwake(true);
                trackHeader.animate().cancel();
                controlsHud.animate().cancel();
                creditRow.animate().cancel();
                if (animate && motionEnabled()) {
                    trackHeader.animate().setStartDelay(0L).translationY(0f).setDuration(240L).setInterpolator(HUD_WAKE_EASE).start();
                    controlsHud.setTranslationY(px(8));
                    controlsHud.animate().setStartDelay(65L).alpha(1f).translationY(0f).setDuration(220L).setInterpolator(HUD_WAKE_EASE).start();
                    creditRow.animate().setStartDelay(65L).alpha(1f).setDuration(220L).setInterpolator(HUD_WAKE_EASE).start();
                } else {
                    trackHeader.setTranslationY(0f);
                    controlsHud.setAlpha(1f);
                    controlsHud.setTranslationY(0f);
                    creditRow.setAlpha(1f);
                }
            }
            scheduleHudRest();
        }

        private void restHud() {
            clock.removeCallbacks(restHudTask);
            if (closed || closing || !focusedWindow || !hudAwake) return;
            if (hasChildDialog() || (plainScroll.getVisibility() == View.VISIBLE && plainScroll.hasFocus())) {
                scheduleHudRest();
                return;
            }
            hudAwake = false;
            rubyStage.setHudAwake(false);
            float drop = controlsHud.getHeight() > 0 ? controlsHud.getHeight() + px(12) : px(68);
            trackHeader.animate().cancel();
            controlsHud.animate().cancel();
            creditRow.animate().cancel();
            if (motionEnabled()) {
                controlsHud.animate().setStartDelay(0L).alpha(0f).translationY(px(8)).setDuration(260L).setInterpolator(HUD_REST_EASE).start();
                creditRow.animate().setStartDelay(0L).alpha(0f).setDuration(260L).setInterpolator(HUD_REST_EASE).start();
                trackHeader.animate().setStartDelay(70L).translationY(drop).setDuration(320L).setInterpolator(HUD_WAKE_EASE).start();
            } else {
                trackHeader.setTranslationY(drop);
                controlsHud.setAlpha(0f);
                controlsHud.setTranslationY(px(8));
                creditRow.setAlpha(0f);
            }
        }

        private void applyControlTint(WallpaperTheme.Tone tone) {
            for (int i = 0; i < mediaButtons.size(); i++) {
                View btn = mediaButtons.get(i);
                boolean primary = btn == play;
                int rad = px(primary ? 22 : 19);
                StateListDrawable bg = new StateListDrawable();
                Drawable pressed = WallpaperTheme.focusWash(rad, tone, true);
                pressed.setAlpha(196);
                Drawable focused = WallpaperTheme.focusWash(rad, tone, false);
                focused.setAlpha(220);
                GradientDrawable idle = new GradientDrawable();
                idle.setCornerRadius(rad);
                idle.setColor(primary ? 0x24ffffff : Color.TRANSPARENT);
                bg.addState(new int[]{android.R.attr.state_pressed}, pressed);
                bg.addState(new int[]{android.R.attr.state_focused}, focused);
                bg.addState(new int[]{}, idle);
                btn.setBackground(bg);
            }
            stylePill(sourceButton, px(15), tone, 0x20ffffff);
            stylePill(lyricFind, px(15), tone, 0x20ffffff);
            stylePill(openAppButton, px(15), tone, 0x20ffffff);
            stylePill(lyricOffsetButton, px(12), tone, 0x1affffff);
            stylePill(lyricAccess, px(12), tone, 0x1affffff);
            stylePill(spotifyAccess, px(12), tone, 0x1affffff);
            visualizer.setTone(tone);
        }

        private void stylePill(View pill, int radius, WallpaperTheme.Tone tone, int idleColor) {
            StateListDrawable bg = new StateListDrawable();
            Drawable pressed = WallpaperTheme.focusWash(radius, tone, true);
            pressed.setAlpha(196);
            Drawable focused = WallpaperTheme.focusWash(radius, tone, false);
            focused.setAlpha(220);
            GradientDrawable idle = new GradientDrawable();
            idle.setCornerRadius(radius);
            idle.setColor(idleColor);
            bg.addState(new int[]{android.R.attr.state_pressed}, pressed);
            bg.addState(new int[]{android.R.attr.state_focused}, focused);
            bg.addState(new int[]{}, idle);
            pill.setBackground(bg);
        }

        private void wireFocus() {
            ArrayList<View> chain = new ArrayList<>(10);
            chain.add(previousBtn);
            chain.add(play);
            chain.add(nextBtn);
            chain.add(sourceButton);
            chain.add(lyricFind);
            if (openAppButton.getVisibility() == View.VISIBLE) chain.add(openAppButton);
            if (plainScroll.getVisibility() == View.VISIBLE) chain.add(plainScroll);
            if (lyricCredit.isFocusable() && lyricCredit.getVisibility() == View.VISIBLE) chain.add(lyricCredit);
            if (lyricOffsetButton.getVisibility() == View.VISIBLE) chain.add(lyricOffsetButton);
            if (lyricAccess.getVisibility() == View.VISIBLE) chain.add(lyricAccess);
            if (spotifyAccess.getVisibility() == View.VISIBLE) chain.add(spotifyAccess);

            for (int i = 0; i < chain.size(); i++) {
                View cur = chain.get(i);
                View left = chain.get(Math.max(0, i - 1));
                View right = chain.get(Math.min(chain.size() - 1, i + 1));
                cur.setNextFocusLeftId(left.getId());
                cur.setNextFocusRightId(right.getId());
            }
            int upperId = timeline.isFocusable() ? timeline.getId() : sourceButton.getId();
            for (View control : mediaButtons) {
                control.setNextFocusUpId(upperId);
                control.setNextFocusDownId(lyricFind.getId());
            }
            if (timeline.isFocusable()) {
                timeline.setNextFocusLeftId(timeline.getId());
                timeline.setNextFocusRightId(timeline.getId());
                timeline.setNextFocusDownId(play.getId());
                timeline.setNextFocusUpId(plainScroll.getVisibility() == View.VISIBLE ? plainScroll.getId() : timeline.getId());
            }
            sourceButton.setNextFocusDownId(play.getId());
            sourceButton.setNextFocusUpId(timeline.isFocusable() ? timeline.getId()
                : plainScroll.getVisibility() == View.VISIBLE ? plainScroll.getId() : sourceButton.getId());
            lyricFind.setNextFocusUpId(timeline.isFocusable() ? timeline.getId() : play.getId());
            openAppButton.setNextFocusUpId(timeline.isFocusable() ? timeline.getId() : play.getId());
            View lowerTarget = lyricOffsetButton.getVisibility() == View.VISIBLE ? lyricOffsetButton
                : lyricAccess.getVisibility() == View.VISIBLE ? lyricAccess
                : spotifyAccess.getVisibility() == View.VISIBLE ? spotifyAccess : null;
            if (lowerTarget != null) {
                lyricFind.setNextFocusDownId(lowerTarget.getId());
                openAppButton.setNextFocusDownId(lowerTarget.getId());
            } else {
                lyricFind.setNextFocusDownId(lyricFind.getId());
                openAppButton.setNextFocusDownId(openAppButton.getId());
            }
            if (lyricOffsetButton.getVisibility() == View.VISIBLE) lyricOffsetButton.setNextFocusUpId(lyricFind.getId());
            if (lyricAccess.getVisibility() == View.VISIBLE) lyricAccess.setNextFocusUpId(lyricFind.getId());
            if (spotifyAccess.getVisibility() == View.VISIBLE) spotifyAccess.setNextFocusUpId(lyricFind.getId());
        }

        void openMusicApp() {
            if (closed || currentMedia == null || currentMedia.source.isEmpty()) return;
            String pkg = currentMedia.source;
            android.content.pm.PackageManager pm = owner.getPackageManager();
            android.content.Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (intent == null) intent = pm.getLaunchIntentForPackage(pkg);
            if (intent == null) {
                Toast.makeText(owner, vi ? "Không tìm thấy màn hình chính của ứng dụng này"
                    : "Cannot open this media app directly", Toast.LENGTH_SHORT).show();
                return;
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try {
                RecentApps.record(owner, pkg);
                lifecycleClose = true;
                close();
                dialog.dismiss();
                owner.startActivity(intent);
            } catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
                Toast.makeText(owner, vi ? "Không mở được ứng dụng nhạc" : "Could not open media app", Toast.LENGTH_SHORT).show();
            }
        }

        boolean metadataAutomatic() { return metadata == null || metadata.automatic(); }

        void chooseLyrics() {
            if (closed || lyricPicker != null || accessDialog != null && accessDialog.isShowing()) return;
            wakeHud(false);
            SpotifyMetadata.Response track = currentMedia;
            if (track == null || lyrics.trackKey().isEmpty()) {
                Toast.makeText(owner, vi ? "Chưa có thông tin bài hát để tìm lời" : "Track information is not available", Toast.LENGTH_SHORT).show();
                return;
            }
            final String requestedKey = lyrics.trackKey();
            lyricPickerKey = requestedKey;
            final LyricCandidates search = new LyricCandidates(track.title, track.artist, playback.duration);
            candidateSearch = search;
            Dialog picker = new Dialog(owner) {
                @Override
                public void onWindowFocusChanged(boolean focused) {
                    super.onWindowFocusChanged(focused);
                    if (!closed) { if (focused) metadata.start(); else metadata.stop(); }
                }
            };
            lyricPicker = picker;
            picker.setOwnerActivity(owner);
            picker.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout content = new LinearLayout(owner);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(px(20), px(16), px(20), px(16));
            content.setBackground(WallpaperTheme.glass(dp, activeTone));
            TextView heading = text(vi ? "Chọn bản lời" : "Choose lyrics", 16, Color.WHITE);
            heading.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            content.addView(heading, new LinearLayout.LayoutParams(-1, px(32)));
            TextView song = text(track.title + " · " + track.artist, 11, 0x99ffffff);
            song.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            content.addView(song, new LinearLayout.LayoutParams(-1, px(28)));
            LinearLayout autoRows = new LinearLayout(owner);
            autoRows.setOrientation(LinearLayout.VERTICAL);
            content.addView(autoRows, new LinearLayout.LayoutParams(-1, -2));
            View auto = lyricChoiceRow(autoRows, (lyrics.isManual() ? "" : "✓  ") + (vi ? "Tự động" : "Automatic"),
                vi ? "Dùng lại các nguồn lời tự động" : "Use automatic lyrics sources", "music_lyrics_auto", () -> {
                    if (lyrics.choose(requestedKey, null)) PopupOptimizer.dismissAnimated(picker, false);
                });
            ScrollView scroll = new ScrollView(owner);
            scroll.setVerticalScrollBarEnabled(false);
            LinearLayout rows = new LinearLayout(owner);
            rows.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(rows);
            content.addView(scroll, new LinearLayout.LayoutParams(px(560), px(224)));
            TextView status = text(vi ? "Đang tìm các bản lời…" : "Finding lyrics versions…", 10, 0xbbffffff);
            status.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            status.setSingleLine(false);
            status.setMaxLines(2);
            content.addView(status, new LinearLayout.LayoutParams(-1, px(44)));
            Backdrop.wrap(owner, picker, content, true, 0);
            picker.setOnDismissListener(ignored -> {
                search.close();
                if (candidateSearch == search) candidateSearch = null;
                if (lyricPicker == picker) lyricPicker = null;
                if (!closed) {
                    wakeHud(false);
                    lyricFind.requestFocus();
                }
            });
            picker.show();
            picker.getWindow().setLayout(-1, -1);
            auto.requestFocus();
            search.search((candidates, failed) -> {
                if (closed || lyricPicker != picker || !requestedKey.equals(lyrics.trackKey())) return;
                rows.removeAllViews();
                final boolean[] busy = {false};
                for (LyricCandidates.Candidate candidate : candidates) {
                    long delta = Math.round((candidate.duration - track.playback.duration) / 1000.0);
                    String kind = candidate.words ? (vi ? "Từng từ" : "Word timing")
                        : candidate.synced ? (vi ? "Đồng bộ theo câu" : "Line timing")
                        : (vi ? "Lời tĩnh" : "Unsynced text");
                    String timing = MusicPlayback.time(candidate.duration)
                        + (Math.abs(candidate.duration - track.playback.duration) > 2000 ? " · Δ " + (delta >= 0 ? "+" : "") + delta + "s" : "");
                    String details = kind + " · " + candidate.provider + " · " + timing + (candidate.album.isEmpty() ? "" : " · " + candidate.album);
                    boolean checked = candidate.key().equals(lyrics.selectedKey());
                    View row = lyricChoiceRow(rows,
                        (checked ? "✓  " : "") + candidate.title + " · " + candidate.artist,
                        details, "music_lyrics_candidate_" + candidate.key(), () -> {
                            if (busy[0] || !requestedKey.equals(lyrics.trackKey())) return;
                            busy[0] = true;
                            status.setText(vi ? "Đang tải bản đã chọn…" : "Loading selected lyrics…");
                            search.choose(candidate, data -> {
                                if (closed || lyricPicker != picker || !requestedKey.equals(lyrics.trackKey())) return;
                                busy[0] = false;
                                if (data != null && lyrics.choose(requestedKey, data, LyricChoiceStore.Choice.fromCandidate(candidate)))
                                    PopupOptimizer.dismissAnimated(picker, false);
                                else status.setText(vi ? "Bản này chưa dùng được. Chọn bản khác hoặc Tự động."
                                    : "This version is unavailable. Choose another or Automatic.");
                            });
                        });
                    if (rows.getChildCount() == 1) {
                        auto.setNextFocusDownId(row.getId());
                        row.setNextFocusUpId(auto.getId());
                    }
                }
                FontChoice.apply(rows);
                status.setText(candidates.isEmpty()
                    ? (failed ? (vi ? "Chưa tải được kết quả. Có thể quay lại và thử sau." : "Search unavailable. Go back and try later.")
                        : (vi ? "Chưa tìm thấy bản phù hợp với tên bài và ca sĩ." : "No matching title and artist found."))
                    : (vi ? "Δ: chênh thời lượng. Giữ nguyên mốc gốc, không tự căn giờ." : "Δ: duration difference. Original timestamps are kept."));
            });
        }

        View lyricChoiceRow(LinearLayout rows, String label, String detail, String tag, Runnable action) {
            LinearLayout row = new LinearLayout(owner);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setId(View.generateViewId());
            row.setTag(tag);
            row.setFocusable(true);
            row.setClickable(true);
            row.setPadding(px(12), px(6), px(12), px(6));
            row.setContentDescription(label + ". " + detail);
            TextView name = text(label, 13, Color.WHITE);
            name.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            TextView description = text(detail, 10, 0x99ffffff);
            description.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.addView(name, new LinearLayout.LayoutParams(-1, px(24)));
            row.addView(description, new LinearLayout.LayoutParams(-1, px(18)));
            stylePill(row, px(12), activeTone, Color.TRANSPARENT);
            row.setOnClickListener(view -> action.run());
            rows.addView(row, new LinearLayout.LayoutParams(-1, px(60)));
            return row;
        }

        void chooseSource() {
            if (closed || sourceDialog != null) return;
            wakeHud(false);
            SpotifyMetadata.Response snapshot = currentMedia;
            Dialog picker = new Dialog(owner);
            picker.setOwnerActivity(owner);
            picker.requestWindowFeature(Window.FEATURE_NO_TITLE);
            sourceDialog = picker;
            LinearLayout content = new LinearLayout(owner);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(px(20), px(16), px(20), px(16));
            content.setBackground(WallpaperTheme.glass(dp, activeTone));
            TextView heading = text(vi ? "Nguồn phát nhạc" : "Media source", 16, Color.WHITE);
            heading.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            content.addView(heading, new LinearLayout.LayoutParams(-1, px(36)));
            ScrollView scroll = new ScrollView(owner);
            scroll.setVerticalScrollBarEnabled(false);
            LinearLayout rows = new LinearLayout(owner);
            rows.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(rows);
            int count = snapshot == null ? 0 : snapshot.sources.length;
            View focused = sourceRow(rows, picker, "", vi ? "Tự động · ưu tiên ứng dụng đang phát" : "Auto · prefer the playing app", metadata.automatic());
            if (snapshot != null) {
                for (SpotifyMetadata.Source source : snapshot.sources) {
                    String status = source.state == 3 ? (vi ? "Đang phát" : "Playing")
                        : source.state == 2 ? (vi ? "Tạm dừng" : "Paused") : "";
                    boolean selected = !metadata.automatic() && source.id.equals(snapshot.sessionId);
                    View row = sourceRow(rows, picker, source.id, source.label + (status.isEmpty() ? "" : " · " + status), selected);
                    if (selected) focused = row;
                }
            }
            if (count == 0) {
                TextView empty = text(vi ? "Phát nhạc trong ứng dụng để hiện ở đây" : "Play media in an app to see it here", 11, 0x99ffffff);
                empty.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                rows.addView(empty, new LinearLayout.LayoutParams(-1, px(40)));
            }
            content.addView(scroll, new LinearLayout.LayoutParams(px(360), px(Math.min(300, (count + 1) * 46 + (count == 0 ? 40 : 0)))));
            Backdrop.wrap(owner, picker, content, true, 0);
            picker.setOnDismissListener(ignored -> {
                sourceDialog = null;
                if (!closed) {
                    wakeHud(false);
                    sourceButton.requestFocus();
                }
            });
            picker.show();
            picker.getWindow().setLayout(-1, -1);
            focused.requestFocus();
        }

        View sourceRow(LinearLayout rows, Dialog picker, String id, String label, boolean selected) {
            TextView row = text((selected ? "✓  " : "") + label, 12, Color.WHITE);
            row.setId(View.generateViewId());
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setPadding(px(12), 0, px(12), 0);
            row.setFocusable(true);
            row.setClickable(true);
            row.setTag("music_source_" + (id.isEmpty() ? "auto" : id));
            stylePill(row, px(10), activeTone, Color.TRANSPARENT);
            row.setOnClickListener(view -> {
                if (closed) return;
                currentMedia = null;
                playback = MusicPlayback.UNKNOWN;
                currentSpotifyId = "";
                appLiveLyrics.clear();
                lyrics.setTrack("", "", "", "", 0);
                for (View control : mediaButtons) control.setAlpha(.35f);
                metadata.select(id);
                PopupOptimizer.dismissAnimated(picker, false);
            });
            rows.addView(row, new LinearLayout.LayoutParams(-1, px(46)));
            return row;
        }

        void sendSelected(int key) {
            if (closed || commandPending) return;
            SpotifyMetadata.Response source = currentMedia;
            if (source == null || !MediaSources.supports(source.actions, key, source.playback.playing())) {
                Toast.makeText(owner, vi ? "Nguồn này chưa hỗ trợ thao tác đó" : "This source does not support that action", Toast.LENGTH_SHORT).show();
                return;
            }
            commandPending = true;
            metadata.control(source.sessionId, key, sent -> {
                commandPending = false;
                if (sent && (key == KeyEvent.KEYCODE_MEDIA_REWIND || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)) {
                    resetLyricMotion();
                }
                if (!sent) Toast.makeText(owner, vi ? "Nguồn phát đã đổi hoặc chưa nhận lệnh" : "Media source changed or did not accept the command", Toast.LENGTH_SHORT).show();
            });
        }

        void connectSources() {
            if (closed || accessDialog != null && accessDialog.isShowing()) return;
            wakeHud(false);
            accessDialog = LyricsAccess.show(owner, vi, () -> {
                accessDialog = null;
                if (!closed) {
                    lyricAccess.setVisibility(LyricsAccess.token(owner).isEmpty() ? View.VISIBLE : View.GONE);
                    wireFocus();
                    wakeHud(false);
                    lyrics.refresh();
                }
            });
        }

        void connectSpotify() {
            if (closed || accessDialog != null && accessDialog.isShowing()) return;
            wakeHud(false);
            accessDialog = SpotifyAccess.show(owner, vi, currentSpotifyId, playback.duration, () -> {
                accessDialog = null;
                if (!closed) {
                    wakeHud(false);
                    lyrics.refresh();
                }
            });
        }

        private Lyrics effectiveLyrics() {
            if (lyrics != null && lyrics.isManual() && lyricData != null) return lyricData;
            if (lyricData != null && lyricData.isSynced()) return lyricData;
            if (appLiveData != null && appLiveData.size() > 0) return appLiveData;
            return lyricData;
        }

        private boolean showingAppLive() {
            return appLiveData != null && appLiveData.size() > 0 && effectiveLyrics() == appLiveData;
        }

        private void syncLyricsStage(boolean liveAppended) {
            Lyrics shown = effectiveLyrics();
            boolean liveNow = shown != null && shown == appLiveData;
            boolean modeChanged = liveNow != usingAppLive;
            usingAppLive = liveNow;
            renderPlain(shown);
            renderCredit(shown);
            if (liveNow) {
                boolean animate = liveAppended && !modeChanged && playback.playing() && focusedWindow && motionEnabled();
                lyricIndex = appLiveIndex;
                rubyStage.updateLiveLyrics(appLiveData, appLiveIndex, animate);
            } else {
                lyricIndex = Integer.MIN_VALUE;
                rubyStage.setLyrics(shown, lyricStatus);
            }
            if (metadata != null) metadata.setFastPoll(liveNow && playback.playing());
            wireFocus();
            renderPlayback();
        }

        void renderPlain(Lyrics data) {
            boolean plain = data != null && !data.isSynced();
            if (!plain && plainScroll.hasFocus()) play.requestFocus();
            plainScroll.setVisibility(plain ? View.VISIBLE : View.GONE);
            rubyStage.setVisibility(plain ? View.GONE : View.VISIBLE);
            plainView.setLyrics(plain ? data : null);
            plainScroll.scrollTo(0, 0);
        }

        void adjustLyricOffset() {
            if (closed || offsetDialog != null && offsetDialog.isShowing() || offsetTrackKey.isEmpty()) return;
            wakeHud(false);
            Dialog picker = new Dialog(owner) {
                @Override
                public void onWindowFocusChanged(boolean focused) {
                    super.onWindowFocusChanged(focused);
                    if (!closed) {
                        if (focused) { metadata.start(); renderPlayback(); }
                        else metadata.stop();
                    }
                }
            };
            offsetDialog = picker;
            picker.setOwnerActivity(owner);
            picker.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout content = new LinearLayout(owner);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(px(22), px(18), px(22), px(18));
            content.setBackground(WallpaperTheme.glass(dp, activeTone));

            TextView heading = text(vi ? "Chỉnh nhịp lời" : "Lyric timing", 16, Color.WHITE);
            heading.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            content.addView(heading, new LinearLayout.LayoutParams(-1, px(30)));

            final TextView readout = text(LyricOffsetStore.formatDelta(lyricOffsetMs), 22, Color.WHITE);
            readout.setTypeface(Typeface.DEFAULT_BOLD);
            readout.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams readLp = new LinearLayout.LayoutParams(-1, px(42));
            readLp.topMargin = px(4);
            content.addView(readout, readLp);

            LinearLayout row = new LinearLayout(owner);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, px(40));
            rowLp.topMargin = px(6);
            content.addView(row, rowLp);

            final long[] deltas = {-500L, -250L, 0L, 250L, 500L};
            final String[] labels = {"-0.5s", "-0.25s", "0.0s", "+0.25s", "+0.5s"};
            View centerBtn = null;
            for (int i = 0; i < deltas.length; i++) {
                final long delta = deltas[i];
                TextView btn = pillButton(labels[i], "music_offset_" + i, labels[i], () -> {
                    long next = delta == 0L ? 0L : LyricOffsetStore.clamp(lyricOffsetMs + delta);
                    lyricOffsetMs = LyricOffsetStore.put(owner, offsetTitle, offsetArtist, offsetDuration, next);
                    readout.setText(LyricOffsetStore.formatDelta(lyricOffsetMs));
                    lyricOffsetButton.setText(LyricOffsetStore.formatPill(lyricOffsetMs, vi));
                    lyricIndex = Integer.MIN_VALUE;
                    resetLyricMotion();
                    renderPlayback();
                });
                btn.setTextSize(11.5f);
                btn.setPadding(px(12), 0, px(12), 0);
                stylePill(btn, px(14), activeTone, delta == 0L ? 0x26ffffff : 0x1cffffff);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, px(34));
                if (i > 0) lp.setMarginStart(px(8));
                row.addView(btn, lp);
                if (delta == 0L) centerBtn = btn;
            }

            TextView hint = text(vi
                ? "(+) Lời hiện sớm hơn · (−) Lời hiện chậm hơn · Lưu theo từng bài"
                : "(+) Earlier lyrics · (−) Later lyrics · Saved per track", 10, 0xa8ffffff);
            hint.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, px(26));
            hintLp.topMargin = px(10);
            content.addView(hint, hintLp);

            FontChoice.apply(content);
            Backdrop.wrap(owner, picker, content, true, 0);
            picker.setOnDismissListener(ignored -> {
                if (offsetDialog == picker) offsetDialog = null;
                if (!closed) {
                    wakeHud(false);
                    if (lyricOffsetButton.getVisibility() == View.VISIBLE) lyricOffsetButton.requestFocus();
                    else play.requestFocus();
                }
            });
            picker.show();
            picker.getWindow().setLayout(-1, -1);
            if (centerBtn != null) centerBtn.requestFocus();
        }

        void renderCredit(Lyrics data) {
            boolean canOffset = !showingAppLive() && data != null && data.isSynced() && !offsetTrackKey.isEmpty();
            if (!canOffset && lyricOffsetButton.hasFocus()) play.requestFocus();
            lyricOffsetButton.setVisibility(canOffset ? View.VISIBLE : View.GONE);
            lyricOffsetButton.setText(LyricOffsetStore.formatPill(lyricOffsetMs, vi));
            lyricAccess.setVisibility(LyricsAccess.token(owner).isEmpty() ? View.VISIBLE : View.GONE);
            lyricCredit.setMovementMethod(null);
            lyricCredit.setFocusable(false);
            lyricCredit.setClickable(false);
            lyricCredit.setLongClickable(false);
            if (data == null || data.sourceUrl().isEmpty()) {
                lyricCredit.setText(data == null ? "Better Lyrics · LyricsPlus · LrcRed · Unison · LRCLIB"
                    : data.source() + (lyrics != null && lyrics.isManual() ? (vi ? " · Đã chọn" : " · Selected") : ""));
                return;
            }
            String label = "Lyrics from " + data.source(), credit = label + " · " + data.contributor();
            SpannableString links = new SpannableString(credit);
            links.setSpan(creditLink(data.sourceUrl()), 0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            links.setSpan(creditLink(data.contributorUrl()), label.length() + 3, credit.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            lyricCredit.setText(links);
            lyricCredit.setLinkTextColor(0x99ffffff);
            lyricCredit.setMovementMethod(LinkMovementMethod.getInstance());
            lyricCredit.setFocusable(true);
        }

        URLSpan creditLink(String address) {
            return new URLSpan(address) {
                @Override
                public void onClick(View widget) {
                    try { super.onClick(widget); }
                    catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
                        Toast.makeText(owner, vi ? "TV chưa mở được liên kết này" : "Cannot open this link on this TV", Toast.LENGTH_SHORT).show();
                    }
                }
            };
        }

        void renderPlayback() {
            clock.removeCallbacks(clockTick);
            rubyStage.removeCallbacks(clockTick);
            if (closed) return;
            long position = playback.positionAt(SystemClock.elapsedRealtime());
            String elapsedText = MusicPlayback.time(position);
            String totalText = MusicPlayback.time(playback.duration > 0 ? playback.duration : -1);
            if (!TextUtils.equals(elapsed.getText(), elapsedText)) elapsed.setText(elapsedText);
            if (!TextUtils.equals(total.getText(), totalText)) total.setText(totalText);
            timeline.setPosition(position, playback.duration);

            boolean appLive = showingAppLive();
            long lyricPosition = position < 0 ? position : Math.max(0L, position + lyricOffsetMs);
            if (!appLive && (lyricData == null || lyricData.isSynced())) {
                int index = lyricData == null ? Integer.MIN_VALUE + 1 : lyricData.indexAt(lyricPosition);
                boolean animate = lyricData != null && lyricIndex >= -1 && index == lyricIndex + 1
                    && playback.playing() && focusedWindow && motionEnabled();
                lyricIndex = index;
                double originalCoverage = lyricData == null ? Double.NaN : lyricData.coverageAt(index, lyricPosition, false);
                boolean stepped = rubyStage.updatePlayback(index, lyricPosition, originalCoverage, animate);
                if (stepped && !motionReported) {
                    motionReported = true;
                    android.util.Log.i("LiveLyrics", "Line motion active duration=480ms cascade=true");
                }
            }

            boolean playing = playback.playing();
            int glyph = playing ? 4 : 1;
            if (glyph != playGlyph) {
                ((ImageView) play).setImageDrawable(new Glyph(px(22), glyph));
                playGlyph = glyph;
            }
            String playLabel = playing ? (vi ? "Tạm dừng" : "Pause") : (vi ? "Phát" : "Play");
            if (!TextUtils.equals(play.getContentDescription(), playLabel)) play.setContentDescription(playLabel);
            syncVisualizerState();
            if (focusedWindow && playing && position >= 0 && playback.duration > 0) {
                if (!appLive && lyricData != null && motionEnabled() && lyricData.isActivelySweeping(lyricIndex, lyricPosition)) {
                    rubyStage.postOnAnimation(clockTick);
                } else {
                    clock.postDelayed(clockTick, appLive || lyricData == null ? 1000L : lyricData.refreshDelay(lyricIndex, lyricPosition));
                }
            }
        }

        void syncVisualizerState() {
            if (visualizer == null) return;
            boolean hasTrack = visualizer.getVisibility() == View.VISIBLE;
            boolean allowMotion = !closed && !closing && focusedWindow && !entranceActive && hasTrack && motionEnabled();
            visualizer.setState(hasTrack && playback.playing(), allowMotion);
        }

        boolean motionEnabled() {
            return ValueAnimator.areAnimatorsEnabled() && (!(owner instanceof MainActivity)
                || (((MainActivity) owner).animations && !((MainActivity) owner).reducedMotion));
        }

        void resetLyricMotion() {
            rubyStage.resetMotion();
        }

        int px(float value) { return Math.round(value * dp); }

        TextView text(String value, int size, int color) {
            TextView text = new TextView(owner);
            text.setText(value);
            text.setTextSize(size);
            text.setTextColor(color);
            text.setGravity(Gravity.CENTER);
            text.setSingleLine(true);
            text.setIncludeFontPadding(false);
            text.setEllipsize(TextUtils.TruncateAt.END);
            return text;
        }

        TextView pillButton(String label, String tag, String description, Runnable action) {
            TextView pill = text(label, 10, 0xeaffffff);
            pill.setTextSize(10.5f);
            pill.setShadowLayer(px(3), 0f, px(1f), 0x58000000);
            pill.setId(View.generateViewId());
            pill.setTag(tag);
            pill.setFocusable(true);
            pill.setClickable(true);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(px(11), 0, px(11), 0);
            pill.setContentDescription(description);
            attachMusicFocus(pill, 1.045f);
            pill.setOnClickListener(v -> action.run());
            return pill;
        }

        private void attachMusicFocus(final View view, final float scale) {
            if (view == null) return;
            view.setOnFocusChangeListener((target, focused) -> {
                target.animate().cancel();
                if (focused && motionEnabled()) {
                    target.animate().scaleX(scale).scaleY(scale).translationY(-px(1.5f)).translationZ(px(4f))
                        .setDuration(150L).setInterpolator(new DecelerateInterpolator(1.4f)).withLayer().start();
                } else {
                    target.animate().scaleX(1f).scaleY(1f).translationY(0f).translationZ(0f)
                        .setDuration(motionEnabled() ? 110L : 0L)
                        .setInterpolator(new DecelerateInterpolator(1.5f)).withLayer().start();
                }
            });
        }

        View button(int glyph, int key, String label, boolean primary) {
            ImageView button = new ImageView(owner);
            button.setId(View.generateViewId());
            button.setFocusable(true);
            button.setClickable(true);
            button.setContentDescription(label);
            button.setTag("music_control_" + key);
            button.setImageDrawable(new Glyph(px(primary ? 22 : 18), glyph));
            button.setScaleType(ImageView.ScaleType.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(primary ? 44 : 38), px(primary ? 44 : 38));
            if (primary) {
                lp.setMarginStart(px(4));
                lp.setMarginEnd(px(4));
            }
            button.setLayoutParams(lp);
            ConfirmPress press = new ConfirmPress();
            presses.add(press);
            button.setOnFocusChangeListener((view, focused) -> {
                press.reset();
                // Keep the press guard and visual focus motion on the same listener.
                view.animate().cancel();
                if (focused && motionEnabled()) {
                    view.animate().scaleX(primary ? 1.08f : 1.06f).scaleY(primary ? 1.08f : 1.06f)
                        .translationY(-px(1.5f)).translationZ(px(4f)).setDuration(150L)
                        .setInterpolator(new DecelerateInterpolator(1.4f)).withLayer().start();
                } else {
                    view.animate().scaleX(1f).scaleY(1f).translationY(0f).translationZ(0f)
                        .setDuration(motionEnabled() ? 110L : 0L)
                        .setInterpolator(new DecelerateInterpolator(1.5f)).withLayer().start();
                }
            });
            button.setOnKeyListener((view, code, event) -> {
                if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                        && code != KeyEvent.KEYCODE_NUMPAD_ENTER && code != KeyEvent.KEYCODE_BUTTON_A) return false;
                if (press.accept(code, event.getAction(), event.getDownTime(), event.getRepeatCount(), event.isCanceled())) {
                    view.performClick();
                }
                return true;
            });
            mediaButtons.add(button);
            button.setOnClickListener(view -> sendSelected(key));
            return button;
        }

        private View resolveAnimTarget() {
            return (panel.getParent() instanceof View
                && CrystalSoundEngine.TAG_POPUP_SHELL.equals(((View) panel.getParent()).getTag()))
                ? (View) panel.getParent() : panel;
        }

        void show() {
            boolean animate = motionEnabled();
            final View animTarget = resolveAnimTarget();
            panel.setAlpha(1f);
            panel.setScaleX(1f);
            panel.setScaleY(1f);
            panel.setTranslationY(0f);

            if (animate) {
                entranceActive = true;
                backdrop.setEntranceActive(true);
                syncVisualizerState();
                animTarget.animate().cancel();
                animTarget.setAlpha(0f);
                animTarget.setScaleX(0.948f);
                animTarget.setScaleY(0.948f);
                animTarget.setTranslationY(px(14));
            } else {
                entranceActive = false;
                animTarget.animate().cancel();
                animTarget.setAlpha(1f);
                animTarget.setScaleX(1f);
                animTarget.setScaleY(1f);
                animTarget.setTranslationY(0f);
            }
            dialog.show();
            dialog.getWindow().setLayout(-1, -1);
            play.requestFocus();
            scheduleHudRest();
            if (animate) {
                animTarget.postOnAnimation(() -> {
                    if (closed || closing || !animTarget.isAttachedToWindow()) {
                        entranceActive = false;
                        backdrop.setEntranceActive(false);
                        syncVisualizerState();
                        return;
                    }
                    animTarget.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationY(0f)
                        .setDuration(340L)
                        .setInterpolator(ENTRANCE_EASE)
                        .withLayer()
                        .withEndAction(() -> {
                            if (!closed) {
                                entranceActive = false;
                                backdrop.setEntranceActive(false);
                                syncVisualizerState();
                            }
                        })
                        .start();
                });
                clock.postDelayed(() -> {
                    if (!closed && entranceActive) {
                        entranceActive = false;
                        backdrop.setEntranceActive(false);
                        syncVisualizerState();
                    }
                }, 400L);
            }
        }

        void dismissAnimated() {
            if (closed || closing) return;
            if (!motionEnabled() || !panel.isAttachedToWindow() || owner.isFinishing() || owner.isDestroyed()) {
                finishDismiss();
                return;
            }
            closing = true;
            entranceActive = false;
            clock.removeCallbacks(clockTick);
            rubyStage.removeCallbacks(clockTick);
            clock.removeCallbacks(restHudTask);
            metadata.setFastPoll(false);
            metadata.stop();
            lyrics.stop();
            resetPresses();
            resetLyricMotion();
            backdrop.setEntranceActive(false);
            syncVisualizerState();

            final View animTarget = resolveAnimTarget();
            View flatView = null;
            if (animTarget.getParent() instanceof ViewGroup) {
                ViewGroup root = (ViewGroup) animTarget.getParent();
                if (root.getChildCount() > 0 && root.getChildAt(0) != animTarget) {
                    flatView = root.getChildAt(0);
                }
            }
            if (flatView != null) {
                flatView.setTag(Boolean.TRUE);
                flatView.animate().cancel();
                flatView.animate()
                    .alpha(0f)
                    .setDuration(230L)
                    .setInterpolator(EXIT_EASE)
                    .start();
            }
            animTarget.animate().cancel();
            animTarget.animate()
                .alpha(0f)
                .scaleX(0.962f)
                .scaleY(0.962f)
                .translationY(px(10))
                .setDuration(230L)
                .setInterpolator(EXIT_EASE)
                .withLayer()
                .withEndAction(this::finishDismiss)
                .start();
            clock.postDelayed(this::finishDismiss, 290L);
        }

        void finishDismiss() {
            if (!closed) {
                close();
            }
            try {
                if (dialog.isShowing()) dialog.dismiss();
            } catch (Throwable ignored) {}
        }

        void resetPresses() { for (ConfirmPress press : presses) press.reset(); }

        void close() {
            if (closed) return;
            closed = true;
            closing = false;
            entranceActive = false;
            clock.removeCallbacksAndMessages(null);
            rubyStage.removeCallbacks(clockTick);
            visualizer.setState(false, false);
            metadata.close();
            appLiveLyrics.close();
            lyrics.close();
            resetPresses();
            resetLyricMotion();
            if (panel.getParent() instanceof View) {
                ((View) panel.getParent()).animate().cancel();
            }
            panel.animate().cancel();
            trackHeader.animate().cancel();
            controlsHud.animate().cancel();
            creditRow.animate().cancel();
            backdrop.release();
            if (accessDialog != null) { accessDialog.dismiss(); accessDialog = null; }
            if (sourceDialog != null) { sourceDialog.dismiss(); sourceDialog = null; }
            if (lyricPicker != null) { lyricPicker.dismiss(); lyricPicker = null; }
            if (offsetDialog != null) { offsetDialog.dismiss(); offsetDialog = null; }
            if (candidateSearch != null) { candidateSearch.close(); candidateSearch = null; }
            if (active.get() == this) active = new WeakReference<>(null);
            if (!lifecycleClose && !owner.isFinishing() && !owner.isDestroyed()) {
                owner.onUserInteraction();
                CrystalSoundEngine.scheduleAmbientCheck(owner);
                View anchor = origin.get();
                if (anchor != null && anchor.isAttachedToWindow()) anchor.requestFocus();
            }
        }
    }

    private static final class Timeline extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF bounds = new RectF();
        final float density;
        float progress;

        Timeline(Context context, float density) { super(context); this.density = density; }

        void setPosition(long position, long duration) {
            float next = duration > 0 && position >= 0 ? Math.max(0f, Math.min(1f, position / (float) duration)) : 0f;
            if (next != progress) { progress = next; invalidate(); }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            boolean focused = isFocused();
            float pad = focused ? 5.5f * density : 0f;
            float left = pad, right = Math.max(left + 1f, getWidth() - pad);
            float trackW = right - left;
            float radius = (focused ? 2.2f : 1.5f) * density, middle = getHeight() / 2f;
            bounds.set(left, middle - radius, right, middle + radius);
            paint.setColor(focused ? 0x58ffffff : 0x40ffffff);
            canvas.drawRoundRect(bounds, radius, radius, paint);
            float filledRight = left + trackW * progress;
            if (progress > 0f) {
                bounds.set(left, middle - radius, Math.max(left + radius * 2f, filledRight), middle + radius);
                paint.setColor(focused ? 0xffffffff : 0xf8ffffff);
                canvas.drawRoundRect(bounds, radius, radius, paint);
            }
            if (focused) {
                float cx = Math.max(left, Math.min(right, filledRight));
                paint.setColor(0x48ffffff);
                canvas.drawCircle(cx, middle, 6.8f * density, paint);
                paint.setColor(0xffffffff);
                canvas.drawCircle(cx, middle, 4.4f * density, paint);
            }
        }
    }

    interface EventSink { void send(int action, long downTime, long eventTime); }

    static void dispatch(EventSink sink, long time) {
        try { sink.send(KeyEvent.ACTION_DOWN, time, time); }
        finally { sink.send(KeyEvent.ACTION_UP, time, time); }
    }

    private static final class Glyph extends Drawable {
        final int size, kind;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();

        Glyph(int size, int kind) { this.size = size; this.kind = kind; paint.setColor(Color.WHITE); }

        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float s = Math.min(size, Math.min(b.width(), b.height()));
            canvas.save();
            canvas.translate(b.exactCenterX() - s / 2f, b.exactCenterY() - s / 2f);
            canvas.scale(s / 24f, s / 24f);
            path.reset();
            if (kind == 0) {
                path.moveTo(8, 6); path.lineTo(19, 3); path.lineTo(19, 7); path.lineTo(8, 10); path.close();
                canvas.drawPath(path, paint);
                canvas.drawRect(8, 7, 10, 18, paint);
                canvas.drawRect(17, 5, 19, 15, paint);
                canvas.drawOval(3, 16, 10, 21, paint);
                canvas.drawOval(12, 13, 19, 18, paint);
            } else if (kind == 1) {
                path.moveTo(6.5f, 3.5f); path.lineTo(20.5f, 12f); path.lineTo(6.5f, 20.5f); path.close();
                canvas.drawPath(path, paint);
            } else if (kind == 4) {
                canvas.drawRoundRect(5.5f, 4f, 9.8f, 20f, 1.4f, 1.4f, paint);
                canvas.drawRoundRect(14.2f, 4f, 18.5f, 20f, 1.4f, 1.4f, paint);
            } else {
                if (kind == 2) { canvas.translate(24, 0); canvas.scale(-1, 1); }
                path.moveTo(2, 5.5f); path.lineTo(11, 12); path.lineTo(2, 18.5f); path.close();
                path.moveTo(10.5f, 5.5f); path.lineTo(19.5f, 12); path.lineTo(10.5f, 18.5f); path.close();
                canvas.drawPath(path, paint);
                canvas.drawRoundRect(20f, 5.5f, 22.8f, 18.5f, 1.2f, 1.2f, paint);
            }
            canvas.restore();
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
