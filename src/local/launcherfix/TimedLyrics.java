package local.launcherfix;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.util.*;

/** Bounded safe reader for Lyricsfile 1.0. Unusable word data keeps line-level text. */
final class TimedLyrics {
    static Lyrics parse(String source,String title,String artist) {
        if (source == null || source.length()>262144) throw new IllegalArgumentException("Lyricsfile size");
        LoaderOptions options=new LoaderOptions();
        options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(0);
        options.setAllowRecursiveKeys(false); options.setNestingDepthLimit(12); options.setCodePointLimit(262144);
        try {
            Map<?,?> file=map(new Yaml(new SafeConstructor(options)).load(source));
            if (!"1.0".equals(file.get("version"))) throw new IllegalArgumentException("Lyricsfile version");
            Map<?,?> metadata=map(file.get("metadata"));
            if (!Lyrics.sameTrack(title,string(metadata.get("title"))) || !Lyrics.sameTrack(artist,string(metadata.get("artist"))))
                throw new IllegalArgumentException("Lyricsfile identity");
            if (metadata.containsKey("offset_ms") && time(metadata.get("offset_ms"))!=0) throw new IllegalArgumentException("Unspecified offset");
            List<?> lines=list(file.get("lines"));
            if (lines.isEmpty() || lines.size()>2048) throw new IllegalArgumentException("Lyricsfile lines");
            TreeMap<Long,Map<?,?>> sorted=new TreeMap<>(); int total=0;
            for (Object entry:lines) {
                Map<?,?> line=map(entry); String text=string(line.get("text"));
                total+=text.length();
                if (text.length()>512 || total>65536 || sorted.put(time(line.get("start_ms")),line)!=null)
                    throw new IllegalArgumentException("Lyricsfile text bounds");
            }
            long[] times=new long[sorted.size()]; String[] texts=new String[sorted.size()];
            Lyrics.Word[][] cues=new Lyrics.Word[sorted.size()][]; int i=0, wordCount=0;
            for (Map.Entry<Long,Map<?,?>> entry:sorted.entrySet()) {
                Map<?,?> line=entry.getValue(); times[i]=entry.getKey(); texts[i]=string(line.get("text"));
                long end=line.containsKey("end_ms") ? time(line.get("end_ms")) : -1;
                if (end>=0 && end<times[i]) throw new IllegalArgumentException("Line end before start");
                if (line.get("words")!=null) {
                    List<?> words=list(line.get("words")); wordCount+=words.size();
                    if (wordCount>8192 || words.size()>512) throw new IllegalArgumentException("Too many words");
                    try { cues[i]=words(words,texts[i],times[i],end); }
                    catch (IllegalArgumentException invalidWordTiming) { cues[i]=null; }
                }
                i++;
            }
            return Lyrics.timed(times,texts,cues);
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid Lyricsfile",invalid); }
    }
    private static Lyrics.Word[] words(List<?> data,String text,long lineStart,long lineEnd) {
        Lyrics.Word[] result=new Lyrics.Word[data.size()]; StringBuilder joined=new StringBuilder();
        for (int i=0;i<data.size();i++) {
            Map<?,?> word=map(data.get(i)); String part=string(word.get("text")); int from=joined.length(); joined.append(part);
            long start=time(word.get("start_ms"));
            long end=word.containsKey("end_ms") ? time(word.get("end_ms"))
                : i+1<data.size() ? time(map(data.get(i+1)).get("start_ms")) : lineEnd>=0 ? lineEnd : start;
            if (part.isEmpty() || start<lineStart || end<start || lineEnd>=0 && end>lineEnd
                    || i>0 && start<result[i-1].end) throw new IllegalArgumentException("Invalid word timing");
            result[i]=new Lyrics.Word(from,joined.length(),start,end);
        }
        if (!joined.toString().equals(text)) throw new IllegalArgumentException("Word text mismatch");
        return result;
    }
    private static long time(Object value) {
        if (!(value instanceof Long) && !(value instanceof Integer)) throw new IllegalArgumentException("Noninteger time");
        long time=((Number)value).longValue();
        if (time<0 || time>86400000) throw new IllegalArgumentException("Time out of range");
        return time;
    }
    private static String string(Object value) { if (!(value instanceof String)) throw new IllegalArgumentException("Not text"); return (String)value; }
    private static Map<?,?> map(Object value) { if (!(value instanceof Map)) throw new IllegalArgumentException("Not a mapping"); return (Map<?,?>)value; }
    private static List<?> list(Object value) { if (!(value instanceof List)) throw new IllegalArgumentException("Not a list"); return (List<?>)value; }
}
