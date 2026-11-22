package local.launcherfix;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;

/**
 * End-to-end parallel network lookup verification on the real Android TV network stack:
 * - Track 1: YouTube-packaged Japanese hit ("Idol (Official Music Video)" / "YOASOBI - Topic") -> Word TTML
 * - Track 2: Actual user BitChord song ("イナイセカイ - Inai Sekai" / "yuiko") -> KuGou synced + Japanese ruby romaji
 * Does not touch TV UI, playback, or private app data.
 */
public final class LiveParallelLyricsCheck {
    private static int checks;
    private static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        final Handler main = new Handler(Looper.getMainLooper());
        final long[] phaseStart = {SystemClock.elapsedRealtime()};
        final List<String> stages = new ArrayList<>();
        final Lyrics[] latest = {null};
        final String[] lastStatus = {""};

        final LiveLyrics live = new LiveLyrics((lyrics, status) -> {
            lastStatus[0] = status;
            if (lyrics != null) {
                latest[0] = lyrics;
                long elapsed = SystemClock.elapsedRealtime() - phaseStart[0];
                stages.add(lyrics.source() + "(words=" + lyrics.hasWords() + ",synced=" + lyrics.isSynced()
                    + ",lines=" + lyrics.size() + ",rubies=" + (lyrics.rubiesAt(0) != null) + ",ms=" + elapsed + ")");
            }
        });
        live.start();
        live.setTrack("Idol (Official Music Video)", "YOASOBI - Topic", "", "", "ZRtdQ81jPUQ", 213000L);

        main.postDelayed(() -> {
            try {
                System.out.println("TRACK1_STAGES=" + stages + " status=" + lastStatus[0]);
                check(latest[0] != null && latest[0].size() > 10, "parallel chain resolves YouTube-packaged track");
                check(latest[0].isSynced(), "resolved synced lyrics for Idol");
                check(latest[0].hasWords(), "resolved word-synced lyrics for Idol in parallel wave");

                // Track 2: User's actual paused BitChord track (bilingual JP - Romaji, only on KuGou!)
                stages.clear();
                latest[0] = null;
                lastStatus[0] = "";
                phaseStart[0] = SystemClock.elapsedRealtime();
                live.setTrack("イナイセカイ - Inai Sekai", "yuiko", "PS 2005-2013", "", "", 196000L);
            } catch (Throwable err) {
                err.printStackTrace(System.out);
                System.exit(1);
            }

            main.postDelayed(() -> {
                try {
                    System.out.println("TRACK2_STAGES=" + stages + " status=" + lastStatus[0]);
                    check(latest[0] != null && latest[0].isSynced() && latest[0].size() > 10,
                        "parallel fallback resolves bilingual BitChord track via KuGou");
                    check(latest[0].rubiesAt(0) != null && latest[0].rubiesAt(0).length > 0,
                        "KuGou Japanese track receives word-aligned ruby romaji");
                    live.close();
                    System.out.println("LIVE_PARALLEL_LYRICS_CHECK_OK cases=" + checks);
                    System.out.flush();
                    System.exit(0);
                } catch (Throwable err) {
                    err.printStackTrace(System.out);
                    System.exit(1);
                }
            }, 9000L);
        }, 6500L);
        Looper.loop();
    }
}
