package local.launcherfix;

import android.os.Looper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Isolated ART verification for fix.145 (all 4 optimization areas):
 * 1) Zero-rebind shared DirectHub handoff policy (750ms grace across Dock <-> Popup, clean idle/trim stop)
 * 2) Auto-expiring background workers (allowCoreThreadTimeOut) & complete MemoryTrimmer cache shedding
 * 3) Zero-alloc 60fps RubyLyricsView hot path (Cell.blank & FontChoice.faceEpoch O(1) guard)
 * 4) Single-track debounced transient RAM lyrics prefetch + cancel + cache deduplication
 */
public final class Fix145OptimizeCheck {
    private static int checks;

    private static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        checks++;
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();

        // 1. DirectHub ref-counted handoff policy (Dock <-> MiniPlayer zero-rebind + clean idle disconnect)
        SpotifyMetadata.HandoffPolicy p = new SpotifyMetadata.HandoffPolicy();
        p.acquire(1000L); // Dock acquires
        check(p.connected && p.holders == 1 && p.disconnectAt == -1L, "Dock acquires direct connection");
        p.release(1100L); // Dock loses focus as popup opens
        check(p.connected && p.holders == 0 && p.disconnectAt == 1100L + SpotifyMetadata.HANDOFF_GRACE_MS,
            "release arms 750ms grace window without tearing down connection");
        check(!p.tick(1200L), "connection stays warm 100ms into grace window");
        p.acquire(1220L); // MiniPlayer acquires 120ms later -> ZERO rebind!
        check(p.connected && p.holders == 1 && p.disconnectAt == -1L,
            "popup acquire cancels grace disconnect without rebinding");
        p.release(5000L); // Popup closes & user leaves launcher
        check(!p.tick(5500L), "still within 750ms grace at +500ms");
        check(p.tick(5750L) && !p.connected && p.disconnectAt == -1L,
            "disconnects cleanly once 750ms grace expires");
        p.acquire(6000L);
        p.release(6100L);
        check(p.trimNow() && !p.connected, "trimMemory disconnects idle connection immediately");
        p.acquire(7000L);
        check(!p.trimNow() && p.connected, "trimMemory never disconnects active holder");

        // 2. Auto-expiring background thread pools & complete MemoryTrimmer cache shedding
        check(SpotifyMetadata.workerAllowsCoreTimeout(), "SpotifyMetadata worker allows core thread timeout");
        check(MusicBackdropView.workerAllowsCoreTimeout(), "MusicBackdropView worker allows core thread timeout");
        check(ControlPanelHelper.telemetryWorkerAllowsCoreTimeout(), "ControlPanelHelper telemetry worker allows core thread timeout");
        LiveLyrics tempLive = new LiveLyrics((d, s) -> {});
        try {
            check(tempLive.workerAllowsCoreTimeout(), "LiveLyrics worker allows core thread timeout");
        } finally {
            tempLive.close();
        }

        // MusicBackdropView lazy scratch buffer release on trimMemory(20)
        Field pxField = MusicBackdropView.class.getDeclaredField("sWorkerPx");
        Field tmpField = MusicBackdropView.class.getDeclaredField("sWorkerTmp");
        pxField.setAccessible(true);
        tmpField.setAccessible(true);
        pxField.set(null, new int[160 * 90]);
        tmpField.set(null, new int[160 * 90]);
        MusicBackdropView.trimMemory(20);
        check(pxField.get(null) == null && tmpField.get(null) == null,
            "MusicBackdropView.trimMemory(20) releases 115KB scratch buffers");

        // WallpaperThumbLoader sFastKeyCache cleared on trimMemory(20)
        Field fastKeysField = WallpaperThumbLoader.class.getDeclaredField("sFastKeyCache");
        fastKeysField.setAccessible(true);
        Map<String, String> fastKeys = (Map<String, String>) fastKeysField.get(null);
        fastKeys.put("/fake/wallpaper.jpg", "key123");
        check(!fastKeys.isEmpty(), "sFastKeyCache populated");
        WallpaperThumbLoader.trimMemory(20);
        check(fastKeys.isEmpty(), "WallpaperThumbLoader.trimMemory(20) clears sFastKeyCache");

        // 3. RubyLyricsView zero-alloc Cell.blank & FontChoice.faceEpoch O(1) guard
        RubyLyricsView.Cell blankSpace = new RubyLyricsView.Cell(0, 10, 10, 0, 0, 1, "   ", "");
        RubyLyricsView.Cell blankEmpty = new RubyLyricsView.Cell(0, 0, 0, 0, 0, 0, "", "");
        RubyLyricsView.Cell nonBlank = new RubyLyricsView.Cell(0, 20, 20, 10, 0, 1, "愛", "ai");
        check(blankSpace.blank && blankEmpty.blank && !nonBlank.blank,
            "Cell.blank precomputes whitespace state without per-frame trim()");

        long selBefore = FontChoice.epoch();
        long faceBefore = FontChoice.faceEpoch();
        Method selectFamily = FontChoice.class.getDeclaredMethod("selectFamily", int.class);
        selectFamily.setAccessible(true);
        selectFamily.invoke(null, 0);
        check(FontChoice.faceEpoch() == faceBefore + 1L, "selectFamily advances FontChoice.faceEpoch()");
        check(FontChoice.epoch() == selBefore, "selectFamily leaves FontChoice.epoch() untouched for FontImport");

        // 4. Single-track lyrics prefetch key parity, cache hit skip, and MemoryTrimmer eviction
        String key = LiveLyrics.buildKey("Idol", "YOASOBI", "THE BOOK", "", "ZRtdQ81jPUQ", 213000L);
        check(!key.isEmpty(), "valid track produces non-empty prefetch key");
        check(LiveLyrics.buildKey("", "YOASOBI", "", "", "", 213000L).isEmpty(), "empty title produces empty key");
        check(LiveLyrics.buildKey("Idol", "", "", "", "", 213000L).isEmpty(), "empty artist produces empty key");
        check(LiveLyrics.buildKey("Idol", "YOASOBI", "", "", "", 500L).isEmpty(), "sub-second duration rejected");

        LiveLyrics keyCheck = new LiveLyrics((d, s) -> {});
        try {
            keyCheck.setTrack("Idol", "YOASOBI", "THE BOOK", "", "ZRtdQ81jPUQ", 213000L);
            check(key.equals(keyCheck.trackKey()), "buildKey matches LiveLyrics.setTrack key 100%");
        } finally {
            keyCheck.close();
        }

        // Seed transient CACHE and verify prefetch deduplication + trimMemory behavior
        Field cacheField = LiveLyrics.class.getDeclaredField("CACHE");
        cacheField.setAccessible(true);
        Map<String, LiveLyrics.Result> cache = (Map<String, LiveLyrics.Result>) cacheField.get(null);
        Lyrics sample = Lyrics.parse("[00:01.00]Line one\n[00:04.00]Line two");
        synchronized (cache) {
            cache.put(key, new LiveLyrics.Result(sample, "ready", 3600000L, true));
        }
        check(LiveLyrics.hasFreshCache(key), "complete cached track reported fresh");
        check(!LiveLyrics.prefetch(null, "Idol", "YOASOBI", "THE BOOK", "", "ZRtdQ81jPUQ", 213000L),
            "prefetch skips network when track is already fresh in CACHE");
        check(!LiveLyrics.prefetch(null, "", "YOASOBI", "", "", "", 213000L),
            "prefetch rejects incomplete track metadata");

        // UI_HIDDEN (level 20) keeps warm RAM cache; BACKGROUND (40) and RUNNING_CRITICAL (15) evict CACHE
        LiveLyrics.trimMemory(20);
        check(LiveLyrics.hasFreshCache(key), "trimMemory(20) retains warm RAM cache across brief app switch");
        LiveLyrics.trimMemory(40);
        check(!LiveLyrics.hasFreshCache(key) && LiveLyrics.cacheSize() == 0,
            "trimMemory(40) evicts LiveLyrics.CACHE under memory pressure");
        synchronized (cache) {
            cache.put(key, new LiveLyrics.Result(sample, "ready", 3600000L, true));
        }
        LiveLyrics.trimMemory(15);
        check(LiveLyrics.cacheSize() == 0, "trimMemory(15) evicts LiveLyrics.CACHE on critical memory");

        System.out.println("FIX145_OPTIMIZE_CHECK_OK cases=" + checks);
    }
}
