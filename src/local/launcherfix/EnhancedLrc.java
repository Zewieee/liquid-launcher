package local.launcherfix;

import java.util.*;
import java.util.regex.*;

/** Enhanced LRC uses supplied word onsets; an unbounded last word steps at its real onset. */
final class EnhancedLrc {
    private static final Pattern LINE=Pattern.compile("\\[(\\d{1,3}:[0-5]\\d(?:\\.\\d{1,3})?)\\]");
    private static final Pattern WORD=Pattern.compile("<(\\d{1,3}:[0-5]\\d(?:\\.\\d{1,3})?)>");
    private static final Pattern OFFSET=Pattern.compile("\\[offset:([^\\]]+)\\]",Pattern.CASE_INSENSITIVE);
    static Lyrics parse(String value,long duration) {
        if (value.length()>65536) throw new IllegalArgumentException("LRC size");
        Matcher offset=OFFSET.matcher(value);
        while(offset.find()) if(!offset.group(1).trim().equals("0")) throw new IllegalArgumentException("Unsupported LRC offset");
        TreeMap<Long,String> rows=new TreeMap<>(); Map<Long,Lyrics.Word[]> cues=new HashMap<>(); int count=0,wordCount=0,total=0;
        for(String raw:value.split("\\r?\\n")) {
            String line=raw.trim(); Matcher stamp=LINE.matcher(line); ArrayList<Long> starts=new ArrayList<>(); int end=0;
            while(stamp.find() && stamp.start()==end) {
                long time=ProviderLyrics.time(stamp.group(1));
                if(++count>2048 || time>duration+2000) throw new IllegalArgumentException("LRC time/count bounds");
                starts.add(time);end=stamp.end();
            }
            if(starts.isEmpty())continue;
            String body=line.substring(end).trim(); int background=body.indexOf("[bg:"); if(background>=0)body=body.substring(0,background);
            Matcher word=WORD.matcher(body); StringBuilder text=new StringBuilder();
            ArrayList<Integer> offsets=new ArrayList<>(); ArrayList<Long> times=new ArrayList<>(); end=0;
            while(word.find()) {
                if(++wordCount>8192)throw new IllegalArgumentException("Too many LRC words");
                text.append(body.substring(end,word.start())); offsets.add(text.length()); times.add(ProviderLyrics.time(word.group(1)));end=word.end();
            }
            text.append(body.substring(end));total+=text.length();
            if(text.length()>512 || total>65536)throw new IllegalArgumentException("LRC text bounds");
            ArrayList<Lyrics.Word> words=new ArrayList<>();
            if(starts.size()==1 && !times.isEmpty() && text.substring(0,offsets.get(0)).trim().isEmpty()) {
                for(int i=0;i<times.size();i++) {
                    int from=offsets.get(i),to=i+1<offsets.size() ? offsets.get(i+1) : text.length();
                    long start=times.get(i),finish=i+1<times.size() ? times.get(i+1) : start;
                    if(start<starts.get(0) || finish<start || finish>duration+2000)throw new IllegalArgumentException("LRC word order/range");
                    if(to>from)words.add(new Lyrics.Word(from,to,start,finish));
                }
            }
            for(long start:starts) if(!rows.containsKey(start)) {rows.put(start,text.toString());cues.put(start,words.toArray(new Lyrics.Word[0]));}
        }
        long[] times=new long[rows.size()];String[] text=new String[rows.size()];Lyrics.Word[][] words=new Lyrics.Word[rows.size()][];int index=0;
        for(Map.Entry<Long,String> row:rows.entrySet()){times[index]=row.getKey();text[index]=row.getValue();words[index]=cues.get(row.getKey());index++;}
        return Lyrics.timed(times,text,words);
    }
}
