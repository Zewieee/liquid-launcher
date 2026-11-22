package local.launcherfix;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Bounded 64-entry LRU store for user-selected manual lyric records per song
 * across LRCLIB, LrcRed (ISRC), and KuGou (id + accesskey).
 * Safe no-op when context is null (isolated unit tests).
 */
final class LyricChoiceStore {
    static final class Choice {
        final String provider, extId, extKey;
        final long id;
        private Choice(String provider, long id, String extId, String extKey) {
            this.provider = provider;
            this.id = id;
            this.extId = extId;
            this.extKey = extKey;
        }
        String key() {
            return provider + ":" + (id > 0 ? Long.toString(id) : extId);
        }
        static Choice lrclib(long id) {
            return id > 0 ? new Choice("LRCLIB", id, "", "") : null;
        }
        static Choice lrcRed(String isrc) {
            if (isrc == null) return null;
            String clean = isrc.trim().toUpperCase(Locale.ROOT);
            return ExtraLyricSources.validIsrc(clean) ? new Choice("LrcRed", 0L, clean, "") : null;
        }
        static Choice kugou(String id, String accessKey) {
            if (id == null || accessKey == null) return null;
            String cid = id.trim(), key = accessKey.trim();
            return !ExtraLyricSources.kugouDownloadUrl(cid, key).isEmpty()
                ? new Choice("KuGou", 0L, cid, key) : null;
        }
        static Choice fromCandidate(LyricCandidates.Candidate c) {
            if (c == null) return null;
            if ("LrcRed".equals(c.provider)) return lrcRed(c.extId);
            if ("KuGou".equals(c.provider)) return kugou(c.extId, c.extKey);
            return lrclib(c.id);
        }
    }

    private static final String PREFS = "liquid_lyric_choices";
    private static final String KEY_ITEMS = "items_v1";
    private static final int MAX_ENTRIES = 64;

    private static boolean loaded;
    private static final LinkedHashMap<String, Choice> MAP = new LinkedHashMap<String, Choice>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Choice> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private LyricChoiceStore() {}

    static String songKey(String title, String artist, long durationMs) {
        if (title == null || artist == null || durationMs < 1000 || durationMs > 3600000) return "";
        String t = normalize(title);
        String a = normalize(artist);
        if (t.isEmpty() || a.isEmpty() || t.length() > 256 || a.length() > 256) return "";
        long bucket = Math.round(durationMs / 2000.0);
        return t + "\t" + a + "\t" + bucket;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
            .trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    static synchronized long get(Context context, String title, String artist, long durationMs) {
        Choice c = getChoice(context, title, artist, durationMs);
        return c != null && "LRCLIB".equals(c.provider) ? c.id : 0L;
    }

    static synchronized Choice getChoice(Context context, String title, String artist, long durationMs) {
        if (context == null) return null;
        ensureLoaded(context);
        String k = songKey(title, artist, durationMs);
        if (k.isEmpty()) return null;
        Choice c = MAP.get(k);
        if (c != null) return c;
        String prefix = normalize(title) + "\t" + normalize(artist) + "\t";
        long center = Math.round(durationMs / 2000.0);
        for (long delta : new long[]{-1L, 1L}) {
            Choice near = MAP.get(prefix + (center + delta));
            if (near != null) return near;
        }
        return null;
    }

    static synchronized void put(Context context, String title, String artist, long durationMs, long recordId) {
        putChoice(context, title, artist, durationMs, Choice.lrclib(recordId));
    }

    static synchronized void putChoice(Context context, String title, String artist, long durationMs, Choice choice) {
        if (context == null || choice == null) return;
        ensureLoaded(context);
        String k = songKey(title, artist, durationMs);
        if (k.isEmpty()) return;
        clearNearLocked(title, artist, durationMs);
        MAP.put(k, choice);
        persistLocked(context);
    }

    static synchronized void clear(Context context, String title, String artist, long durationMs) {
        if (context == null) return;
        ensureLoaded(context);
        if (clearNearLocked(title, artist, durationMs)) {
            persistLocked(context);
        }
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

    private static boolean clearNearLocked(String title, String artist, long durationMs) {
        String prefix = normalize(title) + "\t" + normalize(artist) + "\t";
        long center = Math.round(durationMs / 2000.0);
        boolean removed = false;
        for (long d = -1L; d <= 1L; d++) {
            if (MAP.remove(prefix + (center + d)) != null) removed = true;
        }
        return removed;
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
                if (k.isEmpty() || k.length() > 540) continue;
                String provider = item.optString("p", "LRCLIB");
                Choice parsed;
                if ("LrcRed".equals(provider)) {
                    parsed = Choice.lrcRed(item.optString("e", ""));
                } else if ("KuGou".equals(provider)) {
                    parsed = Choice.kugou(item.optString("e", ""), item.optString("x", ""));
                } else {
                    parsed = Choice.lrclib(item.optLong("id", 0L));
                }
                if (parsed != null) MAP.put(k, parsed);
            }
        } catch (Exception ignored) {
            MAP.clear();
        }
    }

    private static void persistLocked(Context context) {
        try {
            JSONArray arr = new JSONArray();
            for (Map.Entry<String, Choice> e : MAP.entrySet()) {
                Choice c = e.getValue();
                JSONObject obj = new JSONObject().put("k", e.getKey());
                if ("LRCLIB".equals(c.provider)) {
                    obj.put("id", c.id);
                } else {
                    obj.put("p", c.provider).put("e", c.extId);
                    if (!c.extKey.isEmpty()) obj.put("x", c.extKey);
                }
                arr.put(obj);
            }
            context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ITEMS, arr.toString()).apply();
        } catch (Exception ignored) {}
    }
}
