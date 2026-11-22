package local.launcherfix;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.*;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** User-requested, bounded multi-source lyric search (LRCLIB, LrcRed, KuGou). Fetch full lyrics only for the selected record. */
final class LyricCandidates implements AutoCloseable {
    static final class Candidate {
        final long id, duration;
        final String title, artist, album, provider, extId, extKey;
        final boolean synced, words;
        Candidate(long id, long duration, String title, String artist, String album, boolean synced, boolean words) {
            this(id, duration, title, artist, album, synced, words, "LRCLIB", "", "");
        }
        Candidate(long id, long duration, String title, String artist, String album,
                  boolean synced, boolean words, String provider, String extId, String extKey) {
            this.id = id; this.duration = duration; this.title = title; this.artist = artist;
            this.album = album; this.synced = synced; this.words = words;
            this.provider = provider == null || provider.isEmpty() ? "LRCLIB" : provider;
            this.extId = extId == null ? "" : extId;
            this.extKey = extKey == null ? "" : extKey;
        }
        String key() { return provider + ":" + (id > 0 ? Long.toString(id) : extId); }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread t = new Thread(task, "lyrics-search");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        return t;
    });
    private final LiveLyrics reader = new LiveLyrics((data, status) -> {});
    private final String title, artist;
    private final long duration;
    private volatile boolean closed;
    private long generation;

    LyricCandidates(String title, String artist, long duration) {
        this.title = title; this.artist = artist; this.duration = duration;
    }

    void search(java.util.function.BiConsumer<List<Candidate>, Boolean> done) {
        final long token = ++generation;
        worker.execute(() -> {
            LinkedHashMap<String, Candidate> found = new LinkedHashMap<>();
            boolean anySuccess = false;
            boolean anyError = false;
            long deadline = SystemClock.elapsedRealtime() + 9000L;
            String cleanTitle = searchQueryTitle(title, artist);
            String cleanArtist = LyricMatch.primaryArtist(artist);

            // 1. LRCLIB (exact artist, then broad title if still sparse)
            for (int broad = 0; broad < 2 && !closed && found.size() < 8; broad++) try {
                if (broad > 0) Thread.sleep(160L);
                JSONObject response = reader.searchRecords(searchUrl(title, artist, broad != 0, true), Math.min(deadline, SystemClock.elapsedRealtime() + 3000L));
                if (response == null) response = reader.searchRecords(searchUrl(title, artist, broad != 0, false), Math.min(deadline, SystemClock.elapsedRealtime() + 3000L));
                if (response != null) anySuccess = true;
                for (Candidate item : parse(response, title, artist, duration)) found.putIfAbsent(item.key(), item);
            } catch (InterruptedException | java.io.InterruptedIOException cancelled) { anyError = true; break; }
            catch (Exception unavailable) { anyError = true; }

            // 2. LrcRed (Apple TTML word-timed catalogue)
            if (!closed && SystemClock.elapsedRealtime() < deadline - 800L) try {
                JSONObject red = reader.searchRecords(ExtraLyricSources.lrcRedSearchUrl(cleanTitle, cleanArtist), Math.min(deadline, SystemClock.elapsedRealtime() + 2600L));
                if (red != null) {
                    anySuccess = true;
                    for (Candidate item : parseLrcRed(red, title, artist, duration)) found.putIfAbsent(item.key(), item);
                }
            } catch (InterruptedException | java.io.InterruptedIOException cancelled) { anyError = true; }
            catch (Exception unavailable) { anyError = true; }

            // 3. KuGou Direct (strong CJK / J-pop / doujin coverage)
            if (!closed && SystemClock.elapsedRealtime() < deadline - 800L) try {
                JSONObject kg = reader.searchRecords(ExtraLyricSources.kugouSearchUrl(cleanTitle, cleanArtist, duration), Math.min(deadline, SystemClock.elapsedRealtime() + 2600L));
                if (kg != null) {
                    anySuccess = true;
                    for (Candidate item : parseKugou(kg, title, artist, duration)) found.putIfAbsent(item.key(), item);
                }
            } catch (InterruptedException | java.io.InterruptedIOException cancelled) { anyError = true; }
            catch (Exception unavailable) { anyError = true; }

            List<Candidate> result = rank(found.values(), duration);
            final boolean failed = !anySuccess && anyError;
            main.post(() -> { if (!closed && token == generation) done.accept(result, failed); });
        });
    }

    void choose(Candidate selected, Consumer<Lyrics> done) {
        final long token = ++generation;
        worker.execute(() -> {
            Lyrics value = null;
            try {
                long dl = SystemClock.elapsedRealtime() + 6000L;
                if ("LrcRed".equals(selected.provider)) {
                    String docUrl = ExtraLyricSources.lrcRedDocUrl(selected.extId);
                    if (!docUrl.isEmpty()) {
                        JSONObject doc = reader.searchRecords(docUrl, dl);
                        if (doc != null) {
                            Lyrics parsed = ProviderLyrics.ttml(doc.getString("ttml"), Math.max(duration, selected.duration), true).fromSource("LrcRed");
                            if (parsed.size() > 0) value = parsed;
                        }
                    }
                } else if ("KuGou".equals(selected.provider)) {
                    String dlUrl = ExtraLyricSources.kugouDownloadUrl(selected.extId, selected.extKey);
                    if (!dlUrl.isEmpty()) {
                        JSONObject doc = reader.searchRecords(dlUrl, dl);
                        Lyrics parsed = ExtraLyricSources.parseKugouDownload(doc, Math.max(duration, selected.duration) + 15000L);
                        if (parsed != null && parsed.size() > 0) value = parsed;
                    }
                } else {
                    value = selected(reader.searchRecords("https://lrclib.net/api/get/" + selected.id, dl), selected, title, artist, duration);
                }
            } catch (Exception unavailable) {}
            final Lyrics result = value;
            main.post(() -> { if (!closed && token == generation) done.accept(result); });
        });
    }

    @Override public void close() {
        if (closed) return;
        closed = true; generation++; reader.close(); worker.shutdownNow();
    }

    static String searchQueryTitle(String title, String artist) {
        String base = LyricMatch.searchTitle(title == null ? "" : title, artist == null ? "" : artist);
        return (base.contains(" - ") || base.contains(" / "))
            ? new LyricMatch().dedupeBilingualTitle(base) : base;
    }

    static String searchUrl(String title, String artist, boolean broad, boolean summary) throws Exception {
        String qTitle = searchQueryTitle(title, artist);
        return "https://lrclib.net/api/" + (summary ? "v2/" : "") + "search?track_name=" + URLEncoder.encode(qTitle, "UTF-8")
            + (broad ? "" : "&artist_name=" + URLEncoder.encode(LyricMatch.primaryArtist(artist), "UTF-8"));
    }

    private static boolean identity(LyricMatch match, String title, String artist, String returnedTitle, String returnedArtist) {
        return match.title(title, artist, returnedTitle) && match.artist(artist, returnedArtist);
    }

    static List<Candidate> parse(JSONObject response, String title, String artist, long duration) {
        LinkedHashMap<Long, Candidate> found = new LinkedHashMap<>();
        if (response == null || duration < 1000 || duration > 3600000 || title.isEmpty() || artist.isEmpty() || title.length() > 512 || artist.length() > 512) {
            return new ArrayList<>();
        }
        JSONArray records = response.optJSONArray("results");
        if (records == null) return new ArrayList<>();
        LyricMatch match = new LyricMatch();
        for (int i = 0; i < Math.min(records.length(), 40); i++) try {
            if (Thread.currentThread().isInterrupted()) break;
            JSONObject item = records.getJSONObject(i);
            if (!(item.opt("id") instanceof Number) || !(item.opt("duration") instanceof Number)
                || !(item.opt("trackName") instanceof String) || !(item.opt("artistName") instanceof String)) continue;
            long id = item.getLong("id"); double seconds = item.getDouble("duration");
            String name = item.getString("trackName"), who = item.getString("artistName"), album = item.optString("albumName", "");
            if (id <= 0 || id > 9007199254740991L || item.getDouble("id") != id || name.length() > 512 || who.length() > 512 || album.length() > 512 || !Double.isFinite(seconds)
                || seconds < 1 || seconds > 3600 || Math.abs(seconds * 1000 - duration) > 15000 || item.optBoolean("instrumental", false)
                || !identity(match, title, artist, name, who)) continue;
            boolean words = item.optBoolean("hasWordSync", false);
            boolean synced = words || item.optBoolean("hasSyncedLyrics", false) || text(item, "syncedLyrics");
            boolean plain = item.optBoolean("hasPlainLyrics", false) || text(item, "plainLyrics");
            if (!synced && !plain && !item.optBoolean("hasLyricsfile", false)) continue;
            found.putIfAbsent(id, new Candidate(id, Math.round(seconds * 1000), name, who, album, synced, words, "LRCLIB", "", ""));
        } catch (Exception invalid) {}
        return rank(found.values(), duration);
    }

    static List<Candidate> parseLrcRed(JSONObject response, String title, String artist, long duration) {
        ArrayList<Candidate> out = new ArrayList<>();
        if (response == null || duration < 1000 || duration > 3600000 || title.isEmpty() || artist.isEmpty()) return out;
        JSONArray hits = response.optJSONArray("hits");
        if (hits == null) return out;
        LyricMatch match = new LyricMatch();
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < Math.min(15, hits.length()); i++) try {
            JSONObject hit = hits.optJSONObject(i);
            if (hit == null) continue;
            String isrc = hit.optString("isrc", "").trim().toUpperCase(Locale.ROOT);
            if (!ExtraLyricSources.validIsrc(isrc) || !seen.add(isrc)) continue;
            String name = hit.optString("title", ""), who = hit.optString("artist", ""), album = hit.optString("album", "");
            double sec = hit.optDouble("duration", -1.0);
            if (name.length() > 512 || who.length() > 512 || album.length() > 512 || !Double.isFinite(sec)
                || sec < 1 || sec > 3600 || Math.abs(sec * 1000.0 - duration) > 15000.0
                || !identity(match, title, artist, name, who)) continue;
            out.add(new Candidate(0L, Math.round(sec * 1000.0), name, who, album, true, true, "LrcRed", isrc, ""));
        } catch (Exception ignored) {}
        return out;
    }

    static List<Candidate> parseKugou(JSONObject response, String title, String artist, long duration) {
        ArrayList<Candidate> out = new ArrayList<>();
        if (response == null || duration < 1000 || duration > 3600000 || title.isEmpty() || artist.isEmpty()) return out;
        JSONArray items = response.optJSONArray("candidates");
        if (items == null) return out;
        LyricMatch match = new LyricMatch();
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < Math.min(15, items.length()) && out.size() < 4; i++) try {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String cid = item.optString("id", "").trim();
            String key = item.optString("accesskey", "").trim();
            if (ExtraLyricSources.kugouDownloadUrl(cid, key).isEmpty() || !seen.add(cid)) continue;
            String name = item.optString("song", ""), who = item.optString("singer", "");
            double rawDur = item.optDouble("duration", -1.0);
            if (rawDur <= 0 || !Double.isFinite(rawDur)) continue;
            double sec = rawDur < 10000.0 ? rawDur : rawDur / 1000.0;
            if (name.length() > 512 || who.length() > 512 || sec < 1 || sec > 3600
                || Math.abs(sec * 1000.0 - duration) > 15000.0
                || !identity(match, title, artist, name, who)) continue;
            out.add(new Candidate(0L, Math.round(sec * 1000.0), name, who, "", true, false, "KuGou", cid, key));
        } catch (Exception ignored) {}
        return out;
    }

    private static boolean text(JSONObject item, String key) {
        Object value = item.opt(key);
        return value instanceof String && !((String) value).trim().isEmpty();
    }

    private static List<Candidate> rank(Collection<Candidate> values, long duration) {
        ArrayList<Candidate> result = new ArrayList<>(values);
        result.sort(Comparator.<Candidate>comparingInt(item -> item.words ? 0 : item.synced ? 1 : 2)
            .thenComparingLong(item -> Math.abs(item.duration - duration))
            .thenComparingInt(item -> "LRCLIB".equals(item.provider) ? 0 : "LrcRed".equals(item.provider) ? 1 : 2)
            .thenComparingLong(item -> item.id));
        return new ArrayList<>(result.subList(0, Math.min(8, result.size())));
    }

    static Lyrics selected(JSONObject record, Candidate candidate, String title, String artist, long duration) throws Exception {
        if (record == null || !(record.opt("id") instanceof Number) || record.getLong("id") != candidate.id) return null;
        // Recheck the full response: search summaries may be stale or malformed.
        List<Candidate> checked = parse(new JSONObject().put("results", new JSONArray().put(record)), title, artist, duration);
        if (checked.isEmpty()) return null;
        Candidate actual = checked.get(0);
        if (!actual.title.equals(candidate.title) || !actual.artist.equals(candidate.artist) || actual.duration != candidate.duration) return null;
        double seconds = record.getDouble("duration");
        Lyrics parsed = LiveLyrics.parse(record, actual.title, actual.artist, Math.round(seconds * 1000)).lyrics;
        if (parsed == null || parsed.size() == 0) return null;
        if (parsed.isSynced()) for (int i = 0; i < parsed.size(); i++) {
            if (parsed.startAt(i) > duration + 2000) return null;
            Lyrics.Word[] words = parsed.wordsAt(i);
            if (words != null) for (Lyrics.Word word : words) if (word.end > duration + 2000) return null;
        }
        return parsed.fromSource("LRCLIB");
    }

    static Lyrics selectedById(JSONObject record, long expectedId, String title, String artist, long duration) throws Exception {
        if (record == null || expectedId <= 0 || !(record.opt("id") instanceof Number) || record.getLong("id") != expectedId) return null;
        List<Candidate> checked = parse(new JSONObject().put("results", new JSONArray().put(record)), title, artist, duration);
        if (checked.isEmpty()) return null;
        return selected(record, checked.get(0), title, artist, duration);
    }
}
