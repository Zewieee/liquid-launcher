package local.launcherfix;

import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Rolling in-memory buffer for live lyric lines published directly by the active music app
 * via public MediaMetadata DISPLAY_SUBTITLE (e.g. BitChord TV's "♪ <line>").
 * Never fabricates future lines or word timings; computes Japanese ruby romaji off the UI thread.
 */
final class AppLiveLyrics {
    interface Listener {
        void onAppLyrics(Lyrics lyrics, int activeIndex, boolean appended);
    }

    static final int MAX_LINES = 8;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final boolean vi;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            task.run();
        }, "app-live-lyrics");
        thread.setDaemon(true);
        return thread;
    });

    private final ArrayList<String> lines = new ArrayList<>(MAX_LINES);
    private final Map<String, Lyrics.Ruby[]> rubyCache = new HashMap<>();
    private final Map<String, String> readingCache = new HashMap<>();
    private String trackKey = "";
    private String credit = "";
    private Lyrics current;
    private volatile long generation;
    private volatile boolean closed;

    AppLiveLyrics(boolean vi, Listener listener) {
        this.vi = vi;
        this.listener = listener;
    }

    void update(boolean connected, String source, String sourceLabel, String title, String artist,
                String youtubeId, long duration, String liveLyric) {
        if (closed) return;
        String safeSource = source == null ? "" : source;
        String safeLabel = sourceLabel == null ? "" : sourceLabel;
        String safeTitle = title == null ? "" : title;
        String safeArtist = artist == null ? "" : artist;
        String safeVideo = youtubeId == null ? "" : youtubeId;
        String nextTrack = !connected || safeTitle.isEmpty() ? ""
            : safeSource + "\n" + safeTitle + "\n" + safeArtist + "\n" + safeVideo + "\n" + duration;

        if (!nextTrack.equals(trackKey)) {
            resetState(nextTrack, formatCredit(safeLabel, safeSource, vi));
            if (liveLyric == null || liveLyric.isEmpty()) {
                listener.onAppLyrics(null, -1, false);
                return;
            }
        } else {
            String nextCredit = formatCredit(safeLabel, safeSource, vi);
            if (!nextCredit.isEmpty()) credit = nextCredit;
        }

        String line = MediaSources.sanitizeLiveLyric(liveLyric);
        if (line.isEmpty()) return;
        if (!lines.isEmpty() && lines.get(lines.size() - 1).equals(line)) return;

        if (lines.size() >= MAX_LINES) lines.remove(0);
        lines.add(line);
        current = buildLyrics(lines, readingCache, rubyCache, credit);
        int activeIndex = lines.size() - 1;
        listener.onAppLyrics(current, activeIndex, true);

        if (hasKana(line) && !rubyCache.containsKey(line)) {
            final long token = generation;
            final String targetLine = line;
            worker.execute(() -> {
                if (closed || generation != token) return;
                try {
                    Lyrics probe = Lyrics.timed(new long[]{0L}, new String[]{targetLine}, null);
                    Lyrics converted = JapaneseRomaji.convert(probe);
                    final Lyrics.Ruby[] rubies = converted.rubiesAt(0);
                    final String reading = converted.romajiAt(0);
                    main.post(() -> {
                        if (closed || generation != token || lines.isEmpty()) return;
                        rubyCache.put(targetLine, rubies);
                        readingCache.put(targetLine, reading);
                        current = buildLyrics(lines, readingCache, rubyCache, credit);
                        listener.onAppLyrics(current, lines.size() - 1, false);
                    });
                } catch (Exception ignored) {
                }
            });
        }
    }

    void clear() {
        if (closed) return;
        if (trackKey.isEmpty() && lines.isEmpty() && current == null) return;
        resetState("", "");
        listener.onAppLyrics(null, -1, false);
    }

    void close() {
        if (closed) return;
        closed = true;
        generation++;
        lines.clear();
        rubyCache.clear();
        readingCache.clear();
        current = null;
        worker.shutdownNow();
    }

    Lyrics currentLyrics() { return current; }

    int activeIndex() { return lines.isEmpty() ? -1 : lines.size() - 1; }

    private void resetState(String nextTrack, String nextCredit) {
        generation++;
        trackKey = nextTrack;
        credit = nextCredit;
        lines.clear();
        rubyCache.clear();
        readingCache.clear();
        current = null;
    }

    static String formatCredit(String sourceLabel, String sourcePackage, boolean vi) {
        String name = sourceLabel != null && !sourceLabel.trim().isEmpty() ? sourceLabel.trim()
            : sourcePackage != null ? sourcePackage.trim() : "";
        String suffix = vi ? "Lời trực tiếp" : "Live from app";
        return name.isEmpty() ? suffix : name + " · " + suffix;
    }

    static Lyrics buildLyrics(ArrayList<String> items, Map<String, String> readings,
                              Map<String, Lyrics.Ruby[]> rubies, String sourceName) {
        int n = items.size();
        long[] times = new long[n];
        String[] text = new String[n];
        String[] romaji = new String[n];
        Lyrics.Ruby[][] rubyRows = new Lyrics.Ruby[n][];
        boolean hasAnyReading = false;
        for (int i = 0; i < n; i++) {
            times[i] = i;
            String line = items.get(i);
            text[i] = line;
            String r = readings.get(line);
            romaji[i] = r == null ? "" : r;
            rubyRows[i] = rubies.get(line);
            if (!romaji[i].isEmpty() || rubyRows[i] != null) hasAnyReading = true;
        }
        Lyrics base = Lyrics.timed(times, text, null).fromSource(sourceName);
        return hasAnyReading ? base.withRomaji(romaji, null, rubyRows) : base;
    }

    private static boolean hasKana(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 0x3041 && c <= 0x3096) || (c >= 0x30a1 && c <= 0x30fa) || (c >= 0xff66 && c <= 0xff9d)) {
                return true;
            }
        }
        return false;
    }
}
