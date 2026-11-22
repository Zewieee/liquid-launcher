package local.launcherfix;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;

/** Bounded 8-package MRU list for surfacing recently used apps at the top of AppSearch. */
final class RecentApps {
    private static final String PREFS = "liquid_recent_apps";
    private static final String KEY_PACKAGES = "packages_v1";
    private static final int MAX_RECENT = 8;

    private static boolean loaded;
    private static final ArrayList<String> PACKAGES = new ArrayList<>(MAX_RECENT);

    private RecentApps() {}

    static synchronized void record(Context context, String packageName) {
        if (context == null || packageName == null || !MediaSources.validPackage(packageName)) return;
        if (packageName.equals(context.getPackageName())) return;
        ensureLoaded(context);
        if (!PACKAGES.isEmpty() && packageName.equals(PACKAGES.get(0))) return;
        PACKAGES.remove(packageName);
        PACKAGES.add(0, packageName);
        while (PACKAGES.size() > MAX_RECENT) {
            PACKAGES.remove(PACKAGES.size() - 1);
        }
        persistLocked(context);
    }

    static synchronized List<String> list(Context context) {
        if (context == null) return new ArrayList<>();
        ensureLoaded(context);
        return new ArrayList<>(PACKAGES);
    }

    static synchronized void resetForTests(Context context) {
        PACKAGES.clear();
        loaded = true;
        if (context != null) {
            context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_PACKAGES).commit();
        }
    }

    private static void ensureLoaded(Context context) {
        if (loaded) return;
        loaded = true;
        try {
            SharedPreferences sp = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY_PACKAGES, "");
            if (raw == null || raw.isEmpty()) return;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length() && PACKAGES.size() < MAX_RECENT; i++) {
                String pkg = arr.optString(i, "");
                if (MediaSources.validPackage(pkg) && !pkg.equals(context.getPackageName()) && !PACKAGES.contains(pkg)) {
                    PACKAGES.add(pkg);
                }
            }
        } catch (Exception ignored) {
            PACKAGES.clear();
        }
    }

    private static void persistLocked(Context context) {
        try {
            JSONArray arr = new JSONArray();
            for (String pkg : PACKAGES) arr.put(pkg);
            context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_PACKAGES, arr.toString()).apply();
        } catch (Exception ignored) {}
    }
}
