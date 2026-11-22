package local.launcherfix;

import android.os.Looper;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Isolated verification for fix.141:
 * 1) Multi-source LyricCandidates (LRCLIB + LrcRed + KuGou) & YouTube/bilingual query cleanup
 * 2) LyricOffsetStore bounds, formatting, and precision
 * 3) GenericMediaBrowser priority ranking & non-player / cooldown deprioritization
 * 4) LiveLyrics.searchRecords strict allowlist
 */
public final class Fix141Check {
    private static int checks;
    private static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();

        // 1. LyricCandidates multi-source parsing & query normalization
        check("Flower Flag".equals(LyricCandidates.searchQueryTitle("【FELT】02. Flower Flag（FELT-003 Flower Flag）[Audio Archives]", "FELT")),
            "candidate search cleans doujin/YouTube upload title");
        check("イナイセカイ".equals(LyricCandidates.searchQueryTitle("イナイセカイ - Inai Sekai", "yuiko")),
            "candidate search collapses bilingual JP - Romaji title");
        check(LyricCandidates.searchQueryTitle("Song (Live) [Official Audio]", "Artist").contains("Live"),
            "candidate search keeps Live version marker");

        JSONObject lrcRed = new JSONObject().put("hits", new JSONArray()
            .put(new JSONObject().put("isrc", "JPP302300335").put("title", "Idol").put("artist", "YOASOBI").put("album", "E-SIDE 3").put("duration", 213.2))
            .put(new JSONObject().put("isrc", "USABC2600002").put("title", "Idol (Live)").put("artist", "YOASOBI").put("duration", 213.0))
            .put(new JSONObject().put("isrc", "../bad").put("title", "Idol").put("artist", "YOASOBI").put("duration", 213.0))
            .put(new JSONObject().put("isrc", "JPP302300339").put("title", "Idol").put("artist", "YOASOBI").put("duration", 260.0)));
        List<LyricCandidates.Candidate> redList = LyricCandidates.parseLrcRed(lrcRed, "Idol (Official Music Video)", "YOASOBI - Topic", 213000L);
        check(redList.size() == 1 && "LrcRed".equals(redList.get(0).provider) && "JPP302300335".equals(redList.get(0).extId) && redList.get(0).words,
            "LrcRed candidate parser keeps valid word-timed match and rejects live/bad-isrc/far-duration");

        JSONObject kg = new JSONObject().put("candidates", new JSONArray()
            .put(new JSONObject().put("id", "94082532").put("accesskey", "ABCDEF0123456789").put("song", "イナイセカイ").put("singer", "yuiko").put("duration", 195))
            .put(new JSONObject().put("id", "163622573").put("accesskey", "1122334455667788").put("song", "イナイセカイ").put("singer", "yuiko").put("duration", 195866))
            .put(new JSONObject().put("id", "bad").put("accesskey", "1122334455667788").put("song", "イナイセカイ").put("singer", "yuiko").put("duration", 195866))
            .put(new JSONObject().put("id", "999").put("accesskey", "1122334455667788").put("song", "Other").put("singer", "yuiko").put("duration", 195866)));
        List<LyricCandidates.Candidate> kgList = LyricCandidates.parseKugou(kg, "イナイセカイ - Inai Sekai", "yuiko", 196000L);
        check(kgList.size() == 2 && "KuGou".equals(kgList.get(0).provider) && kgList.get(0).synced && !kgList.get(0).words,
            "KuGou candidate parser accepts both seconds and ms durations for bilingual track");
        check("94082532".equals(kgList.get(0).extId) && "163622573".equals(kgList.get(1).extId),
            "KuGou candidates keep distinct IDs and keys");

        // 2. LiveLyrics.searchRecords URL allowlist
        LiveLyrics live = new LiveLyrics((d, s) -> {});
        try {
            for (String badUrl : new String[]{
                "https://evil.example/search?q=1",
                "https://lrc.red/s/../secret.ttml",
                "https://lyrics.kugou.com/download?id=bad&accesskey=123",
                "http://lrclib.net/api/search?track_name=Song"
            }) {
                try {
                    live.searchRecords(badUrl, android.os.SystemClock.elapsedRealtime() + 100L);
                    throw new AssertionError("Accepted disallowed searchRecords URL: " + badUrl);
                } catch (IllegalArgumentException expected) { checks++; }
            }
        } finally {
            live.close();
        }

        // 3. LyricOffsetStore bounds & formatting
        check(LyricOffsetStore.clamp(0L) == 0L, "zero offset unchanged");
        check(LyricOffsetStore.clamp(250L) == 250L && LyricOffsetStore.clamp(-750L) == -750L, "quarter-second offsets preserved");
        check(LyricOffsetStore.clamp(99999L) == LyricOffsetStore.MAX_MS && LyricOffsetStore.clamp(-99999L) == LyricOffsetStore.MIN_MS,
            "offsets clamped to [-5000, +5000]");
        check("0.0s".equals(LyricOffsetStore.formatDelta(0L)), "format zero delta");
        check("+0.25s".equals(LyricOffsetStore.formatDelta(250L)), "format +0.25s");
        check("+0.5s".equals(LyricOffsetStore.formatDelta(500L)), "format +0.5s without trailing zero");
        check("-1.25s".equals(LyricOffsetStore.formatDelta(-1250L)), "format -1.25s");
        check("Nhịp".equals(LyricOffsetStore.formatPill(0L, true)) && "Sync".equals(LyricOffsetStore.formatPill(0L, false)),
            "pill label at zero offset");
        check("Nhịp +0.5s".equals(LyricOffsetStore.formatPill(500L, true)) && "Sync -0.25s".equals(LyricOffsetStore.formatPill(-250L, false)),
            "pill label with non-zero offset");
        check(LyricOffsetStore.get(null, "Song", "Artist", 180000L) == 0L, "null context safe read");
        check(LyricOffsetStore.put(null, "Song", "Artist", 180000L, 500L) == 500L, "null context safe clamped write");

        // 4. GenericMediaBrowser prioritization & non-player deprioritization
        check(GenericMediaBrowser.looksLikeMediaPlayer("com.music.bitchord.tv"), "BitChord recognized as media player");
        check(GenericMediaBrowser.looksLikeMediaPlayer("org.videolan.vlc"), "VLC recognized as media player");
        check(!GenericMediaBrowser.looksLikeMediaPlayer("com.cxinventor.file.explorer"), "CX File Explorer is not prioritized as music player");
        List<String> recent = Arrays.asList("com.music.bitchord.tv", "org.smarttube.beta");
        int pLast = GenericMediaBrowser.packagePriority("com.music.bitchord.tv", "com.music.bitchord.tv", recent, false);
        int pRecent = GenericMediaBrowser.packagePriority("org.smarttube.beta", "com.music.bitchord.tv", recent, false);
        int pMusic = GenericMediaBrowser.packagePriority("com.other.musicplayer", "com.music.bitchord.tv", recent, false);
        int pOther = GenericMediaBrowser.packagePriority("com.cxinventor.file.explorer", "com.music.bitchord.tv", recent, false);
        int pCooled = GenericMediaBrowser.packagePriority("com.music.bitchord.tv", "com.music.bitchord.tv", recent, true);
        check(pLast < pRecent && pRecent < pMusic && pMusic < pOther && pOther < pCooled,
            "priority order: lastConnected < recent < musicPlayer < other < cooledDown");
        check(GenericMediaBrowser.packagePriority("com.music.bitchord.tv", "", Collections.emptyList(), false)
            < GenericMediaBrowser.packagePriority("com.cxinventor.file.explorer", "", Collections.emptyList(), false),
            "even on cold boot BitChord ranks ahead of CX File Explorer");

        System.out.println("FIX141_CHECK_OK cases=" + checks);
    }
}
