package local.launcherfix;

import android.os.Looper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Isolated ART verification for fix.144 lyric accuracy upgrades:
 * 1) YouTube / Japanese corner quotes (「...」/『...』), "Artist - Title" prefix, channel suffix stripping
 * 2) Multi-factor confidence scoring (AggregateLyrics.best quality arbitration)
 * 3) Truncated short-cut / TV-Size lyric rejection on full-length songs
 * 4) Two-tier conditional MV intro/outro duration allowance (+/-2s strict -> +/-10s MV fallback)
 */
public final class Fix144AccuracyCheck {
    private static int checks;

    private static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        checks++;
    }

    private static String buildLrc(int lines, long startMs, long stepMs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            long ms = startMs + i * stepMs;
            long min = ms / 60000L;
            long sec = (ms % 60000L) / 1000L;
            long rem = ms % 1000L;
            sb.append(String.format(java.util.Locale.ROOT, "[%02d:%02d.%03d]Line %d\n", min, sec, rem, i + 1));
        }
        return sb.toString();
    }

    private static String buildTtmlNoDur(int lines, long startMs, long stepMs) {
        StringBuilder sb = new StringBuilder("<tt xmlns='http://www.w3.org/ns/ttml'><body><div>");
        for (int i = 0; i < lines; i++) {
            long b = startMs + i * stepMs;
            long e = b + Math.max(1000L, stepMs - 500L);
            sb.append("<p begin='").append(b).append("ms' end='").append(e).append("ms'>")
              .append("<span begin='").append(b).append("ms' end='").append(e).append("ms'>Word")
              .append(i + 1).append("</span></p>");
        }
        sb.append("</div></body></tt>");
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        LyricMatch match = new LyricMatch();

        // 1. Japanese corner quotes, "Artist - Title", unbracketed MV suffix, channel suffixes
        check("アイドル".equals(LyricMatch.searchTitle("YOASOBI「アイドル」Official Music Video", "YOASOBI - Topic")),
            "unwrap YOASOBI「アイドル」Official Music Video");
        check("怪獣の花唄".equals(LyricMatch.searchTitle("『怪獣の花唄』- Vaundy (MUSIC VIDEO)", "Vaundy Official Channel")),
            "unwrap 『怪獣の花唄』- Vaundy (MUSIC VIDEO)");
        check("Chúng Ta Của Hiện Tại".equals(LyricMatch.searchTitle(
            "Sơn Tùng M-TP - Chúng Ta Của Hiện Tại (Official Music Video)", "Sơn Tùng M-TP Official")),
            "strip Artist - Title prefix and Official channel suffix");
        check("Official髭男dism".equals(LyricMatch.primaryArtist("Official髭男dism")),
            "never strip leading Official from Official髭男dism");
        check("Subtitle".equals(LyricMatch.searchTitle("Official髭男dism - Subtitle [Official Video]", "Official髭男dism")),
            "strip Official髭男dism prefix cleanly");
        check(match.artist("TaylorSwiftVEVO", "Taylor Swift"), "VEVO channel suffix matches canonical artist");
        check(match.artist("Ado Official YouTube Channel", "Ado"), "Official YouTube Channel suffix matches artist");
        check(match.title("YOASOBI「アイドル」Official Music Video", "YOASOBI - Topic", "アイドル"),
            "corner-quoted MV title matches catalogue title");
        check(match.title("Sơn Tùng M-TP - Chúng Ta Của Hiện Tại [Vietsub]", "Sơn Tùng M-TP Official", "Chúng Ta Của Hiện Tại"),
            "Vietnamese MV title with artist prefix and Vietsub tag matches catalogue");

        // Version guard & unrelated artist safety
        String tvSizeClean = LyricMatch.searchTitle("YOASOBI「アイドル」(TV Size) Official Music Video", "YOASOBI");
        check(tvSizeClean.contains("TV Size") && !match.title("YOASOBI「アイドル」(TV Size) Official Music Video", "YOASOBI", "アイドル"),
            "corner-quoted TV Size preserves version guard and rejects full title");
        check("OtherArtist「アイドル」".equals(LyricMatch.searchTitle("OtherArtist「アイドル」", "YOASOBI")),
            "unrelated artist before corner quotes is not stripped");

        // 2. Multi-factor confidence scoring & AggregateLyrics.best arbitration
        Lyrics driftedWord = ProviderLyrics.ttml(buildTtmlNoDur(16, 5000L, 12000L), 219_000L, true)
            .fromSource("Better Lyrics TTML");
        driftedWord = driftedWord.withQuality(LyricMatch.computeQuality(driftedWord, 219_000L, 213_000L, false));
        Lyrics exactIsrcWord = ProviderLyrics.ttml(buildTtmlNoDur(16, 5000L, 12000L), 219_000L, true)
            .fromSource("LrcRed");
        exactIsrcWord = exactIsrcWord.withQuality(LyricMatch.computeQuality(exactIsrcWord, 219_000L, 218_800L, true));
        check(exactIsrcWord.quality() >= driftedWord.quality() + 300,
            "ISRC exact-duration word lyric scores well above drifted fuzzy match: "
                + exactIsrcWord.quality() + " vs " + driftedWord.quality());
        check(AggregateLyrics.best(driftedWord, exactIsrcWord) == exactIsrcWord,
            "later ISRC exact word lyric upgrades earlier drifted word lyric");

        Lyrics exactLine = Lyrics.parse(buildLrc(20, 4000L, 9500L)).fromSource("LRCLIB");
        exactLine = exactLine.withQuality(LyricMatch.computeQuality(exactLine, 219_000L, 218_900L, false));
        Lyrics driftedLine = Lyrics.parse(buildLrc(20, 4000L, 9500L)).fromSource("KuGou");
        driftedLine = driftedLine.withQuality(LyricMatch.computeQuality(driftedLine, 219_000L, 212_000L, false));
        check(AggregateLyrics.best(driftedLine, exactLine) == exactLine,
            "exact-duration line lyric upgrades earlier drifted line lyric");
        check(AggregateLyrics.best(exactLine, driftedWord) == driftedWord,
            "word timing still outranks line timing regardless of quality score");
        Lyrics withRomaji = exactIsrcWord.withRomaji(new String[exactIsrcWord.size()]);
        check(withRomaji.quality() == exactIsrcWord.quality(), "withRomaji preserves quality score");

        // 3. Truncated TV-Size / TikTok short-cut rejection on full songs (240s)
        String tvSizeTtml = buildTtmlNoDur(10, 5000L, 8000L); // ends at ~84.5s on a 240s track (35%)
        boolean ttmlRejected = false;
        try {
            ProviderLyrics.ttml(tvSizeTtml, 240_000L);
        } catch (IllegalArgumentException expected) {
            ttmlRejected = true;
        }
        check(ttmlRejected, "TTML without <body dur> ending at 85s rejected on 240s full song");
        // Same 85s TTML MUST be accepted on an actual 90s TV-size track!
        check(ProviderLyrics.ttml(tvSizeTtml, 90_000L).size() == 10,
            "85s TTML accepted on actual 90s TV-Size track");
        // Real 240s song with long 50s instrumental outro (ends at 190s = 79%) MUST be accepted!
        String longOutroTtml = buildTtmlNoDur(12, 10000L, 15500L); // ends ~195s
        check(ProviderLyrics.ttml(longOutroTtml, 240_000L).size() == 12,
            "full song with long instrumental outro accepted");

        String tvSizeLrc = buildLrc(10, 5000L, 8000L); // last start 77s on 240s full track
        JSONObject kgTruncDl = new JSONObject().put("content",
            Base64.getEncoder().encodeToString(tvSizeLrc.getBytes(StandardCharsets.UTF_8)));
        check(ExtraLyricSources.parseKugouDownload(kgTruncDl, 240_000L) == null,
            "KuGou download rejects truncated 77s LRC on 240s full track");
        JSONObject lrcTruncRec = new JSONObject()
            .put("trackName", "Full Song").put("artistName", "Artist").put("duration", 240.0)
            .put("syncedLyrics", tvSizeLrc).put("plainLyrics", "Should not leak as plain");
        check(LiveLyrics.parse(lrcTruncRec, "Full Song", "Artist", 240_000L).lyrics == null,
            "LRCLIB parse rejects truncated synced LRC without leaking truncated plain");

        // 4. Two-tier conditional MV duration tolerance (+/-2s strict -> +/-10s MV fallback)
        // 4a. LrcRed search: MV is 219s, studio master ISRC is 213.4s (delta = 5.6s)
        JSONObject mvLrcRed = new JSONObject().put("hits", new JSONArray()
            .put(new JSONObject().put("isrc", "JPP302300335").put("title", "アイドル").put("artist", "YOASOBI").put("duration", 213.4)));
        check("JPP302300335".equals(ExtraLyricSources.parseLrcRedSearch(
            mvLrcRed, "YOASOBI「アイドル」Official Music Video", "YOASOBI - Topic", 219_000L, match)),
            "LrcRed rescues studio ISRC for MV with 5.6s intro/outro drift");

        // Strict +/-3s match MUST beat +/-5.6s MV match when both exist!
        JSONObject bothLrcRed = new JSONObject().put("hits", new JSONArray()
            .put(new JSONObject().put("isrc", "JPP302300335").put("title", "アイドル").put("artist", "YOASOBI").put("duration", 213.4))
            .put(new JSONObject().put("isrc", "JPP302300999").put("title", "アイドル").put("artist", "YOASOBI").put("duration", 218.5)));
        check("JPP302300999".equals(ExtraLyricSources.parseLrcRedSearch(
            bothLrcRed, "YOASOBI「アイドル」Official Music Video", "YOASOBI - Topic", 219_000L, match)),
            "strict duration match always beats MV-fallback match");

        // Version keyword disables MV fallback; >10s drift rejected; short track (<120s) disables MV fallback
        check(!match.recordMvFallback("Idol (Live)", "YOASOBI", 219_000L, "Idol (Live)", "YOASOBI", 213.4),
            "version word disables MV drift fallback");
        check(!match.recordMvFallback("Idol", "YOASOBI", 219_000L, "Idol", "YOASOBI", 205.0),
            "drift >10s (14s) rejected");
        check(!match.recordMvFallback("Short", "Artist", 90_000L, "Short", "Artist", 84.0),
            "tracks under 120s never use MV drift fallback");

        // 4b. ProviderLyrics.ttml exactRecording accepts studio <body dur='213.4'> for 219s MV
        String studioTtml = "<tt xmlns='http://www.w3.org/ns/ttml'><body dur='213.4'><div>"
            + "<p begin='5s' end='10s'><span begin='5s' end='10s'>A</span></p>"
            + "<p begin='180s' end='195s'><span begin='180s' end='195s'>B</span></p>"
            + "</div></body></tt>";
        check(ProviderLyrics.ttml(studioTtml, 219_000L, true).size() == 2,
            "ISRC-verified studio TTML (213.4s) accepted for 219s MV");

        // 4c. LRCLIB search MV fallback rescues studio LRC for 219s MV
        String fullLrc = buildLrc(15, 5000L, 13000L); // ends ~187s
        JSONObject lrcMvResults = new JSONObject().put("results", new JSONArray()
            .put(new JSONObject().put("trackName", "アイドル").put("artistName", "YOASOBI")
                .put("duration", 213.4).put("syncedLyrics", fullLrc)));
        LiveLyrics.Result mvLrcPick = LyricSources.quickSearch(
            lrcMvResults, "YOASOBI「アイドル」Official Music Video", "YOASOBI - Topic", 219_000L);
        check(mvLrcPick.lyrics != null && mvLrcPick.lyrics.isSynced() && mvLrcPick.lyrics.quality() > 0,
            "LRCLIB search rescues studio LRC for 219s MV with quality score");

        // 4d. LyricsPlus MV fallback rescues studio syllable lyrics for 219s MV
        JSONObject lpMv = new JSONObject()
            .put("metadata", new JSONObject().put("source", "Apple Music").put("title", "アイドル")
                .put("artist", "YOASOBI").put("totalDuration", "3:33.400"))
            .put("lyrics", new JSONArray()
                .put(new JSONObject().put("time", 10000).put("duration", 2000).put("text", "無敵の笑顔")
                    .put("syllabus", new JSONArray()
                        .put(new JSONObject().put("time", 10000).put("duration", 1000).put("text", "無敵の"))
                        .put(new JSONObject().put("time", 11000).put("duration", 1000).put("text", "笑顔")))));
        Lyrics lpMvParsed = ExtraLyricSources.parseLyricsPlus(
            lpMv, "YOASOBI「アイドル」Official Music Video", "YOASOBI - Topic", 219_000L, match);
        check(lpMvParsed != null && lpMvParsed.hasWords() && lpMvParsed.quality() > 0,
            "LyricsPlus rescues studio syllable lyrics for 219s MV");

        System.out.println("FIX144_ACCURACY_CHECK_OK cases=" + checks
            + " isrc_q=" + exactIsrcWord.quality()
            + " drift_q=" + driftedWord.quality());
    }
}
