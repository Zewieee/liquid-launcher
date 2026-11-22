package local.launcherfix;

import android.content.Context;
import android.content.SharedPreferences;

/** One visual configuration; no low-memory mode or mode selector. */
public final class SingleMode {
    public static final String MIGRATION_KEY = "single_mode_migration";
    private SingleMode() {}

    public static void initialize(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("launcher_settings", 0);
        boolean first = prefs.getInt(MIGRATION_KEY, 0) < 1;
        boolean legacy = prefs.contains("launcher_lite_mode");
        if (!first && !legacy) return;
        SharedPreferences.Editor editor = prefs.edit().remove("launcher_lite_mode");
        if (first) {
            // The user chose full effects. Preserve unrelated settings, including
            // an explicitly saved reduced-motion accessibility preference.
            editor.putBoolean("animations", true)
                .putBoolean("advanced_effects", true)
                .putBoolean("focus_overshoot", true)
                .putInt(MIGRATION_KEY, 1)
                .putLong("settings_revision", prefs.getLong("settings_revision", 0) + 1);
        }
        if (!editor.commit()) android.util.Log.w("TvHome.SingleMode", "Visual settings migration was not persisted; retry on next process start");
    }

    public static float factor(int width) {
        return Math.min(.55f, 864f / Math.max(1, width));
    }
}
