package local.launcherfix;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.UUID;

/** Public player connection used when the shell-owned media bridge is unavailable. */
final class MochyMediaBrowser {
    private static final class RevisionSlot {
        final String baseIdentity;
        final WeakReference<Bitmap> artRef;
        final boolean hasArt;
        final int artGen, artW, artH;
        final String hex;
        RevisionSlot(String baseIdentity, Bitmap art, int artGen, int artW, int artH, String hex) {
            this.baseIdentity = baseIdentity;
            this.hasArt = art != null;
            this.artRef = art != null ? new WeakReference<>(art) : null;
            this.artGen = artGen;
            this.artW = artW;
            this.artH = artH;
            this.hex = hex;
        }
        boolean matches(String identity, Bitmap art, int gen, int w, int h) {
            if (!baseIdentity.equals(identity) || hasArt != (art != null)) return false;
            if (!hasArt) return true;
            return artGen == gen && artW == w && artH == h && artRef != null && artRef.get() == art;
        }
    }

    private static final RevisionSlot[] REVISION_CACHE = new RevisionSlot[4];
    private static int sNextRevisionSlot;
    private static int sRevisionCacheHits;

    static synchronized void trimMemory() {
        Arrays.fill(REVISION_CACHE, null);
        sNextRevisionSlot = 0;
    }

    static synchronized int revisionCacheHits() {
        return sRevisionCacheHits;
    }

    private static synchronized String lookupCachedRevision(String baseIdentity, Bitmap art, int gen, int w, int h) {
        for (RevisionSlot slot : REVISION_CACHE) {
            if (slot != null && slot.matches(baseIdentity, art, gen, w, h)) {
                sRevisionCacheHits++;
                return slot.hex;
            }
        }
        return null;
    }

    private static synchronized void storeCachedRevision(String baseIdentity, Bitmap art, int gen, int w, int h, String hex) {
        REVISION_CACHE[sNextRevisionSlot] = new RevisionSlot(baseIdentity, art, gen, w, h, hex);
        sNextRevisionSlot = (sNextRevisionSlot + 1) & 3;
    }
    static final String PACKAGE = "com.mochyy.mochymusic";
    private static final ComponentName SERVICE = new ComponentName(PACKAGE, PACKAGE + ".playback.MusicService");
    private static final long CONNECT_TIMEOUT_MS = 8000L, RETRY_MS = 15000L;
    private final Context context;
    private final Runnable changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedHashSet<String> issuedIds = new LinkedHashSet<>();
    private final Runnable retry = () -> { if (this.wanted) start(); };
    private MediaBrowser browser;
    private Runnable timeout;
    private boolean wanted;
    private long generation, retryAt;
    private MediaSession.Token lastToken;
    private String lastId = "";
    private volatile Entry current;

    private static final class Entry {
        final MediaController controller;
        final String id;
        MediaController.Callback callback;
        Entry(MediaController controller, String id) { this.controller = controller; this.id = id; }
    }

    MochyMediaBrowser(Context applicationContext, Runnable changed) {
        this.context = applicationContext.getApplicationContext();
        this.changed = changed;
    }

    void start() {
        requireMain();
        wanted = true;
        if (browser != null) return;
        main.removeCallbacks(retry);
        long delay = retryAt - SystemClock.elapsedRealtime();
        if (delay > 0) { main.postDelayed(retry, delay); return; }
        final long attempt = ++generation;
        try {
            ServiceInfo service = context.getPackageManager().getServiceInfo(SERVICE, 0);
            if (!service.enabled || !service.exported || !service.applicationInfo.enabled)
                throw new IllegalStateException("Public Mochy service unavailable");
            browser = new MediaBrowser(context, SERVICE, new MediaBrowser.ConnectionCallback() {
                @Override public void onConnected() {
                    if (!valid(attempt)) return;
                    try {
                        MediaSession.Token token = browser.getSessionToken();
                        MediaController controller = new MediaController(context, token);
                        if (!PACKAGE.equals(controller.getPackageName())) throw new IllegalStateException("Unexpected player");
                        if (!token.equals(lastToken)) {
                            lastToken = token;
                            lastId = UUID.randomUUID().toString().replace("-", "");
                        }
                        final Entry entry = new Entry(controller, lastId);
                        entry.callback = new MediaController.Callback() {
                            @Override public void onMetadataChanged(MediaMetadata metadata) { publish(); }
                            @Override public void onPlaybackStateChanged(PlaybackState state) { publish(); }
                            @Override public void onSessionDestroyed() {
                                if (current == entry && valid(attempt)) {
                                    lastToken = null; lastId = "";
                                    failed(attempt);
                                }
                            }
                            private void publish() { if (current == entry && valid(attempt)) changed.run(); }
                        };
                        controller.registerCallback(entry.callback, main);
                        current = entry;
                        synchronized (issuedIds) {
                            issuedIds.add(entry.id);
                            while (issuedIds.size() > 16) issuedIds.remove(issuedIds.iterator().next());
                        }
                        if (timeout != null) main.removeCallbacks(timeout);
                        retryAt = 0;
                        android.util.Log.i("MochyDirect", "Connected to public media service");
                        changed.run();
                    } catch (Exception unavailable) { failed(attempt); }
                }
                @Override public void onConnectionSuspended() { failed(attempt); }
                @Override public void onConnectionFailed() { failed(attempt); }
            }, new Bundle());
            timeout = () -> { if (valid(attempt) && current == null) failed(attempt); };
            main.postDelayed(timeout, CONNECT_TIMEOUT_MS);
            browser.connect();
        } catch (Exception unavailable) { failed(attempt); }
    }

    void stop() {
        requireMain();
        wanted = false;
        generation++;
        main.removeCallbacks(retry);
        disconnect();
    }

    boolean isConnecting() {
        requireMain();
        return wanted && browser != null && current == null;
    }

    private boolean valid(long attempt) { return wanted && generation == attempt; }

    private void failed(long attempt) {
        if (!valid(attempt)) return;
        generation++;
        disconnect();
        retryAt = SystemClock.elapsedRealtime() + RETRY_MS;
        main.removeCallbacks(retry);
        main.postDelayed(retry, RETRY_MS);
        changed.run();
    }

    private void disconnect() {
        if (timeout != null) { main.removeCallbacks(timeout); timeout = null; }
        Entry previous = current;
        current = null;
        if (previous != null) try { previous.controller.unregisterCallback(previous.callback); }
        catch (Exception ignored) { }
        MediaBrowser previousBrowser = browser;
        browser = null;
        if (previousBrowser != null) try { previousBrowser.disconnect(); }
        catch (Exception ignored) { }
    }

    boolean owns(String id) {
        synchronized (issuedIds) { return issuedIds.contains(id); }
    }

    SpotifyMetadata.Response read(String knownRevision, int maxSide) {
        Entry entry = current;
        if (entry == null) return null;
        try {
            SpotifyMetadata.Response response = snapshot(entry.controller.getMetadata(), entry.controller.getPlaybackState(),
                entry.id, knownRevision, maxSide);
            if (current == entry) return response;
            if (response != null && response.art != null) response.art.recycle();
        } catch (Exception unavailable) { }
        return null;
    }

    boolean control(String id, int key) {
        Entry entry = current;
        if (entry == null) return false;
        try {
            PlaybackState state = entry.controller.getPlaybackState();
            if (current != entry || !canControl(entry.id, id, state, key)) return false;
            MediaController.TransportControls controls = entry.controller.getTransportControls();
            if (current != entry) return false;
            if (key == 87) controls.skipToNext();
            else if (key == 88) controls.skipToPrevious();
            else if (key == 89 || key == 90) {
                MediaMetadata meta = entry.controller.getMetadata();
                long duration = meta == null ? 0L : Math.max(0L, meta.getLong(MediaMetadata.METADATA_KEY_DURATION));
                long target = MediaSources.seekTarget(state.getPosition(), state.getLastPositionUpdateTime(),
                    state.getPlaybackSpeed(), state.getState(), duration, SystemClock.elapsedRealtime(), key);
                if (target < 0L) return false;
                controls.seekTo(target);
            }
            else if (state.getState() == PlaybackState.STATE_PLAYING) controls.pause();
            else controls.play();
            return true;
        } catch (Exception unavailable) { return false; }
    }

    static boolean canControl(String currentId, String requestedId, PlaybackState state, int key) {
        return MediaSources.validId(currentId) && currentId.equals(requestedId) && validState(state)
            && MediaSources.supports(state.getActions(), key, state.getState() == PlaybackState.STATE_PLAYING);
    }

    static SpotifyMetadata.Response snapshot(MediaMetadata metadata, PlaybackState state, String id,
                                             String knownRevision, int maxSide) throws Exception {
        return snapshot(metadata, state, id, knownRevision, maxSide, PACKAGE, "Mochy Music");
    }

    static SpotifyMetadata.Response snapshot(MediaMetadata metadata, PlaybackState state, String id,
                                             String knownRevision, int maxSide, String packageName,
                                             String label) throws Exception {
        if (metadata == null || !validState(state) || !MediaSources.validId(id)
                || !MediaSources.validPackage(packageName)) return null;
        String title = first(metadata, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (title.isEmpty()) return null;
        String displaySubtitle = first(metadata, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        String liveLyric = MediaSources.extractLiveLyric(displaySubtitle);
        String artist = first(metadata, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (artist.isEmpty() && liveLyric.isEmpty()) artist = displaySubtitle;
        if (artist.isEmpty()) artist = first(metadata, MediaMetadata.METADATA_KEY_AUTHOR);
        String album = first(metadata, MediaMetadata.METADATA_KEY_ALBUM);
        String youtubeId = YouTubeTrack.extract(packageName, first(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID),
            first(metadata, MediaMetadata.METADATA_KEY_MEDIA_URI));
        long duration = Math.max(0, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
        Bitmap borrowed = null;
        for (String key : new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART,
                                     MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
            Bitmap candidate = metadata.getBitmap(key);
            if (candidate != null && !candidate.isRecycled() && candidate.getWidth() > 0 && candidate.getHeight() > 0) {
                borrowed = candidate; break;
            }
        }
        StringBuilder identity = new StringBuilder(packageName).append('\n').append(title).append('\n').append(artist)
            .append('\n').append(album).append('\n').append(duration).append('\n')
            .append(first(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID));
        if (!youtubeId.isEmpty()) identity.append('\n').append(youtubeId);
        String baseIdentity = identity.toString();
        int artW = 0, artH = 0, artGen = -1;
        if (borrowed != null) {
            try {
                artW = borrowed.getWidth();
                artH = borrowed.getHeight();
                artGen = borrowed.getGenerationId();
            } catch (RuntimeException unreadableArt) {
                borrowed = null;
            }
        }
        String revisionHex = lookupCachedRevision(baseIdentity, borrowed, artGen, artW, artH);
        if (revisionHex == null) {
            if (borrowed != null) {
                int withoutArt = identity.length();
                try {
                    identity.append(':').append(artW).append('x').append(artH);
                    for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++)
                        identity.append(':').append(borrowed.getPixel((2*x+1)*artW/16, (2*y+1)*artH/16));
                    artGen = borrowed.getGenerationId();
                } catch (RuntimeException unreadableArt) {
                    identity.setLength(withoutArt);
                    borrowed = null;
                    artW = artH = 0;
                    artGen = -1;
                }
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(Character.forDigit((value >> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
            revisionHex = hex.toString();
            storeCachedRevision(baseIdentity, borrowed, artGen, artW, artH, revisionHex);
        }
        SpotifyMetadata.Response response = new SpotifyMetadata.Response();
        response.version = 2; response.source = packageName; response.sessionId = id;
        response.sources = new SpotifyMetadata.Source[]{new SpotifyMetadata.Source(id, packageName, label, state.getState())};
        response.title = title; response.artist = artist; response.album = album;
        response.youtubeId = youtubeId;
        response.liveLyric = liveLyric;
        response.actions = state.getActions(); response.revision = revisionHex;
        response.playback = new MusicPlayback(true, duration, state.getPosition(), state.getLastPositionUpdateTime(),
            state.getPlaybackSpeed(), state.getState());
        response.unchanged = response.revision.equals(knownRevision);
        if (!response.unchanged && borrowed != null) {
            int cap = Math.max(32, Math.min(1024, maxSide));
            float scale = Math.min(1f, (float) cap / Math.max(borrowed.getWidth(), borrowed.getHeight()));
            if (scale < 1f) response.art = Bitmap.createScaledBitmap(borrowed,
                Math.max(1, Math.round(borrowed.getWidth() * scale)), Math.max(1, Math.round(borrowed.getHeight() * scale)), true);
            else response.art = borrowed.copy(Bitmap.Config.ARGB_8888, false);
            if (scale < 1f && response.art != null) FastPixelOps.sharpen(response.art, 0.22f);
        }
        return response;
    }

    private static boolean validState(PlaybackState state) {
        if (state == null) return false;
        float speed = state.getPlaybackSpeed();
        return state.getState() >= 0 && state.getState() <= 11 && state.getPosition() >= -1
            && state.getLastPositionUpdateTime() >= 0 && state.getActions() >= 0
            && !Float.isNaN(speed) && !Float.isInfinite(speed) && Math.abs(speed) <= 16f;
    }

    private static String first(MediaMetadata metadata, String... keys) {
        for (String key : keys) {
            CharSequence value = metadata.getText(key);
            if (value != null && value.length() > 0) return value.subSequence(0, Math.min(512, value.length())).toString();
        }
        return "";
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Browser lifecycle requires main thread");
    }
}
