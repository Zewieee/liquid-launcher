package local.launcherfix;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Bounded 64-entry LRU store for per-track user lyric timing offset in milliseconds.
 * Positive offset advances lyric lookup (lyrics appear earlier); negative delays lyrics.
 * Never alters underlying lyric timestamps.
 */
final class LyricOffsetStore {
    static final long MIN_MS = -5000L;
    static final long MAX_MS = 5000L;
    static final long STEP_MS = 250L;

    private static final String PREFS = "liquid_lyric_offsets";
    private static final String KEY_ITEMS = "offsets_v1";
    private static final int MAX_ENTRIES = 64;

    private static boolean loaded;
    private static final LinkedHashMap<String, Long> MAP = new LinkedHashMap<String, Long>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private LyricOffsetStore() {}

    static long clamp(long offsetMs) {
        long bounded = Math.max(MIN_MS, Math.min(MAX_MS, offsetMs));
        return Math.round(bounded / 50.0) * 50L;
    }

    static String formatDelta(long offsetMs) {
        long c = clamp(offsetMs);
        if (c == 0L) return "0.0s";
        double sec = c / 1000.0;
        String sign = c > 0 ? "+" : "-";
        double abs = Math.abs(sec);
        long hundredths = Math.round(abs * 100.0);
        if (hundredths % 10 == 0) {
            return String.format(Locale.ROOT, "%s%.1fs", sign, hundredths / 100.0);
        }
        return String.format(Locale.ROOT, "%s%.2fs", sign, hundredths / 100.0);
    }

    static String formatPill(long offsetMs, boolean vi) {
        long c = clamp(offsetMs);
        String base = vi ? "Nhịp" : "Sync";
        return c == 0L ? base : base + " " + formatDelta(c);
    }

    static synchronized long get(Context context, String title, String artist, long durationMs) {
        if (context == null) return 0L;
        ensureLoaded(context);
        String k = LyricChoiceStore.songKey(title, artist, durationMs);
        if (k.isEmpty()) return 0L;
        Long direct = MAP.get(k);
        if (direct != null) return clamp(direct);
        int lastTab = k.lastIndexOf('\t');
        if (lastTab <= 0) return 0L;
        String prefix = k.substring(0, lastTab + 1);
        long center;
        try { center = Long.parseLong(k.substring(lastTab + 1)); }
        catch (NumberFormatException ignored) { return 0L; }
        for (long delta : new long[]{-1L, 1L}) {
            Long near = MAP.get(prefix + (center + delta));
            if (near != null) return clamp(near);
        }
        return 0L;
    }

    static synchronized long put(Context context, String title, String artist, long durationMs, long offsetMs) {
        long clean = clamp(offsetMs);
        if (context == null) return clean;
        ensureLoaded(context);
        String k = LyricChoiceStore.songKey(title, artist, durationMs);
        if (k.isEmpty()) return clean;
        clearNearLocked(k);
        if (clean != 0L) {
            MAP.put(k, clean);
        }
        persistLocked(context);
        return clean;
    }

    static synchronized void resetForTests(Context context) {
        MAP.clear();
        loaded = true;
        if (context != null) {
            context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_ITEMS).commit();
        }
    }

    static synchronized void reloadForTests(Context context) {
        MAP.clear();
        loaded = false;
        if (context != null) ensureLoaded(context);
    }

    private static void clearNearLocked(String key) {
        MAP.remove(key);
        int lastTab = key.lastIndexOf('\t');
        if (lastTab <= 0) return;
        String prefix = key.substring(0, lastTab + 1);
        try {
            long center = Long.parseLong(key.substring(lastTab + 1));
            MAP.remove(prefix + (center - 1L));
            MAP.remove(prefix + (center + 1L));
        } catch (NumberFormatException ignored) {}
    }

    private static void ensureLoaded(Context context) {
        if (loaded) return;
        loaded = true;
        try {
            SharedPreferences sp = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY_ITEMS, "");
            if (raw == null || raw.isEmpty()) return;
            JSONArray arr = new JSONArray(raw);
            int limit = Math.min(arr.length(), MAX_ENTRIES);
            for (int i = 0; i < limit; i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String k = item.optString("k", "");
                long v = clamp(item.optLong("v", 0L));
                if (!k.isEmpty() && k.length() <= 540 && v != 0L) {
                    MAP.put(k, v);
                }
            }
        } catch (Exception ignored) {
            MAP.clear();
        }
    }

    private static void persistLocked(Context context) {
        try {
            JSONArray arr = new JSONArray();
            for (Map.Entry<String, Long> e : MAP.entrySet()) {
                if (e.getValue() == 0L) continue;
                arr.put(new JSONObject().put("k", e.getKey()).put("v", e.getValue()));
            }
            context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ITEMS, arr.toString()).apply();
        } catch (Exception ignored) {}
    }
}
