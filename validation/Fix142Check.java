package local.launcherfix;

import android.media.session.PlaybackState;
import android.os.Looper;

/**
 * Isolated verification for fix.142:
 * 1) +/-10s seekTo support and bounds clamping across MediaSources & MochyMediaBrowser.canControl
 * 2) Persistent multi-provider LyricChoiceStore (LRCLIB + LrcRed + KuGou) and LiveLyrics.selectedKey
 * 3) Removal of unused KaraokeTextView dead code
 */
public final class Fix142Check {
    private static int checks;
    private static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();

        // 1. Seek policy and clamping
        check(MediaSources.supports(256L, 89, true) && MediaSources.supports(256L, 90, false), "ACTION_SEEK_TO enables rewind/fast-forward");
        check(!MediaSources.supports(566L & ~256L, 89, true) && !MediaSources.supports(566L & ~256L, 90, true), "without ACTION_SEEK_TO seek rejected");
        check(!MediaSources.supports(Long.MAX_VALUE, 26, true), "arbitrary key still rejected");

        // Playing at pos=20s updated at t=10s, now=12s -> current=22s -> +10s=32s, -10s=12s
        check(MediaSources.seekTarget(20_000L, 10_000L, 1f, 3, 200_000L, 12_000L, 90) == 32_000L, "playing +10s advances from live clock");
        check(MediaSources.seekTarget(20_000L, 10_000L, 1f, 3, 200_000L, 12_000L, 89) == 12_000L, "playing -10s rewinds from live clock");
        // Paused at pos=5s -> -10s clamps to 0; near end 195s/200s -> +10s clamps to 200s
        check(MediaSources.seekTarget(5_000L, 10_000L, 0f, 2, 200_000L, 50_000L, 89) == 0L, "rewind clamps to 0");
        check(MediaSources.seekTarget(195_000L, 10_000L, 0f, 2, 200_000L, 50_000L, 90) == 200_000L, "fast-forward clamps to duration");
        check(MediaSources.seekTarget(-1L, 10_000L, 1f, 3, 200_000L, 12_000L, 90) == -1L, "unknown position rejects seek");
        check(MediaSources.seekTarget(20_000L, 10_000L, 1f, 3, 200_000L, 12_000L, 85) == -1L, "non-seek key returns -1");

        String sid = "0123456789abcdef0123456789abcdef";
        PlaybackState seekable = new PlaybackState.Builder().setState(3, 20_000L, 1f, 10_000L).setActions(256L | 512L).build();
        PlaybackState nonSeekable = new PlaybackState.Builder().setState(3, 20_000L, 1f, 10_000L).setActions(512L).build();
        check(MochyMediaBrowser.canControl(sid, sid, seekable, 89) && MochyMediaBrowser.canControl(sid, sid, seekable, 90),
            "canControl permits seek when ACTION_SEEK_TO advertised");
        check(!MochyMediaBrowser.canControl(sid, sid, nonSeekable, 89) && !MochyMediaBrowser.canControl(sid, sid, nonSeekable, 90),
            "canControl blocks seek when ACTION_SEEK_TO absent");

        // 2. Persistent multi-provider LyricChoiceStore (LrcRed & KuGou & LRCLIB)
        PersistenceAndRecentCheck.FakeContext ctx = new PersistenceAndRecentCheck.FakeContext();
        LyricChoiceStore.resetForTests(ctx);

        LyricChoiceStore.Choice red = LyricChoiceStore.Choice.lrcRed("jpp302300335");
        check(red != null && "LrcRed:JPP302300335".equals(red.key()), "LrcRed choice normalizes ISRC");
        check(LyricChoiceStore.Choice.lrcRed("../bad") == null, "malformed ISRC rejected");
        LyricChoiceStore.putChoice(ctx, "Idol", "YOASOBI", 213_000L, red);
        LyricChoiceStore.reloadForTests(ctx);
        LyricChoiceStore.Choice redLoaded = LyricChoiceStore.getChoice(ctx, "Idol", "YOASOBI", 214_200L);
        check(redLoaded != null && "LrcRed:JPP302300335".equals(redLoaded.key()), "LrcRed choice persists across reload with +/-2s tolerance");
        check(LyricChoiceStore.get(ctx, "Idol", "YOASOBI", 213_000L) == 0L, "legacy LRCLIB ID getter stays 0 for LrcRed choice");

        LyricChoiceStore.Choice kg = LyricChoiceStore.Choice.kugou("94082532", "ABCDEF0123456789");
        check(kg != null && "KuGou:94082532".equals(kg.key()), "KuGou choice valid");
        check(LyricChoiceStore.Choice.kugou("bad", "ABCDEF0123456789") == null, "malformed KuGou ID rejected");
        LyricChoiceStore.putChoice(ctx, "イナイセカイ", "yuiko", 196_000L, kg);
        LyricChoiceStore.reloadForTests(ctx);
        LyricChoiceStore.Choice kgLoaded = LyricChoiceStore.getChoice(ctx, "イナイセカイ", "yuiko", 195_500L);
        check(kgLoaded != null && "KuGou".equals(kgLoaded.provider) && "94082532".equals(kgLoaded.extId) && "ABCDEF0123456789".equals(kgLoaded.extKey),
            "KuGou choice persists across reload");

        // LiveLyrics.choose with multi-provider Choice
        LiveLyrics live = new LiveLyrics(ctx, (d, s) -> {});
        try {
            live.setTrack("Idol", "YOASOBI", 213_000L);
            check(live.isManual() && "LrcRed:JPP302300335".equals(live.selectedKey()), "LiveLyrics recognizes persisted LrcRed manual choice");
            Lyrics sample = Lyrics.parse("[00:01]Line one").fromSource("KuGou");
            check(live.choose(live.trackKey(), sample, kg), "LiveLyrics accepts KuGou manual choice");
            check(live.isManual() && "KuGou:94082532".equals(live.selectedKey()), "LiveLyrics updates selectedKey to KuGou");
            check(live.choose(live.trackKey(), null) && !live.isManual() && live.selectedKey().isEmpty(), "Automatic clears persisted multi-provider choice");
        } finally {
            live.close();
        }

        // 3. Dead code KaraokeTextView removed
        boolean removed = false;
        try {
            Class.forName("local.launcherfix.KaraokeTextView");
        } catch (ClassNotFoundException expected) {
            removed = true;
        }
        check(removed, "unused KaraokeTextView removed from production dex");

        System.out.println("FIX142_CHECK_OK cases=" + checks);
    }
}
