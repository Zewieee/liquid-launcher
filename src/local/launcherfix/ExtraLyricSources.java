package local.launcherfix;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Additional keyless public lyric providers aligned with BitChord TV's chain:
 * BetterLyrics (boidu.dev), LyricsPlus (syllable JSON), LrcRed free-text search + ISRC cache,
 * direct KuGou public lyrics, and keyless YouTube Music Lyrics tab (keyed strictly on youtubeId).
 */
final class ExtraLyricSources {
    private static final Pattern ISRC = Pattern.compile("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}");
    private static final Pattern KUGOU_ID = Pattern.compile("[0-9]{1,20}");
    private static final Pattern KUGOU_KEY = Pattern.compile("[A-Za-z0-9]{8,64}");
    private static final Pattern YTM_LYRICS_BROWSE_ID = Pattern.compile("MPLYt_[A-Za-z0-9_-]{4,80}");
    private static final Pattern KUGOU_STAMP = Pattern.compile("^\\[\\d{1,3}:[0-5]\\d(?:\\.\\d{1,3})?\\](.*)$");
    private static final Pattern KUGOU_CREDIT = Pattern.compile(
        "^(?:作[词詞曲]|[编編]曲|制作人|製作人|混音|录音|錄音|母带|母帶|监制|監製|出品|发行|發行|Lyrics(?:\\s+by)?|Composed(?:\\s+by)?|Arranged(?:\\s+by)?|Producer|Written(?:\\s+by)?)\\s*[:：].*",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern KUGOU_INSTRUMENTAL = Pattern.compile("纯音乐.*欣赏|純音樂.*欣賞");

    private static final Map<String, String> VIDEO_ISRC = Collections.synchronizedMap(
        new LinkedHashMap<String, String>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > 64;
            }
        });

    private ExtraLyricSources() {}

    static boolean validIsrc(String value) {
        return value != null && ISRC.matcher(value).matches();
    }

    static void rememberIsrc(String videoId, String isrc) {
        if (YouTubeTrack.valid(videoId) && validIsrc(isrc)) {
            VIDEO_ISRC.put(videoId, isrc);
        }
    }

    static String cachedIsrc(String videoId) {
        if (!YouTubeTrack.valid(videoId)) return "";
        String value = VIDEO_ISRC.get(videoId);
        return validIsrc(value) ? value : "";
    }

    static String boiduTtmlUrl(String title, String artist, String album, long duration) throws Exception {
        StringBuilder sb = new StringBuilder("https://lyrics-api.boidu.dev/getLyrics?s=")
            .append(encode(title)).append("&a=").append(encode(artist));
        if (album != null && !album.isEmpty()) sb.append("&al=").append(encode(album));
        if (duration > 0) sb.append("&d=").append(duration / 1000L);
        return sb.toString();
    }

    static String lyricsPlusUrl(String title, String artist, String album, long duration, String isrc) throws Exception {
        StringBuilder sb = new StringBuilder("https://lyricsplus.prjktla.my.id/v2/lyrics/get?title=")
            .append(encode(title)).append("&artist=").append(encode(artist));
        if (duration > 0) sb.append("&duration=").append(duration / 1000L);
        if (album != null && !album.isEmpty()) sb.append("&album=").append(encode(album));
        if (validIsrc(isrc)) sb.append("&isrc=").append(isrc);
        return sb.toString();
    }

    static String lrcRedSearchUrl(String title, String artist) throws Exception {
        String query = (title + " " + artist).trim();
        return "https://lrc.red/search.json?q=" + encode(query);
    }

    static String lrcRedDocUrl(String isrc) {
        if (isrc == null) return "";
        String clean = isrc.trim().toUpperCase(Locale.ROOT);
        return validIsrc(clean) ? "https://lrc.red/s/" + clean + ".ttml" : "";
    }

    static String kugouSearchUrl(String title, String artist, long duration) throws Exception {
        String keyword = (title + " - " + artist).trim();
        StringBuilder sb = new StringBuilder("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=")
            .append(encode(keyword));
        if (duration > 0) sb.append("&duration=").append(duration);
        return sb.toString();
    }

    static String kugouDownloadUrl(String id, String accessKey) {
        if (id == null || accessKey == null || !KUGOU_ID.matcher(id).matches() || !KUGOU_KEY.matcher(accessKey).matches()) {
            return "";
        }
        return "https://lyrics.kugou.com/download?fmt=lrc&charset=utf8&client=pc&ver=1&id=" + id + "&accesskey=" + accessKey;
    }

    static Lyrics parseBetterLyricsTtml(JSONObject info, long duration) throws Exception {
        if (info == null) return null;
        if (info.has("score")) {
            double score = info.getDouble("score");
            if (Double.isNaN(score) || Double.isInfinite(score) || score < 0.85 || score > 1.0) return null;
        }
        String ttml = info.optString("ttml", "");
        if (ttml.isEmpty()) return null;
        Lyrics parsed = ProviderLyrics.ttml(ttml, duration);
        if (parsed.size() == 0 || LyricMatch.isTruncatedForDuration(parsed, duration)) return null;
        Lyrics built = parsed.fromSource("Better Lyrics TTML");
        return built.withQuality(LyricMatch.computeQuality(built, duration, 0L, false));
    }

    static Lyrics parseLyricsPlus(JSONObject info, String title, String artist, long duration, LyricMatch match) {
        if (info == null || duration <= 0) return null;
        JSONObject meta = info.optJSONObject("metadata");
        String sourceLabel = "LyricsPlus";
        long metaMs = 0L;
        if (meta != null) {
            String metaTitle = meta.optString("title", "").trim();
            String metaArtist = meta.optString("artist", "").trim();
            if (!metaTitle.isEmpty() && !match.title(title, artist, metaTitle)) return null;
            if (!metaArtist.isEmpty() && !match.artist(artist, metaArtist)) return null;
            String totalDur = meta.optString("totalDuration", "").trim();
            if (!totalDur.isEmpty()) {
                try {
                    metaMs = ProviderLyrics.time(totalDur);
                    boolean strictDur = Math.abs(metaMs - duration) <= 3000L;
                    boolean mvDur = !strictDur && match.recordMvFallback(title, artist, duration,
                        metaTitle.isEmpty() ? title : metaTitle,
                        metaArtist.isEmpty() ? artist : metaArtist,
                        metaMs / 1000.0);
                    if (!strictDur && !mvDur) return null;
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
            String sub = sanitizeShortLabel(meta.optString("source", ""));
            if (!sub.isEmpty()) sourceLabel = "LyricsPlus · " + sub;
        }
        JSONArray rows = info.optJSONArray("lyrics");
        if (rows == null || rows.length() == 0 || rows.length() > 2048) return null;

        TreeMap<Long, LpRow> sorted = new TreeMap<>();
        int totalChars = 0;
        int totalWords = 0;
        long maxTime = Math.max(duration, metaMs) + 3000L;

        for (int i = 0; i < rows.length(); i++) {
            JSONObject item = rows.optJSONObject(i);
            if (item == null || !(item.opt("time") instanceof Number)) continue;
            long lineStart = item.optLong("time", -1L);
            if (lineStart < 0 || lineStart > maxTime) return null;

            JSONArray syllabus = item.optJSONArray("syllabus");
            String text = "";
            ArrayList<Lyrics.Word> words = new ArrayList<>();
            boolean validWords = syllabus != null && syllabus.length() > 0;
            if (validWords) {
                StringBuilder sb = new StringBuilder();
                long prevEnd = 0L;
                long firstStart = Long.MAX_VALUE;
                for (int j = 0; j < syllabus.length(); j++) {
                    JSONObject syl = syllabus.optJSONObject(j);
                    if (syl == null || !(syl.opt("time") instanceof Number)) { validWords = false; break; }
                    String rawPart = syl.optString("text", "");
                    if (rawPart.isEmpty()) continue;
                    long sStart = syl.optLong("time", -1L);
                    long sDur = Math.max(0L, syl.optLong("duration", 0L));
                    long sEnd = sStart + sDur;
                    if (sStart < 0 || sEnd < sStart || sEnd > maxTime) { validWords = false; break; }
                    if (!words.isEmpty()) {
                        if (sStart < prevEnd) {
                            if (prevEnd - sStart <= 60L) {
                                sStart = prevEnd;
                                if (sEnd < sStart) sEnd = sStart;
                            } else {
                                validWords = false;
                                break;
                            }
                        }
                    }
                    int from = sb.length();
                    sb.append(rawPart);
                    if (sb.length() > 512) return null;
                    int trimFrom = from, trimTo = sb.length();
                    while (trimFrom < trimTo && Character.isWhitespace(sb.charAt(trimFrom))) trimFrom++;
                    while (trimTo > trimFrom && Character.isWhitespace(sb.charAt(trimTo - 1))) trimTo--;
                    if (trimTo > trimFrom) {
                        if (++totalWords > 8192) return null;
                        words.add(new Lyrics.Word(trimFrom, trimTo, sStart, sEnd));
                        if (sStart < firstStart) firstStart = sStart;
                        prevEnd = sEnd;
                    }
                }
                if (validWords && !words.isEmpty()) {
                    text = sb.toString();
                    int leadTrim = 0;
                    while (leadTrim < text.length() && Character.isWhitespace(text.charAt(leadTrim))) leadTrim++;
                    int endTrim = text.length();
                    while (endTrim > leadTrim && Character.isWhitespace(text.charAt(endTrim - 1))) endTrim--;
                    if (leadTrim > 0 || endTrim < text.length()) {
                        text = text.substring(leadTrim, endTrim);
                        ArrayList<Lyrics.Word> shifted = new ArrayList<>(words.size());
                        for (Lyrics.Word w : words) {
                            int nf = Math.max(0, w.from - leadTrim);
                            int nt = Math.min(text.length(), w.to - leadTrim);
                            if (nt > nf) shifted.add(new Lyrics.Word(nf, nt, w.start, w.end));
                        }
                        words = shifted;
                    }
                    if (firstStart < lineStart) lineStart = firstStart;
                } else {
                    words.clear();
                    text = item.optString("text", "").trim();
                }
            } else {
                text = item.optString("text", "").trim();
            }

            if (text.isEmpty()) continue;
            if (text.length() > 512) text = text.substring(0, 512);
            totalChars += text.length();
            if (totalChars > 65536) return null;
            if (!sorted.containsKey(lineStart)) {
                sorted.put(lineStart, new LpRow(text, words.isEmpty() ? null : words.toArray(new Lyrics.Word[0])));
            }
        }

        if (sorted.isEmpty()) return null;
        long[] times = new long[sorted.size()];
        String[] texts = new String[sorted.size()];
        Lyrics.Word[][] wordRows = new Lyrics.Word[sorted.size()][];
        int idx = 0;
        for (Map.Entry<Long, LpRow> entry : sorted.entrySet()) {
            times[idx] = entry.getKey();
            texts[idx] = entry.getValue().text;
            wordRows[idx] = entry.getValue().words;
            idx++;
        }
        Lyrics built = Lyrics.timed(times, texts, wordRows).fromSource(sourceLabel);
        if (LyricMatch.isTruncatedForDuration(built, duration)) return null;
        return built.withQuality(LyricMatch.computeQuality(built, duration, metaMs, false));
    }

    static String parseLrcRedSearch(JSONObject info, String title, String artist, long duration, LyricMatch match) {
        if (info == null || duration <= 0) return "";
        JSONArray hits = info.optJSONArray("hits");
        if (hits == null) return "";
        String strictIsrc = "", mvIsrc = "";
        double strictDelta = Double.POSITIVE_INFINITY, mvDelta = Double.POSITIVE_INFINITY;
        for (int i = 0; i < Math.min(15, hits.length()); i++) {
            JSONObject hit = hits.optJSONObject(i);
            if (hit == null) continue;
            String isrc = hit.optString("isrc", "").trim().toUpperCase(Locale.ROOT);
            if (!validIsrc(isrc)) continue;
            String hitTitle = hit.optString("title", "");
            String hitArtist = hit.optString("artist", "");
            double sec = hit.optDouble("duration", -1.0);
            double delta = Math.abs(sec * 1000.0 - duration);
            if (match.record(title, artist, duration, hitTitle, hitArtist, sec, 3000L)) {
                if (delta < strictDelta) {
                    strictDelta = delta;
                    strictIsrc = isrc;
                }
            } else if (strictIsrc.isEmpty() && match.recordMvFallback(title, artist, duration, hitTitle, hitArtist, sec)) {
                if (delta < mvDelta) {
                    mvDelta = delta;
                    mvIsrc = isrc;
                }
            }
        }
        return !strictIsrc.isEmpty() ? strictIsrc : mvIsrc;
    }

    static String[] parseKugouCandidate(JSONObject info, String title, String artist, long duration, LyricMatch match) {
        if (info == null || duration <= 0) return null;
        JSONArray candidates = info.optJSONArray("candidates");
        if (candidates == null) return null;
        String[] strictBest = null, mvBest = null;
        int strictScore = Integer.MIN_VALUE, mvScore = Integer.MIN_VALUE;
        double strictDelta = Double.POSITIVE_INFINITY, mvDelta = Double.POSITIVE_INFINITY;
        for (int i = 0; i < Math.min(15, candidates.length()); i++) {
            JSONObject c = candidates.optJSONObject(i);
            if (c == null) continue;
            String id = c.optString("id", "").trim();
            String key = c.optString("accesskey", "").trim();
            if (!KUGOU_ID.matcher(id).matches() || !KUGOU_KEY.matcher(key).matches()) continue;
            String song = c.optString("song", "");
            String singer = c.optString("singer", "");
            double candRaw = c.optDouble("duration", -1.0);
            if (candRaw <= 0 || Double.isNaN(candRaw) || Double.isInfinite(candRaw)) continue;
            // KuGou returns seconds on some candidates (e.g. 195) and milliseconds on others (e.g. 195866).
            double candSec = candRaw < 10000.0 ? candRaw : candRaw / 1000.0;
            int score = c.optInt("score", 0);
            double delta = Math.abs(candSec * 1000.0 - duration);
            if (match.record(title, artist, duration, song, singer, candSec, 5000L)) {
                if (strictBest == null || score > strictScore || (score == strictScore && delta < strictDelta)) {
                    strictBest = new String[]{id, key};
                    strictScore = score;
                    strictDelta = delta;
                }
            } else if (strictBest == null && match.recordMvFallback(title, artist, duration, song, singer, candSec)) {
                if (mvBest == null || score > mvScore || (score == mvScore && delta < mvDelta)) {
                    mvBest = new String[]{id, key};
                    mvScore = score;
                    mvDelta = delta;
                }
            }
        }
        return strictBest != null ? strictBest : mvBest;
    }

    static Lyrics parseKugouDownload(JSONObject info, long duration) {
        if (info == null || duration <= 0) return null;
        String encoded = info.optString("content", "");
        if (encoded.isEmpty() || encoded.length() > 180000) return null;
        byte[] decoded;
        try {
            decoded = Base64.getMimeDecoder().decode(encoded);
        } catch (IllegalArgumentException badBase64) {
            return null;
        }
        if (decoded.length == 0 || decoded.length > 65536) return null;
        String raw = new String(decoded, StandardCharsets.UTF_8);
        String cleaned = stripKugouNoise(raw);
        if (cleaned.isEmpty()) return null;
        Lyrics parsed;
        try {
            parsed = Lyrics.parse(cleaned);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        if (parsed.size() == 0) return null;
        if (parsed.startAt(parsed.size() - 1) > duration + 5000L) return null;
        if (LyricMatch.isTruncatedForDuration(parsed, duration)) return null;
        Lyrics built = parsed.fromSource("KuGou");
        return built.withQuality(LyricMatch.computeQuality(built, duration, 0L, false));
    }

    static String stripKugouNoise(String lrc) {
        ArrayList<String> stamped = new ArrayList<>();
        for (String raw : lrc.split("\\r?\\n")) {
            String line = raw.trim();
            if (KUGOU_STAMP.matcher(line).matches()) stamped.add(line);
        }
        if (stamped.isEmpty()) return "";
        int headLimit = Math.min(20, stamped.size() - 1);
        int headCut = 0;
        for (int i = headLimit; i >= 0; i--) {
            String body = KUGOU_STAMP.matcher(stamped.get(i)).replaceFirst("$1").trim();
            if (KUGOU_CREDIT.matcher(body).matches()) { headCut = i + 1; break; }
        }
        if (headCut >= stamped.size()) return "";
        int tailLimit = Math.min(20, stamped.size() - headCut - 1);
        int tailCut = 0;
        for (int i = 0; i <= tailLimit; i++) {
            String body = KUGOU_STAMP.matcher(stamped.get(stamped.size() - 1 - i)).replaceFirst("$1").trim();
            if (KUGOU_CREDIT.matcher(body).matches()) { tailCut = i + 1; break; }
        }
        int end = stamped.size() - tailCut;
        if (end <= headCut) return "";
        boolean allInstrumental = true;
        int nonEmpty = 0;
        StringBuilder out = new StringBuilder();
        for (int i = headCut; i < end; i++) {
            String line = stamped.get(i);
            String body = KUGOU_STAMP.matcher(line).replaceFirst("$1").trim();
            if (!body.isEmpty()) {
                nonEmpty++;
                if (!KUGOU_INSTRUMENTAL.matcher(body).find()) allInstrumental = false;
            }
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        return nonEmpty > 0 && !allInstrumental ? out.toString() : "";
    }

    static String youtubeNextBody(String videoId) throws Exception {
        if (!YouTubeTrack.valid(videoId)) throw new IllegalArgumentException("Invalid YouTube video ID");
        return new JSONObject()
            .put("videoId", videoId)
            .put("playlistId", "RDAMVM" + videoId)
            .put("isAudioOnly", true)
            .put("context", youtubeContext())
            .toString();
    }

    static String youtubeBrowseBody(String browseId, String params) throws Exception {
        if (browseId == null || !YTM_LYRICS_BROWSE_ID.matcher(browseId).matches()) {
            throw new IllegalArgumentException("Invalid YouTube lyrics browse ID");
        }
        JSONObject req = new JSONObject()
            .put("browseId", browseId)
            .put("context", youtubeContext());
        if (params != null && !params.isEmpty() && params.length() <= 256) {
            req.put("params", params);
        }
        return req.toString();
    }

    private static JSONObject youtubeContext() throws Exception {
        return new JSONObject().put("client", new JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", "1.20250101.01.00")
            .put("hl", "en")
            .put("gl", "US"));
    }

    static String[] extractYouTubeLyricsEndpoint(JSONObject nextResponse) {
        if (nextResponse == null) return null;
        ArrayList<JSONObject> tabs = findObjectsNamed(nextResponse, "tabRenderer", 16);
        for (JSONObject tab : tabs) {
            String title = collectText(tab.opt("title"));
            if (!"lyrics".equalsIgnoreCase(title.trim())) continue;
            ArrayList<JSONObject> endpoints = findObjectsNamed(tab, "browseEndpoint", 4);
            for (JSONObject ep : endpoints) {
                String browseId = ep.optString("browseId", "").trim();
                if (YTM_LYRICS_BROWSE_ID.matcher(browseId).matches()) {
                    String params = ep.optString("params", "").trim();
                    if (params.length() > 256 || params.matches("(?s).*[\\p{Cntrl}].*")) params = "";
                    return new String[]{browseId, params};
                }
            }
        }
        return null;
    }

    static Lyrics parseYouTubeMusicLyrics(JSONObject browseResponse) {
        if (browseResponse == null) return null;
        ArrayList<JSONObject> shelves = findObjectsNamed(browseResponse, "musicDescriptionShelfRenderer", 4);
        for (JSONObject shelf : shelves) {
            Object desc = shelf.opt("description");
            String text = collectText(desc).trim();
            if (text.isEmpty() || text.length() > 65536) continue;
            try {
                Lyrics plain = Lyrics.plain(text);
                if (plain.size() > 0) return plain.fromSource("YouTube Music");
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    private static ArrayList<JSONObject> findObjectsNamed(Object root, String targetKey, int limit) {
        ArrayList<JSONObject> found = new ArrayList<>();
        if (root == null) return found;
        ArrayDeque<Object> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < 4000 && found.size() < limit) {
            Object current = queue.poll();
            visited++;
            if (current instanceof JSONObject) {
                JSONObject obj = (JSONObject) current;
                JSONArray names = obj.names();
                if (names == null) continue;
                for (int i = 0; i < names.length(); i++) {
                    String k = names.optString(i, "");
                    Object v = obj.opt(k);
                    if (targetKey.equals(k) && v instanceof JSONObject) {
                        found.add((JSONObject) v);
                        if (found.size() >= limit) break;
                    }
                    if (v instanceof JSONObject || v instanceof JSONArray) queue.add(v);
                }
            } else if (current instanceof JSONArray) {
                JSONArray arr = (JSONArray) current;
                for (int i = 0; i < arr.length(); i++) {
                    Object v = arr.opt(i);
                    if (v instanceof JSONObject || v instanceof JSONArray) queue.add(v);
                }
            }
        }
        return found;
    }

    private static String collectText(Object node) {
        if (node == null) return "";
        if (node instanceof String) return (String) node;
        StringBuilder out = new StringBuilder();
        collectTextInto(node, out, 0);
        return out.toString();
    }

    private static void collectTextInto(Object node, StringBuilder out, int depth) {
        if (node == null || depth > 10 || out.length() > 65536) return;
        if (node instanceof String) {
            out.append((String) node);
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) collectTextInto(arr.opt(i), out, depth + 1);
        } else if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            Object direct = obj.opt("text");
            if (direct instanceof String) { out.append((String) direct); return; }
            Object simple = obj.opt("simpleText");
            if (simple instanceof String) { out.append((String) simple); return; }
            Object runs = obj.opt("runs");
            if (runs instanceof JSONArray) { collectTextInto(runs, out, depth + 1); return; }
            JSONArray names = obj.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    collectTextInto(obj.opt(names.optString(i, "")), out, depth + 1);
                }
            }
        }
    }

    private static String sanitizeShortLabel(String raw) {
        if (raw == null) return "";
        String clean = MediaSources.sanitizeLiveLyric(raw);
        return clean.length() <= 32 ? clean : clean.substring(0, 32).trim();
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    private static final class LpRow {
        final String text;
        final Lyrics.Word[] words;
        LpRow(String text, Lyrics.Word[] words) { this.text = text; this.words = words; }
    }
}
