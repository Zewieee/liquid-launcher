package local.launcherfix;

import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Isolated verification for fix.140:
 * 1) Direct live lyric line extraction from public MediaMetadata DISPLAY_SUBTITLE & bridge v2 packet
 * 2) Artwork revision stability across 500ms subtitle lyric ticks
 * 3) AppLiveLyrics rolling history, cap, reset, and async Japanese ruby romaji
 * 4) YouTube/doujin title cleanup with strict version-word preservation
 * 5) Keyless parsers for LyricsPlus, LrcRed search, KuGou direct, and YouTube Music Lyrics tab
 */
public final class Fix140LyricsCheck {
    private static final String PKG = "com.music.bitchord.tv";
    private static final String VID = "kAdNwmQvIbY";
    private static final String SESSION = "0123456789abcdef0123456789abcdef";
    private static int checks;

    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checks++;
    }

    private static SpotifyMetadata.Response parsePacket(JSONObject info) throws Exception {
        byte[] json = info.toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0x4c4d4231);
        out.writeInt(json.length);
        out.write(json);
        out.writeInt(0);
        return SpotifyMetadata.parse(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), "");
    }

    private static JSONObject basePacket() throws Exception {
        return new JSONObject().put("version", 2).put("source", PKG).put("sessionId", SESSION)
            .put("sources", new JSONArray().put(new JSONObject().put("id", SESSION).put("package", PKG)
                .put("label", "BitChord TV").put("state", 3)))
            .put("title", "Flower Flag").put("artist", "FELT").put("album", "")
            .put("revision", SESSION + SESSION).put("spotifyId", "").put("youtubeId", VID)
            .put("playback", new JSONObject().put("duration", 295000).put("position", 12000)
                .put("updated", 10000).put("speed", 1).put("state", 3).put("actions", 7340029));
    }

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        final Handler main = new Handler(Looper.getMainLooper());

        // 1. Live lyric subtitle extraction
        check("遠い空の下で".equals(MediaSources.extractLiveLyric("♪ 遠い空の下で")), "BitChord note prefix extracted");
        check("Hello world".equals(MediaSources.extractLiveLyric("  ♫   Hello\tworld  ")), "beamed note and whitespace normalized");
        check("La la".equals(MediaSources.extractLiveLyric("🎵 La la")), "emoji note prefix extracted");
        check("".equals(MediaSources.extractLiveLyric("FELT")), "artist-only subtitle is not a live lyric");
        check("".equals(MediaSources.extractLiveLyric("♪   ")), "blank note-only gap ignored");
        check("".equals(MediaSources.extractLiveLyric("♪ bad\u202Etext")), "bidi override rejected");
        check(MediaSources.extractLiveLyric("♪ " + String.join("", Collections.nCopies(600, "a"))).length() == 512, "live lyric capped at 512");

        // 2. Direct MediaBrowser snapshot + artwork revision stability across subtitle ticks
        Bitmap art = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888);
        art.eraseColor(0xff204060);
        PlaybackState playing = new PlaybackState.Builder().setState(3, 12000, 1f, 10000).setActions(7340029).build();
        MediaMetadata tick1Meta = new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Flower Flag")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "FELT")
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, "♪ 遠い空の下で")
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, VID)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 295000)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
            .build();
        SpotifyMetadata.Response snap1 = MochyMediaBrowser.snapshot(tick1Meta, playing, SESSION, "", 256, PKG, "BitChord TV");
        check(snap1 != null && "FELT".equals(snap1.artist) && "遠い空の下で".equals(snap1.liveLyric), "direct snapshot extracts liveLyric alongside artist");
        check(snap1.art != null && !snap1.unchanged, "first snapshot decodes cover art");

        MediaMetadata tick2Meta = new MediaMetadata.Builder(tick1Meta)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, "♪ 花びらが舞う")
            .build();
        SpotifyMetadata.Response snap2 = MochyMediaBrowser.snapshot(tick2Meta, playing, SESSION, snap1.revision, 256, PKG, "BitChord TV");
        check(snap2 != null && snap2.revision.equals(snap1.revision) && snap2.unchanged && snap2.art == null,
            "subtitle lyric tick preserves artwork revision without reallocating bitmap");
        check("花びらが舞う".equals(snap2.liveLyric), "second tick carries updated live lyric");

        MediaMetadata noArtistLive = new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Radio Track")
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, "♪ Live line only")
            .build();
        SpotifyMetadata.Response snapNoArtist = MochyMediaBrowser.snapshot(noArtistLive, playing, SESSION, "", 96, PKG, "BitChord TV");
        check(snapNoArtist != null && snapNoArtist.artist.isEmpty() && "Live line only".equals(snapNoArtist.liveLyric),
            "lyric subtitle does not pollute empty artist");
        snap1.art.recycle();
        art.recycle();

        // 3. Bridge v2 packet parser liveLyric contract
        SpotifyMetadata.Response parsedLive = parsePacket(basePacket().put("liveLyric", "遠い空の下で"));
        check("遠い空の下で".equals(parsedLive.liveLyric), "bridge v2 packet carries liveLyric");
        check(parsePacket(basePacket()).liveLyric.isEmpty(), "bridge v2 packet without liveLyric stays compatible");
        try { parsePacket(basePacket().put("liveLyric", 123)); throw new AssertionError("non-string liveLyric accepted"); }
        catch (IOException expected) { checks++; }
        try { parsePacket(basePacket().put("liveLyric", String.join("", Collections.nCopies(513, "a")))); throw new AssertionError("oversized liveLyric accepted"); }
        catch (IOException expected) { checks++; }
        try { parsePacket(basePacket().put("liveLyric", "bad\u202Ebidi")); throw new AssertionError("bidi liveLyric accepted"); }
        catch (IOException expected) { checks++; }

        // 4. YouTube / Doujin title cleanup & strict version preservation
        LyricMatch match = new LyricMatch();
        check("Idol".equals(LyricMatch.cleanTitle("Idol (Official Music Video)")), "strip official music video");
        check("Dracula".equals(LyricMatch.cleanTitle("Dracula (feat. JENNIE)")), "strip feat credit");
        check("Flower Flag".equals(LyricMatch.searchTitle("【FELT】02. Flower Flag（FELT-003 Flower Flag）[Audio Archives]", "FELT")),
            "strip matching artist bracket, track number, catalog code, and audio archives");
        check("【Other】02. Real Title".equals(LyricMatch.searchTitle("【Other】02. Real Title", "FELT")),
            "do not strip unrelated leading bracket");
        check(!match.title("Song (Live) [Official Video]", "Artist", "Song"), "live version guard preserved");
        check(!match.title("Song (Remix) [HD]", "Artist", "Song"), "remix version guard preserved");
        check(!match.title("Song - TV Size [Official Audio]", "Artist", "Song"), "TV size version guard preserved");
        check(match.title("【FELT】02. Flower Flag（FELT-003 Flower Flag）[Audio Archives]", "FELT", "Flower Flag"),
            "doujin YouTube upload matches clean catalogue title");
        check("イナイセカイ".equals(match.dedupeBilingualTitle("イナイセカイ - Inai Sekai")),
            "bilingual JP - Romaji title collapses to canonical JP title");
        check(match.title("イナイセカイ - Inai Sekai", "yuiko", "イナイセカイ"),
            "bilingual JP - Romaji matches single-script catalogue title");
        check("Song - Live".equals(match.dedupeBilingualTitle("Song - Live")),
            "bilingual dedupe never strips version suffix");
        check(match.artist("YOASOBI - Topic", "YOASOBI"), "Topic channel artist matches catalogue artist");
        check(!match.artist("YOASOBI - Topic", "Other Artist"), "different artist rejected");

        // 5. Keyless ExtraLyricSources parsers
        JSONObject lpJson = new JSONObject()
            .put("type", "Word")
            .put("metadata", new JSONObject().put("source", "QQ Music").put("title", "Idol").put("artist", "YOASOBI").put("totalDuration", "3:33.000"))
            .put("lyrics", new JSONArray()
                .put(new JSONObject().put("time", 659).put("duration", 2000).put("text", "Hello world")
                    .put("syllabus", new JSONArray()
                        .put(new JSONObject().put("time", 659).put("duration", 400).put("text", "Hello "))
                        .put(new JSONObject().put("time", 1060).put("duration", 600).put("text", "world")))));
        Lyrics lpParsed = ExtraLyricSources.parseLyricsPlus(lpJson, "Idol (Official MV)", "YOASOBI - Topic", 213000L, match);
        check(lpParsed != null && lpParsed.hasWords() && lpParsed.source().equals("LyricsPlus · QQ Music"), "LyricsPlus syllable timing parsed");
        check("Hello world".equals(lpParsed.at(0)) && lpParsed.coverageAt(0, 859, false) == 2.5, "LyricsPlus word sweep coverage accurate");
        check(ExtraLyricSources.parseLyricsPlus(lpJson, "Other Song", "YOASOBI", 213000L, match) == null, "LyricsPlus rejects wrong title");
        check(ExtraLyricSources.parseLyricsPlus(lpJson, "Idol", "YOASOBI", 180000L, match) == null, "LyricsPlus rejects wrong duration");

        JSONObject lrcRedSearch = new JSONObject().put("hits", new JSONArray()
            .put(new JSONObject().put("isrc", "USABC2600002").put("title", "Idol (Live)").put("artist", "YOASOBI").put("duration", 213.0))
            .put(new JSONObject().put("isrc", "JPP302300335").put("title", "Idol").put("artist", "YOASOBI").put("duration", 213.4)));
        check("JPP302300335".equals(ExtraLyricSources.parseLrcRedSearch(lrcRedSearch, "Idol", "YOASOBI - Topic", 213000L, match)),
            "LrcRed search skips live variant and picks matching ISRC");
        check("https://lrc.red/s/JPP302300335.ttml".equals(ExtraLyricSources.lrcRedDocUrl("JPP302300335")), "LrcRed doc URL");
        check(ExtraLyricSources.lrcRedDocUrl("../evil").isEmpty(), "LrcRed rejects malformed ISRC");
        ExtraLyricSources.rememberIsrc(VID, "JPP302300335");
        check("JPP302300335".equals(ExtraLyricSources.cachedIsrc(VID)), "ISRC LRU remembers valid video mapping");

        JSONObject kgSearch = new JSONObject().put("candidates", new JSONArray()
            .put(new JSONObject().put("id", "94082532").put("accesskey", "ABCDEF0123456789").put("song", "イナイセカイ").put("singer", "yuiko").put("duration", 195).put("score", 60))
            .put(new JSONObject().put("id", "12345").put("accesskey", "ABCDEF0123456789").put("song", "Idol").put("singer", "YOASOBI").put("duration", 213000).put("score", 60))
            .put(new JSONObject().put("id", "99999").put("accesskey", "BADKEY!").put("song", "Idol").put("singer", "YOASOBI").put("duration", 213000).put("score", 99)));
        String[] kgPick = ExtraLyricSources.parseKugouCandidate(kgSearch, "Idol", "YOASOBI", 213000L, match);
        check(kgPick != null && "12345".equals(kgPick[0]), "KuGou candidate validation rejects malformed accesskey");
        String[] kgSecPick = ExtraLyricSources.parseKugouCandidate(kgSearch, "イナイセカイ - Inai Sekai", "yuiko", 196000L, match);
        check(kgSecPick != null && "94082532".equals(kgSecPick[0]), "KuGou accepts seconds-encoded duration and bilingual title");
        String kgRawLrc = "[00:00.10]作词：Ayase\n[00:01.00]無敵の笑顔で荒らすメディア\n[00:05.00]知りたいその秘密";
        JSONObject kgDl = new JSONObject().put("content", Base64.getEncoder().encodeToString(kgRawLrc.getBytes(StandardCharsets.UTF_8)));
        Lyrics kgLyrics = ExtraLyricSources.parseKugouDownload(kgDl, 213000L);
        check(kgLyrics != null && kgLyrics.size() == 2 && "無敵の笑顔で荒らすメディア".equals(kgLyrics.at(0)) && "KuGou".equals(kgLyrics.source()),
            "KuGou download strips credit line and parses timed LRC");
        String kgInst = "[00:00.00]纯音乐，请欣赏";
        check(ExtraLyricSources.parseKugouDownload(new JSONObject().put("content", Base64.getEncoder().encodeToString(kgInst.getBytes(StandardCharsets.UTF_8))), 213000L) == null,
            "KuGou instrumental notice rejected");

        JSONObject ytmNext = new JSONObject().put("contents", new JSONObject().put("tabs", new JSONArray()
            .put(new JSONObject().put("tabRenderer", new JSONObject().put("title", "Up next")))
            .put(new JSONObject().put("tabRenderer", new JSONObject().put("title", "Lyrics")
                .put("endpoint", new JSONObject().put("browseEndpoint", new JSONObject().put("browseId", "MPLYt_fR152IsQEld")))))));
        String[] ytmEp = ExtraLyricSources.extractYouTubeLyricsEndpoint(ytmNext);
        check(ytmEp != null && "MPLYt_fR152IsQEld".equals(ytmEp[0]), "YouTube Music Lyrics tab browseId extracted");
        JSONObject ytmBadNext = new JSONObject().put("tabRenderer", new JSONObject().put("title", "Lyrics")
            .put("endpoint", new JSONObject().put("browseEndpoint", new JSONObject().put("browseId", "FEwhat_to_watch"))));
        check(ExtraLyricSources.extractYouTubeLyricsEndpoint(ytmBadNext) == null, "non-lyrics browseId rejected");
        JSONObject ytmBrowse = new JSONObject().put("contents", new JSONObject().put("musicDescriptionShelfRenderer",
            new JSONObject().put("description", new JSONObject().put("runs", new JSONArray()
                .put(new JSONObject().put("text", "Line one\nLine two"))))));
        Lyrics ytmPlain = ExtraLyricSources.parseYouTubeMusicLyrics(ytmBrowse);
        check(ytmPlain != null && !ytmPlain.isSynced() && !ytmPlain.hasWords() && ytmPlain.size() == 2 && "YouTube Music".equals(ytmPlain.source()),
            "YouTube Music plain lyrics parsed cleanly");

        // 6. AppLiveLyrics rolling history, cap, reset, and async Japanese ruby romaji
        final Lyrics[] latest = {null};
        final int[] latestIdx = {-1};
        final int[] appends = {0};
        final CountDownLatch rubyReady = new CountDownLatch(1);
        final AppLiveLyrics live = new AppLiveLyrics(true, (data, idx, appended) -> {
            latest[0] = data;
            latestIdx[0] = idx;
            if (appended) appends[0]++;
            if (data != null && idx >= 0 && data.rubiesAt(idx) != null && data.rubiesAt(idx).length > 0) {
                rubyReady.countDown();
            }
        });
        live.update(true, PKG, "BitChord TV", "Flower Flag", "FELT", VID, 295000L, "Line 1");
        live.update(true, PKG, "BitChord TV", "Flower Flag", "FELT", VID, 295000L, "Line 1"); // consecutive dup
        live.update(true, PKG, "BitChord TV", "Flower Flag", "FELT", VID, 295000L, "Line 2");
        live.update(true, PKG, "BitChord TV", "Flower Flag", "FELT", VID, 295000L, "Line 1"); // chorus repeat preserved
        check(appends[0] == 3 && latest[0].size() == 3 && latestIdx[0] == 2, "rolling buffer deduplicates consecutive only and preserves repeats");
        check(!latest[0].hasWords() && "BitChord TV · Lời trực tiếp".equals(latest[0].source()), "app live lyrics carry honest source and no fake words");
        live.update(true, PKG, "BitChord TV", "Flower Flag", "FELT", VID, 295000L, ""); // paused/interlude
        check(latest[0] != null && latest[0].size() == 3, "pause/interlude retains captured live lines");
        for (int n = 4; n <= 10; n++) {
            live.update(true, PKG, "BitChord TV", "Flower Flag", "FELT", VID, 295000L, "Line " + n);
        }
        check(latest[0].size() == AppLiveLyrics.MAX_LINES && latestIdx[0] == AppLiveLyrics.MAX_LINES - 1 && "Line 10".equals(latest[0].at(AppLiveLyrics.MAX_LINES - 1)),
            "rolling buffer caps cleanly at MAX_LINES");
        // Track switch clears old lines
        live.update(true, PKG, "BitChord TV", "New Track", "FELT", "UEBliYVwP4U", 200000L, "");
        check(latest[0] == null && latestIdx[0] == -1, "track change resets live lyric buffer");
        // Japanese async ruby test (cold Kuromoji dictionary load on ARM32 TV can take ~2-4s)
        final long jpStart = android.os.SystemClock.elapsedRealtime();
        live.update(true, PKG, "BitChord TV", "New Track", "FELT", "UEBliYVwP4U", 200000L, "遠い空の下で");
        check(latest[0] != null && "遠い空の下で".equals(latest[0].at(0)), "Japanese line delivered immediately");
        Runnable pollRuby = new Runnable() {
            @Override public void run() {
                try {
                    boolean ready = latest[0] != null && latest[0].rubiesAt(0) != null && latest[0].rubiesAt(0).length > 0;
                    long elapsed = android.os.SystemClock.elapsedRealtime() - jpStart;
                    if (!ready && elapsed < 8000L) {
                        main.postDelayed(this, 100L);
                        return;
                    }
                    check(ready, "async Japanese ruby romaji enriches live line off UI thread (elapsedMs=" + elapsed + ")");
                    live.close();
                    System.out.println("FIX140_LYRICS_CHECK_OK cases=" + checks + " coldRubyMs=" + elapsed);
                    System.out.flush();
                    System.exit(0);
                } catch (Throwable error) {
                    error.printStackTrace(System.out);
                    System.exit(1);
                }
            }
        };
        main.postDelayed(pollRuby, 100L);
        Looper.loop();
    }
}
