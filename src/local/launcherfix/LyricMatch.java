package local.launcherfix;

import com.atilika.kuromoji.ipadic.Tokenizer;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded fallback matching; version words are retained to distinguish live/remix/TV-size. */
final class LyricMatch {
    private static final Pattern REMASTER = Pattern.compile(
        "(?i)\\s*(?:-\\s*|\\()(?:(?:19|20)\\d{2}\\s+)?remaster(?:ed)?(?:\\s+(?:19|20)\\d{2})?\\)?\\s*$");
    private static final Pattern BRACKET_CREDITS = Pattern.compile(
        "(?i)\\s*[(\\[\\u3010]\\s*(?:feat\\.?|ft\\.?|featuring|with|prod\\.?\\s*by)\\b[^)\\]\\u3011]*[)\\]\\u3011]");
    private static final Pattern TRAILING_CREDITS = Pattern.compile(
        "(?i)\\s+(?:feat|ft|featuring)\\.?\\s+.*$");
    private static final Pattern UPLOAD_LABEL = Pattern.compile(
        "(?i)\\s*[(\\[\\u3010]\\s*(?:official\\s*)?(?:music\\s*)?(?:video|audio(?:\\s+archives?)?|visuali[sz]er|lyrics?\\s*video|lyrics?|performance\\s*video|special\\s*video|dance\\s*practice|track\\s*video|color\\s*coded(?:\\s*lyrics?)?|kan\\s*/\\s*rom(?:\\s*/\\s*eng)?|(?:viet|eng)\\s*sub(?:s|titled)?|m/?v|hd|hq|4k|full\\s*song)\\s*[)\\]\\u3011]");
    private static final Pattern TRAILING_UPLOAD_LABEL = Pattern.compile(
        "(?i)(?:\\s*[-–—/|]\\s*|\\s+|(?<=[\\u300d\\u300f\\u3011)\\]])\\s*)(?:official\\s+(?:music\\s+video|lyric\\s+video|performance\\s+video|video|audio|visuali[sz]er|m/?v)|music\\s+video|lyric\\s+video)\\s*$");
    private static final Pattern OFFICIAL_TAG = Pattern.compile(
        "(?i)\\s*[(\\[\\u3010]\\s*official\\s*[)\\]\\u3011]");
    private static final Pattern LEADING_BRACKET = Pattern.compile(
        "^\\s*[\\u3010\\[]([^\\u3011\\]]{1,64})[\\u3011\\]]\\s*");
    private static final Pattern CORNER_QUOTED = Pattern.compile(
        "^([^\\u300c\\u300e]*)[\\u300c\\u300e]([^\\u300d\\u300f]{1,160})[\\u300d\\u300f]([^\\u300c\\u300e]*)$");
    private static final Pattern TRACK_NUMBER = Pattern.compile("^\\d{1,2}\\s*[.\\-]\\s+");
    private static final Pattern CATALOG_CODE = Pattern.compile(
        "(?i)\\s*[(\\[]\\s*[A-Z]{2,8}-\\d{2,5}\\b[^)\\]]*[)\\]]");
    private static final Pattern VERSION_WORDS = Pattern.compile(
        "(?i)\\b(?:live|remix|remixed|mix|acoustic|unplugged|instrumental|karaoke|a?cap+ella|demo|edit|version|ver|cover|sped|slowed|reverb|nightcore|lofi|orchestral|extended|tv\\s*size|short)\\b");
    private static final Pattern BILINGUAL_SEP = Pattern.compile("\\s+(?:-|–|—|/)\\s+");
    private static final Pattern ARTIST_PREFIX_SEP = Pattern.compile("\\s+(?:-|–|—|/|:)\\s+");
    private static final Pattern ARTIST_SPLIT = Pattern.compile(
        "(?i)\\s*(?:[,;]|\\s+&\\s+|\\s+(?:feat\\.?|ft\\.?|featuring|with)\\s+)\\s*");
    private static final Pattern ARTIST_CHANNEL_SUFFIX = Pattern.compile(
        "(?i)^(.{2,}?)(?:\\s*-\\s*topic|\\s+official(?:\\s+youtube)?(?:\\s+channel|\\s+music)?|(?:\\s+|(?<=[a-z0-9]))vevo)\\s*$");

    private Tokenizer tokenizer;
    private final Map<String,String> readings=new HashMap<>();

    static boolean hasVersionWords(String text) {
        return text != null && VERSION_WORDS.matcher(text).find();
    }

    static String cleanTitle(String text) {
        if (text == null) return "";
        String original = Normalizer.normalize(text, Normalizer.Form.NFKC).trim();
        String name = original;
        for (int pass = 0; pass < 4; pass++) {
            String next = REMASTER.matcher(name).replaceFirst("").trim();
            next = stripUnlessVersion(next, BRACKET_CREDITS);
            next = stripUnlessVersion(next, UPLOAD_LABEL);
            next = stripUnlessVersion(next, OFFICIAL_TAG);
            next = stripUnlessVersion(next, TRAILING_CREDITS);
            next = stripTrailingUploadLabel(next);
            next = trimTrailingSeparators(next);
            Matcher corner = CORNER_QUOTED.matcher(next);
            if (corner.matches() && trimSeparators(corner.group(1)).isEmpty() && trimSeparators(corner.group(3)).isEmpty()) {
                String inner = corner.group(2).trim();
                if (!inner.isEmpty()) next = inner;
            }
            if (next.equals(name)) break;
            name = next;
        }
        return name.isEmpty() ? original : name;
    }

    private static String stripTrailingUploadLabel(String input) {
        Matcher m = TRAILING_UPLOAD_LABEL.matcher(input);
        if (!m.find() || VERSION_WORDS.matcher(m.group()).find()) return input;
        String prefix = input.substring(0, m.start()).trim();
        return prefix.isEmpty() ? input : prefix;
    }

    /**
     * Deeper query normalization for YouTube/doujin/Japanese MV uploads:
     * - "【FELT】02. Flower Flag (FELT-003 Flower Flag) [Audio Archives]" -> "Flower Flag"
     * - "YOASOBI「アイドル」Official Music Video" -> "アイドル"
     * - "『怪獣の花唄』- Vaundy" -> "怪獣の花唄"
     * - "Sơn Tùng M-TP - Chúng Ta Của Hiện Tại (Official MV)" -> "Chúng Ta Của Hiện Tại"
     * Never strips recording-version words (Live, Remix, TV Size, Short, Acoustic, etc.).
     */
    static String searchTitle(String title, String artist) {
        String cleaned = cleanTitle(title);
        if (cleaned.isEmpty()) return "";
        String work = cleaned;

        // 1. Leading [Artist] or 【Artist】 bracket
        Matcher lead = LEADING_BRACKET.matcher(work);
        if (lead.find()) {
            String tag = lead.group(1).trim();
            if (!artist.isEmpty() && artistTokensMatch(tag, artist) && !hasVersionWords(tag)) {
                String rest = work.substring(lead.end()).trim();
                if (!rest.isEmpty()) {
                    work = TRACK_NUMBER.matcher(rest).replaceFirst("").trim();
                }
            }
        }

        // 2. Japanese corner quotes: Artist「Title」, 「Title」/ Artist, or Artist「Title」(TV Size)
        Matcher corner = CORNER_QUOTED.matcher(work);
        if (corner.matches()) {
            String prefix = trimSeparators(corner.group(1));
            String inner = corner.group(2).trim();
            String suffix = trimSeparators(corner.group(3));
            boolean prefixMatchesArtist = prefix.isEmpty()
                || (!artist.isEmpty() && !hasVersionWords(prefix) && artistTokensMatch(prefix, artist));
            boolean suffixMatchesArtist = suffix.isEmpty()
                || (!artist.isEmpty() && !hasVersionWords(suffix) && artistTokensMatch(suffix, artist));
            if (!inner.isEmpty() && prefixMatchesArtist) {
                if (suffixMatchesArtist) {
                    work = inner;
                } else if (hasVersionWords(suffix)) {
                    work = (inner + " " + suffix).trim();
                }
            }
        }

        // 3. Catalog code parentheses like (FELT-003 Flower Flag)
        work = stripUnlessVersion(work, CATALOG_CODE);
        work = trimTrailingSeparators(work);

        // 4. Leading "Artist - Title" prefix when uploader included artist in video title
        if (!artist.isEmpty()) {
            String[] parts = ARTIST_PREFIX_SEP.split(work, 2);
            if (parts.length == 2) {
                String left = trimSeparators(parts[0]);
                String right = parts[1].trim();
                if (!left.isEmpty() && !right.isEmpty() && !hasVersionWords(left) && artistTokensMatch(left, artist)) {
                    String candidate = TRACK_NUMBER.matcher(right).replaceFirst("").trim();
                    Matcher rightCorner = CORNER_QUOTED.matcher(candidate);
                    if (rightCorner.matches() && trimSeparators(rightCorner.group(1)).isEmpty()
                            && trimSeparators(rightCorner.group(3)).isEmpty()) {
                        String inner = rightCorner.group(2).trim();
                        if (!inner.isEmpty()) candidate = inner;
                    }
                    if (!candidate.isEmpty()) work = candidate;
                }
            }
        }

        work = stripTrailingUploadLabel(work);
        work = trimTrailingSeparators(work);
        return work.isEmpty() ? cleaned : work;
    }

    private static String stripUnlessVersion(String input, Pattern pattern) {
        Matcher m = pattern.matcher(input);
        StringBuffer sb = new StringBuffer();
        boolean changed = false;
        while (m.find()) {
            if (VERSION_WORDS.matcher(m.group()).find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            } else {
                m.appendReplacement(sb, " ");
                changed = true;
            }
        }
        if (!changed) return input;
        m.appendTail(sb);
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private static String trimSeparators(String text) {
        if (text == null) return "";
        String s = text.replaceAll("\\s+", " ").trim();
        while (!s.isEmpty()) {
            char first = s.charAt(0);
            if (first == ',' || first == '-' || first == '\u2013' || first == '\u2014' || first == '/' || first == ':') {
                s = s.substring(1).trim();
            } else break;
        }
        return trimTrailingSeparators(s);
    }

    private static String trimTrailingSeparators(String text) {
        String s = text.replaceAll("\\s+", " ").trim();
        while (!s.isEmpty()) {
            char last = s.charAt(s.length() - 1);
            if (last == ',' || last == '-' || last == '\u2013' || last == '\u2014' || last == '/' || last == ':') {
                s = s.substring(0, s.length() - 1).trim();
            } else break;
        }
        return s;
    }

    private static boolean artistTokensMatch(String tag, String artist) {
        String tagKey = key(stripArtistNoise(tag));
        if (tagKey.isEmpty()) return false;
        if (tagKey.equals(key(primaryArtist(artist)))) return true;
        if (tagKey.equals(key(stripArtistNoise(artist)))) return true;
        for (String part : ARTIST_SPLIT.split(Normalizer.normalize(artist, Normalizer.Form.NFKC), 8)) {
            if (tagKey.equals(key(stripArtistNoise(part)))) return true;
        }
        for (String tagPart : ARTIST_PREFIX_SEP.split(Normalizer.normalize(tag, Normalizer.Form.NFKC), 4)) {
            String subKey = key(stripArtistNoise(tagPart));
            if (!subKey.isEmpty() && subKey.equals(key(primaryArtist(artist)))) return true;
        }
        return false;
    }

    static String stripArtistNoise(String value) {
        if (value == null) return "";
        String s = value.replaceFirst("(?i)\\s*\\(CV[.:：].*\\)\\s*$", "").trim();
        Matcher m = ARTIST_CHANNEL_SUFFIX.matcher(s);
        if (m.matches()) {
            String core = m.group(1).trim();
            if (!core.isEmpty()) s = core;
        }
        return s;
    }

    static String primaryArtist(String text) {
        if (text == null) return "";
        String norm = Normalizer.normalize(text, Normalizer.Form.NFKC).trim();
        String first = ARTIST_SPLIT.split(norm, 2)[0];
        String cleaned = stripArtistNoise(first);
        return cleaned.isEmpty() ? norm : cleaned;
    }

    private static String key(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static boolean hasHan(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.UnicodeScript.of(value.charAt(i)) == Character.UnicodeScript.HAN) return true;
        }
        return false;
    }

    String reading(String text) {
        if (!text.matches("(?s).*[\\u3041-\\u30fa\\uff66-\\uff9d].*")) return text;
        String cached = readings.get(text);
        if (cached != null) return cached;
        if (Thread.currentThread().isInterrupted()) return text;
        String result;
        if (!hasHan(text)) {
            // Pure hiragana/katakana converts in <0.1ms without cold-loading the 13MB Kuromoji dictionary.
            result = JapaneseRomaji.kana(text).trim();
        } else {
            if (tokenizer == null) tokenizer = JapaneseRomaji.obtainTokenizer();
            result = JapaneseRomaji.alias(tokenizer, text);
        }
        readings.put(text, result);
        return result;
    }

    /**
     * Collapses "JP - Romaji" bilingual duplicate titles (e.g. "イナイセカイ - Inai Sekai")
     * to the Japanese original ONLY when the Latin half matches the romaji reading of the JP half
     * and neither side carries a recording-version word.
     */
    String dedupeBilingualTitle(String text) {
        if (text == null || text.isEmpty() || VERSION_WORDS.matcher(text).find()) return text == null ? "" : text;
        String[] parts = BILINGUAL_SEP.split(text, 2);
        if (parts.length != 2) return text;
        String left = parts[0].trim(), right = parts[1].trim();
        if (left.isEmpty() || right.isEmpty()) return text;
        boolean leftJp = left.matches("(?s).*[\\u3041-\\u30fa\\uff66-\\uff9d\\u4e00-\\u9fff].*");
        boolean rightJp = right.matches("(?s).*[\\u3041-\\u30fa\\uff66-\\uff9d\\u4e00-\\u9fff].*");
        if (leftJp && !rightJp) {
            String leftKey = key(reading(left)), rightKey = key(right);
            if (!leftKey.isEmpty() && leftKey.equals(rightKey)) return left;
        } else if (rightJp && !leftJp) {
            String rightKey = key(reading(right)), leftKey = key(left);
            if (!rightKey.isEmpty() && rightKey.equals(leftKey)) return right;
        }
        return text;
    }

    private boolean equivalent(String a, String b) {
        String left = key(a), right = key(b);
        return !left.isEmpty() && (left.equals(right) || !right.isEmpty() && key(reading(a)).equals(key(reading(b))));
    }

    boolean title(String requested, String returned) {
        return title(requested, "", returned);
    }

    boolean title(String requested, String artist, String returned) {
        if (requested == null || returned == null || requested.length() > 512 || returned.length() > 512) return false;
        String cleanReq = cleanTitle(requested), cleanRet = cleanTitle(returned);
        if (equivalent(cleanReq, cleanRet)) return true;
        String searchReq = artist != null && !artist.isEmpty() ? searchTitle(requested, artist) : cleanReq;
        String searchRet = artist != null && !artist.isEmpty() ? searchTitle(returned, artist) : cleanRet;
        if ((!searchReq.equals(cleanReq) || !searchRet.equals(cleanRet)) && equivalent(searchReq, searchRet)) return true;
        String dedupReq = dedupeBilingualTitle(searchReq), dedupRet = dedupeBilingualTitle(searchRet);
        return (!dedupReq.equals(searchReq) || !dedupRet.equals(searchRet)) && equivalent(dedupReq, dedupRet);
    }

    boolean artist(String requested, String returned) {
        if (requested == null || returned == null || requested.length() > 512 || returned.length() > 512) return false;
        String normReq = stripArtistNoise(Normalizer.normalize(requested, Normalizer.Form.NFKC));
        String normRet = stripArtistNoise(Normalizer.normalize(returned, Normalizer.Form.NFKC));
        if (equivalent(normReq, normRet)) return true;
        for (String left : ARTIST_SPLIT.split(normReq, 8)) {
            String l = stripArtistNoise(left);
            if (l.isEmpty()) continue;
            for (String right : ARTIST_SPLIT.split(normRet, 8)) {
                String r = stripArtistNoise(right);
                if (!r.isEmpty() && equivalent(l, r)) return true;
            }
        }
        return false;
    }

    boolean record(String title, String artist, long duration, String returnedTitle, String returnedArtist, double seconds) {
        return record(title, artist, duration, returnedTitle, returnedArtist, seconds, 2000L);
    }

    boolean record(String title, String artist, long duration, String returnedTitle, String returnedArtist, double seconds, long toleranceMs) {
        return !Double.isNaN(seconds) && !Double.isInfinite(seconds) && Math.abs(seconds * 1000.0 - duration) <= toleranceMs
            && title(title, artist, returnedTitle) && artist(artist, returnedArtist);
    }

    /**
     * Tier-2 conditional MV duration allowance (up to +/-10s or 6% of track duration):
     * Applies only on full-length tracks (>= 120s) when both title and artist match canonically
     * and neither title contains recording-version keywords (Live, Remix, TV Size, Short, etc.).
     */
    boolean recordMvFallback(String title, String artist, long duration,
                             String returnedTitle, String returnedArtist, double seconds) {
        if (duration < 120_000L || Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds < 60.0) {
            return false;
        }
        if (hasVersionWords(title) || hasVersionWords(returnedTitle)) {
            return false;
        }
        double maxDriftMs = Math.min(10_000.0, duration * 0.06);
        return Math.abs(seconds * 1000.0 - duration) <= maxDriftMs
            && title(title, artist, returnedTitle) && artist(artist, returnedArtist);
    }

    /**
     * Detects when a short-cut lyric (e.g. 1:29 Anime TV-Size or TikTok snippet without <body dur>)
     * was fuzzy-matched onto a full-length song (>= 2m45s), leaving >75s of dead silence after <48% progress.
     */
    static boolean isTruncatedForDuration(Lyrics lyrics, long trackDurationMs) {
        if (lyrics == null || !lyrics.isSynced() || lyrics.size() < 5 || trackDurationMs < 165_000L) {
            return false;
        }
        long last = lyrics.lastTimestamp();
        return last > 0 && last < (trackDurationMs * 48L) / 100L && (trackDurationMs - last) > 75_000L;
    }

    /**
     * Multi-factor confidence score (1..1000) used to arbitrate between two lyrics of the same sync tier:
     * combines exact ISRC/catalog verification, duration delta closeness, vocal span coverage, and source precision.
     */
    static int computeQuality(Lyrics lyrics, long trackDurationMs, long candidateDurationMs, boolean exactIdMatch) {
        if (lyrics == null || lyrics.size() == 0) return 0;
        int score = 100;
        if (exactIdMatch) score += 300;
        if (trackDurationMs > 0 && candidateDurationMs > 0) {
            long delta = Math.abs(trackDurationMs - candidateDurationMs);
            if (delta <= 500L) score += 240;
            else if (delta <= 1000L) score += 205;
            else if (delta <= 2000L) score += 165;
            else if (delta <= 3500L) score += 95;
            else if (delta <= 10000L) score += 25;
        } else {
            score += 110;
        }
        if (lyrics.isSynced() && trackDurationMs >= 120_000L) {
            long last = lyrics.lastTimestamp();
            if (last >= (trackDurationMs * 62L) / 100L && last <= trackDurationMs + 3000L) {
                score += 120;
            } else if (last >= (trackDurationMs * 48L) / 100L && last <= trackDurationMs + 5000L) {
                score += 60;
            }
        }
        if (lyrics.size() >= 15) score += 40;
        else if (lyrics.size() >= 6) score += 20;

        String src = lyrics.source();
        if (src != null) {
            if (src.contains("TTML") || src.equals("LrcRed") || src.equals("BiniLyrics")) score += 30;
            else if (src.startsWith("LyricsPlus") || src.equals("Unison") || src.equals("Musixmatch")) score += 22;
            else if (src.equals("LRCLIB")) score += 15;
            else if (src.contains("KuGou")) score += 10;
        }
        return score;
    }
}
