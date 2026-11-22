package local.launcherfix;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/** Verifies persistent LyricChoiceStore, LyricCandidates.selectedById, and RecentApps MRU behavior. */
public final class PersistenceAndRecentCheck {
    static int count;
    static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        count++;
    }

    static final class MemoryPrefs implements SharedPreferences {
        final Map<String, Object> data = new HashMap<>();
        @Override public Map<String, ?> getAll() { return new HashMap<>(data); }
        @Override public String getString(String k, String d) { Object v = data.get(k); return v instanceof String ? (String) v : d; }
        @Override @SuppressWarnings("unchecked") public Set<String> getStringSet(String k, Set<String> d) { Object v = data.get(k); return v instanceof Set ? (Set<String>) v : d; }
        @Override public int getInt(String k, int d) { Object v = data.get(k); return v instanceof Integer ? (Integer) v : d; }
        @Override public long getLong(String k, long d) { Object v = data.get(k); return v instanceof Long ? (Long) v : d; }
        @Override public float getFloat(String k, float d) { Object v = data.get(k); return v instanceof Float ? (Float) v : d; }
        @Override public boolean getBoolean(String k, boolean d) { Object v = data.get(k); return v instanceof Boolean ? (Boolean) v : d; }
        @Override public boolean contains(String k) { return data.containsKey(k); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        @Override public Editor edit() {
            return new Editor() {
                final Map<String, Object> pending = new HashMap<>();
                boolean clear;
                @Override public Editor putString(String k, String v) { pending.put(k, v); return this; }
                @Override public Editor putStringSet(String k, Set<String> v) { pending.put(k, v); return this; }
                @Override public Editor putInt(String k, int v) { pending.put(k, v); return this; }
                @Override public Editor putLong(String k, long v) { pending.put(k, v); return this; }
                @Override public Editor putFloat(String k, float v) { pending.put(k, v); return this; }
                @Override public Editor putBoolean(String k, boolean v) { pending.put(k, v); return this; }
                @Override public Editor remove(String k) { pending.put(k, null); return this; }
                @Override public Editor clear() { clear = true; return this; }
                @Override public boolean commit() { apply(); return true; }
                @Override public void apply() {
                    if (clear) data.clear();
                    for (Map.Entry<String, Object> e : pending.entrySet()) {
                        if (e.getValue() == null) data.remove(e.getKey());
                        else data.put(e.getKey(), e.getValue());
                    }
                }
            };
        }
    }

    static final class FakeContext extends ContextWrapper {
        final Map<String, MemoryPrefs> stores = new HashMap<>();
        FakeContext() { super(null); }
        @Override public Context getApplicationContext() { return this; }
        @Override public String getPackageName() { return "com.gathin.tvhome.liquid"; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            MemoryPrefs p = stores.get(name);
            if (p == null) { p = new MemoryPrefs(); stores.put(name, p); }
            return p;
        }
    }

    public static void main(String[] args) throws Exception {
        FakeContext ctx = new FakeContext();
        LyricChoiceStore.resetForTests(ctx);
        RecentApps.resetForTests(ctx);

        // 1. LyricChoiceStore persistence across reload, normalization, and +/- 1.5s player duration jitter.
        check(LyricChoiceStore.get(ctx, "プロポーズ", "9Lana", 180000) == 0L, "empty initially");
        LyricChoiceStore.put(ctx, "  プロポーズ  ", "9lana", 180200, 9592692L);
        check(LyricChoiceStore.get(ctx, "プロポーズ", "9Lana", 180000) == 9592692L, "normalized exact match");
        check(LyricChoiceStore.get(ctx, "プロポーズ", "9Lana", 181600) == 9592692L, "adjacent duration bucket match");
        LyricChoiceStore.reloadForTests(ctx);
        check(LyricChoiceStore.get(ctx, "プロポーズ", "9Lana", 180000) == 9592692L, "persisted across process reload");

        // 2. Clearing restores 0 (Automatic).
        LyricChoiceStore.clear(ctx, "プロポーズ", "9Lana", 181200);
        LyricChoiceStore.reloadForTests(ctx);
        check(LyricChoiceStore.get(ctx, "プロポーズ", "9Lana", 180000) == 0L, "cleared across reload");

        // 3. 64-entry LRU eviction keeps newest & touched entries.
        LyricChoiceStore.put(ctx, "Pinned Song", "Artist", 120000, 777L);
        for (int i = 1; i <= 70; i++) {
            if (i == 40) LyricChoiceStore.get(ctx, "Pinned Song", "Artist", 120000); // touch LRU
            LyricChoiceStore.put(ctx, "Song " + i, "Artist", 120000, 1000L + i);
        }
        LyricChoiceStore.reloadForTests(ctx);
        check(LyricChoiceStore.get(ctx, "Song 1", "Artist", 120000) == 0L, "oldest LRU evicted");
        check(LyricChoiceStore.get(ctx, "Pinned Song", "Artist", 120000) == 777L, "touched entry retained");
        check(LyricChoiceStore.get(ctx, "Song 70", "Artist", 120000) == 1070L, "newest entry retained");

        // 4. LyricCandidates.selectedById strict validation & cue bounds check.
        JSONObject validRecord = new JSONObject()
            .put("id", 555L)
            .put("trackName", "Believe in you")
            .put("artistName", "nonoc")
            .put("albumName", "Album")
            .put("duration", 278.0)
            .put("syncedLyrics", "[00:10.00]Line one\n[01:20.00]Line two");
        Lyrics restored = LyricCandidates.selectedById(validRecord, 555L, "Believe in you", "nonoc", 279000L);
        check(restored != null && restored.isSynced() && restored.size() == 2 && "LRCLIB".equals(restored.source()),
            "selectedById accepts valid manual record");
        check(LyricCandidates.selectedById(validRecord, 556L, "Believe in you", "nonoc", 279000L) == null,
            "selectedById rejects mismatched ID");
        check(LyricCandidates.selectedById(validRecord, 555L, "Other Song", "nonoc", 279000L) == null,
            "selectedById rejects mismatched title");
        JSONObject outOfBounds = new JSONObject(validRecord.toString()).put("syncedLyrics", "[05:30.00]Past track end");
        check(LyricCandidates.selectedById(outOfBounds, 555L, "Believe in you", "nonoc", 279000L) == null,
            "selectedById rejects cue past track duration");

        // 5. RecentApps MRU ordering, deduplication, self-exclusion, and 8-app bound.
        RecentApps.record(ctx, "com.gathin.tvhome.liquid");
        check(RecentApps.list(ctx).isEmpty(), "launcher excludes itself");
        RecentApps.record(ctx, "../invalid");
        check(RecentApps.list(ctx).isEmpty(), "invalid package ignored");
        RecentApps.record(ctx, "com.spotify.tv.android");
        RecentApps.record(ctx, "com.mochyy.mochymusic");
        RecentApps.record(ctx, "org.smarttube.beta");
        RecentApps.record(ctx, "com.spotify.tv.android"); // promote back to index 0
        List<String> mru = RecentApps.list(ctx);
        check(mru.size() == 3 && "com.spotify.tv.android".equals(mru.get(0))
            && "org.smarttube.beta".equals(mru.get(1))
            && "com.mochyy.mochymusic".equals(mru.get(2)), "MRU promotion and deduplication");
        for (int i = 0; i < 10; i++) RecentApps.record(ctx, "example.app.num" + i);
        check(RecentApps.list(ctx).size() == 8 && "example.app.num9".equals(RecentApps.list(ctx).get(0)),
            "RecentApps bounded to 8");

        System.out.println("PERSISTENCE_RECENT_CHECK_OK cases=" + count);
    }
}
