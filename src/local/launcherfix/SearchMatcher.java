package local.launcherfix;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Normalize once per app, and once per query; filtering does no package or image work. */
public final class SearchMatcher {
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private SearchMatcher() {}

    public static String normalize(String value) {
        if (value == null) return "";
        String text = Normalizer.normalize(value, Normalizer.Form.NFD).toLowerCase(Locale.ROOT);
        return SPACE.matcher(MARKS.matcher(text).replaceAll("").replace('\u0111', 'd')).replaceAll(" ").trim();
    }

    public static boolean matches(String normalizedName, String normalizedQuery) {
        if (normalizedQuery.isEmpty()) return true;
        for (String word : normalizedQuery.split(" ")) if (!normalizedName.contains(word)) return false;
        return true;
    }
}
