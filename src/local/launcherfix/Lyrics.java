package local.launcherfix;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable line-timed lyrics. No UI or network work is needed to follow a playback clock. */
final class Lyrics {
    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):([0-5]\\d)(?:\\.(\\d{1,3}))?\\]");
    private final long[] times;
    private final String[] lines;
    private final String[] romaji;
    private Word[][] words, romajiWords;
    private Ruby[][] rubies;
    private boolean synced=true;
    boolean isSynced() { return synced; }
    private String source="LRCLIB";
    private String sourceUrl="", contributor="", contributorUrl="";
    private int quality;
    String source() { return source; }
    String sourceUrl() { return sourceUrl; }
    String contributor() { return contributor; }
    String contributorUrl() { return contributorUrl; }
    int quality() { return quality; }
    Lyrics withQuality(int score) {
        Lyrics result=fromSource(source); result.quality=Math.max(0,score); return result;
    }
    Lyrics withCredit(String url,String name,String profile) {
        Lyrics result=fromSource(source); result.sourceUrl=url; result.contributor=name; result.contributorUrl=profile; return result;
    }
    Lyrics fromSource(String name) {
        Lyrics result=new Lyrics(times,lines,romaji);
        result.words=words; result.romajiWords=romajiWords; result.rubies=rubies; result.source=name; result.synced=synced;
        result.sourceUrl=sourceUrl; result.contributor=contributor; result.contributorUrl=contributorUrl;
        result.quality=quality; return result;
    }
    boolean hasWords() {
        if (words!=null) for (Word[] line:words) if (line!=null && line.length>0) return true;
        return false;
    }
    static final class Word {
        final int from, to;
        final long start, end;
        Word(int from,int to,long start,long end) { this.from=from; this.to=to; this.start=start; this.end=end; }
    }
    static final class Ruby {
        final int from, to;
        final String surface, romaji;
        Ruby(int from,int to,String surface,String romaji) {
            this.from=from; this.to=to; this.surface=surface; this.romaji=romaji;
        }
    }
    private Lyrics(long[] times, String[] lines) { this(times, lines, null); }
    private Lyrics(long[] times, String[] lines, String[] romaji) { this.times=times; this.lines=lines; this.romaji=romaji; }
    Lyrics withRomaji(String[] readings) {
        return withRomaji(readings,null,null);
    }
    Lyrics withRomaji(String[] readings, Word[][] mapped) {
        return withRomaji(readings,mapped,null);
    }
    Lyrics withRomaji(String[] readings, Word[][] mapped, Ruby[][] rubies) {
        if (readings.length != lines.length) throw new IllegalArgumentException("Invalid reading count");
        Lyrics result=new Lyrics(times, lines, readings.clone());
        result.words=words; result.romajiWords=mapped; result.rubies=rubies; result.source=source; result.synced=synced;
        result.sourceUrl=sourceUrl; result.contributor=contributor; result.contributorUrl=contributorUrl;
        result.quality=quality; return result;
    }
    Ruby[] rubiesAt(int index) { return rubies != null && index >= 0 && index < rubies.length ? rubies[index] : null; }
    static Lyrics timed(long[] times,String[] lines,Word[][] words) {
        Lyrics result=new Lyrics(times,lines); result.words=words; return result;
    }
    Word[] wordsAt(int index) { return words != null && index >= 0 && index < words.length ? words[index] : null; }
    boolean isActivelySweeping(int index, long position) {
        if (position < 0) return false;
        Word[] active = wordsAt(index);
        if (active == null || active.length == 0) return false;
        for (Word word : active) {
            if (position >= word.start - 32 && position <= word.end + 48) return true;
        }
        return false;
    }
    long refreshDelay(int index,long position) {
        long delay=1000;
        if (position<0) return delay;
        if (index+1>=0 && index+1<times.length) delay=Math.min(delay,Math.max(1,times[index+1]-position));
        Word[] active=wordsAt(index);
        if (active!=null) for (Word word:active) {
            if (position<word.start) delay=Math.min(delay,Math.max(1,word.start-position));
            else if (position<word.end) delay=Math.min(delay,50);
        }
        return delay;
    }
    double coverageAt(int index,long position,boolean reading) {
        Word[][] source=reading ? romajiWords : words;
        if (source == null || index < 0 || index >= source.length || source[index] == null || source[index].length == 0 || position < 0)
            return Double.NaN;
        String text=reading ? romajiAt(index) : at(index); double covered=0;
        for (Word word:source[index]) {
            if (position < word.start) break;
            int from=text.codePointCount(0,word.from), to=text.codePointCount(0,word.to);
            double progress=word.end <= word.start || position >= word.end ? 1 : (position-word.start)/(double)(word.end-word.start);
            covered=Math.max(covered,from+(to-from)*progress);
        }
        return covered;
    }
    String romajiAt(int index) { return romaji != null && index >= 0 && index < romaji.length ? romaji[index] : ""; }
    static Lyrics parse(String lrc) {
        if (lrc == null) lrc = "";
        if (lrc.length() > 65536) throw new IllegalArgumentException("Lyrics too large");
        TreeMap<Long, String> sorted = new TreeMap<>();
        int count = 0;
        for (String raw : lrc.split("\\r?\\n")) {
            Matcher matcher = STAMP.matcher(raw.trim());
            ArrayList<Long> stamps = new ArrayList<>();
            int end = 0;
            while (matcher.find() && matcher.start() == end) {
                String fraction = matcher.group(3);
                long ms = fraction == null ? 0 : Long.parseLong((fraction + "00").substring(0, 3));
                stamps.add((Long.parseLong(matcher.group(1))*60 + Long.parseLong(matcher.group(2)))*1000 + ms);
                end = matcher.end();
                if (++count > 2048) throw new IllegalArgumentException("Too many lyric timestamps");
            }
            String value = raw.trim().substring(end).trim();
            if (value.length() > 512) value = value.substring(0, 512);
            for (Long stamp : stamps) {
                String previous = sorted.get(stamp);
                String combined = previous == null || previous.isEmpty() ? value
                    : value.isEmpty() || previous.equals(value) ? previous : previous + "\n" + value;
                sorted.put(stamp, combined.substring(0, Math.min(512, combined.length())));
            }
        }
        long[] times = new long[sorted.size()]; String[] lines = new String[sorted.size()]; int i=0;
        for (java.util.Map.Entry<Long,String> item : sorted.entrySet()) {
            times[i]=item.getKey(); lines[i]=item.getValue(); i++;
        }
        return new Lyrics(times, lines);
    }
    static Lyrics plain(String text) {
        if (text==null) text="";
        if (text.length()>65536) throw new IllegalArgumentException("Lyrics too large");
        String trimmed=text.trim(); ArrayList<String> lines=new ArrayList<>();
        if (!trimmed.isEmpty()) for (String line:trimmed.split("\\r?\\n",-1)) {
            if (line.length()>512 || lines.size()>=2048) throw new IllegalArgumentException("Plain lyrics bounds");
            lines.add(line.trim());
        }
        Lyrics result=new Lyrics(new long[0],lines.toArray(new String[0])); result.synced=false; return result;
    }
    int size() { return lines.length; }
    long startAt(int index) { return synced ? times[index] : -1; }
    long lastTimestamp() {
        if (!synced || times.length == 0) return -1L;
        long last = times[times.length - 1];
        for (int i = times.length - 1; i >= 0; i--) {
            Word[] row = wordsAt(i);
            if (row != null && row.length > 0) {
                last = Math.max(last, row[row.length - 1].end);
                break;
            }
        }
        return last;
    }
    String at(int index) { return index >= 0 && index < lines.length ? lines[index] : ""; }
    int indexAt(long position) {
        if (position < 0) return -1;
        int low=0, high=times.length-1, result=-1;
        while (low <= high) {
            int middle=(low+high) >>> 1;
            if (times[middle] <= position) { result=middle; low=middle+1; } else high=middle-1;
        }
        return result;
    }
    static boolean sameTrack(String left, String right) { return normalized(left).equals(normalized(right)); }
    private static String normalized(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
