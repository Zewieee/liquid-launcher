package local.launcherfix;

import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import javax.xml.parsers.SAXParserFactory;
import java.io.StringReader;
import java.util.*;
import java.util.regex.*;

/** Bounded TTML/QRC readers. XML never resolves a DTD or an external entity. */
final class ProviderLyrics {
    private static final Pattern QLINE=Pattern.compile("\\[(\\d+),(\\d+)\\]");
    private static final Pattern QWORD=Pattern.compile("\\((\\d+),(\\d+)\\)");
    private static final Pattern HEADER=Pattern.compile("\\[(ti|ar|offset):([^\\]]*)\\]",Pattern.CASE_INSENSITIVE);
    private static final Pattern CREDIT=Pattern.compile("^(?:作[词詞曲]|[编編]曲|制作人|製作人|Lyrics(?: by)?|Composed(?: by)?|Arranged(?: by)?|Producer)\\s*[:：].*",Pattern.CASE_INSENSITIVE);
    static long time(String value) {
        if (value==null || value.isEmpty()) throw new IllegalArgumentException("Missing time");
        double scale=1000;
        if (value.endsWith("ms")) { scale=1; value=value.substring(0,value.length()-2); }
        else if (value.endsWith("s")) value=value.substring(0,value.length()-1);
        if (!value.matches("\\d+(?:\\.\\d+)?|\\d+:[0-5]\\d(?:\\.\\d+)?|\\d+:[0-5]\\d:[0-5]\\d(?:\\.\\d+)?"))
            throw new IllegalArgumentException("Unsupported time");
        double seconds=0;
        for (String part:value.split(":")) seconds=seconds*60+Double.parseDouble(part);
        double result=seconds*scale;
        if (Double.isNaN(result) || Double.isInfinite(result) || result>86400000) throw new IllegalArgumentException("Time bounds");
        return Math.round(result);
    }
    private static long integer(String value) {
        try { long result=Long.parseLong(value); if (result<0 || result>86400000) throw new IllegalArgumentException("Time bounds"); return result; }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Time bounds",invalid); }
    }
    private static void xml(String text,DefaultHandler handler) {
        if (text==null || text.length()>262144 || Pattern.compile("<!\\s*(DOCTYPE|ENTITY)",Pattern.CASE_INSENSITIVE).matcher(text).find())
            throw new IllegalArgumentException("Unsupported XML declaration/size");
        try {
            SAXParserFactory factory=SAXParserFactory.newInstance(); factory.setNamespaceAware(true);
            XMLReader reader=factory.newSAXParser().getXMLReader();
            reader.setEntityResolver((publicId,systemId)-> { throw new SAXException("External entity rejected"); });
            reader.setContentHandler(handler); reader.setErrorHandler(handler);
            reader.parse(new InputSource(new StringReader(text)));
        } catch (Exception invalid) { throw new IllegalArgumentException("Invalid lyric XML",invalid); }
    }
    private static String tag(String local,String qualified) { return local.isEmpty() ? qualified.replaceFirst("^.*:","") : local; }
    private static final class Row {
        final long start,end;
        final StringBuilder text=new StringBuilder();
        final ArrayList<Lyrics.Word> words=new ArrayList<>();
        Row(long start,long end) {
            if (end<start) throw new IllegalArgumentException("Inverted lyric range");
            this.start=start; this.end=end;
        }
    }
    private static final class Span {
        final int depth,from; final long start,end; boolean child;
        Span(int depth,int from,long start,long end) { this.depth=depth; this.from=from; this.start=start; this.end=end; }
    }
    static Lyrics ttml(String value,long duration) {
        return ttml(value,duration,false);
    }
    static Lyrics ttml(String value,long duration,boolean exactRecording) {
        final TreeMap<Long,Row> rows=new TreeMap<>();
        xml(value,new DefaultHandler() {
            int depth,bodyDepth,skipDepth,total,wordCount; Row row;
            final ArrayDeque<Span> spans=new ArrayDeque<>();
            @Override public void startElement(String uri,String local,String q,Attributes a) {
                String name=tag(local,q); if (++depth>16) throw new IllegalArgumentException("XML depth");
                if (depth==1 && !name.equals("tt")) throw new IllegalArgumentException("Not TTML");
                if (skipDepth>0) return;
                if (name.equals("body")) {
                    bodyDepth=depth;
                    if (a.getValue("dur")!=null) {
                        long declared=time(a.getValue("dur"));
                        long mvTolerance=exactRecording && duration>=120_000L
                            ? Math.min(10_000L,Math.round(duration*0.06)) : 2000L;
                        // Exact catalogue recordings may include a short silent TTML outro or MV intro/outro drift; cue bounds stay strict.
                        if(Math.abs(declared-duration)>mvTolerance && !(exactRecording && declared>=duration && declared-duration<=5000))
                            throw new IllegalArgumentException("Different recording duration");
                    }
                }
                if (bodyDepth==0) return;
                for (int i=0;i<a.getLength();i++) if (tag(a.getLocalName(i),a.getQName(i)).equals("role")
                    && Arrays.asList("x-bg","x-translation","x-transliteration").contains(a.getValue(i))) { skipDepth=depth; return; }
                if (name.equals("p")) {
                    if (row!=null) throw new IllegalArgumentException("Nested lyric line");
                    row=new Row(time(a.getValue("begin")),time(a.getValue("end"))); spans.clear();
                } else if (row!=null && name.equals("span")) {
                    long start=a.getValue("begin")==null ? -1 : time(a.getValue("begin"));
                    long end=a.getValue("end")==null ? row.end : time(a.getValue("end"));
                    if (start>=0) for (Span parent:spans) parent.child=true;
                    spans.push(new Span(depth,row.text.length(),start,end));
                } else if (row!=null && name.equals("br")) row.text.append('\n');
            }
            @Override public void characters(char[] chars,int start,int length) {
                if (row==null || skipDepth>0) return;
                String text=new String(chars,start,length);
                if (text.trim().isEmpty() && (text.indexOf('\n')>=0 || text.indexOf('\r')>=0)) return;
                row.text.append(text);
                if (row.text.length()>512) throw new IllegalArgumentException("Lyric line too long");
            }
            @Override public void endElement(String uri,String local,String q) {
                String name=tag(local,q);
                if (skipDepth>0) { if (depth==skipDepth) skipDepth=0; depth--; return; }
                if (row!=null && name.equals("span") && !spans.isEmpty() && spans.peek().depth==depth) {
                    Span span=spans.pop();
                    if (span.start>=0 && !span.child && row.text.length()>span.from) {
                        if (++wordCount>8192) throw new IllegalArgumentException("Too many segments");
                        if (span.start<row.start || span.end<span.start || span.end>row.end
                            || !row.words.isEmpty() && span.start<row.words.get(row.words.size()-1).end)
                            throw new IllegalArgumentException("Invalid segment order/range");
                        row.words.add(new Lyrics.Word(span.from,row.text.length(),span.start,span.end));
                    }
                } else if (row!=null && name.equals("p")) {
                    total+=row.text.length();
                    if (total>65536 || rows.size()>=2048 || row.end>duration+2000) throw new IllegalArgumentException("Lyric bounds");
                    if (!row.text.toString().trim().isEmpty() && !rows.containsKey(row.start)) rows.put(row.start,row);
                    row=null;
                }
                if (depth==bodyDepth) bodyDepth=0;
                depth--;
            }
        });
        Lyrics parsed=finish(rows);
        if (LyricMatch.isTruncatedForDuration(parsed,duration))
            throw new IllegalArgumentException("Truncated short-version lyrics");
        return parsed;
    }
    static Lyrics qrc(String xml,String title,String artist,long duration) {
        final String[] content={null};
        xml(xml,new DefaultHandler() {
            int depth;
            @Override public void startElement(String uri,String local,String q,Attributes a) {
                if (++depth>12) throw new IllegalArgumentException("QRC depth");
                if (tag(local,q).equals("Lyric_1")) content[0]=a.getValue("LyricContent");
            }
            @Override public void endElement(String u,String l,String q) { depth--; }
        });
        if (content[0]==null || content[0].length()>65536) throw new IllegalArgumentException("Missing QRC body");
        validateHeaders(content[0],title,artist);
        TreeMap<Long,Row> rows=new TreeMap<>(); Matcher headers=QLINE.matcher(content[0]);
        ArrayList<int[]> bounds=new ArrayList<>(); ArrayList<long[]> times=new ArrayList<>();
        while (headers.find()) {
            if (bounds.size()>=2048) throw new IllegalArgumentException("Too many QRC lines");
            bounds.add(new int[]{headers.start(),headers.end()});times.add(new long[]{integer(headers.group(1)),integer(headers.group(2))});
        }
        int wordCount=0;
        for (int i=0;i<bounds.size();i++) {
            long[] timing=times.get(i);
            long lineEnd=timing[1]>0 ? timing[0]+timing[1] : i+1<times.size() ? times.get(i+1)[0] : duration;
            Row row=new Row(timing[0],lineEnd);
            if (row.end>duration+2000) throw new IllegalArgumentException("Different recording duration");
            String body=content[0].substring(bounds.get(i)[1],i+1<bounds.size()?bounds.get(i+1)[0]:content[0].length());
            Matcher words=QWORD.matcher(body); int end=0;
            while (words.find()) {
                String text=body.substring(end,words.start()); int from=row.text.length(); row.text.append(text);
                long start=integer(words.group(1)),finish=start+integer(words.group(2));
                if (++wordCount>8192 || start<row.start || finish>row.end || !row.words.isEmpty() && start<row.words.get(row.words.size()-1).end)
                    throw new IllegalArgumentException("Invalid QRC segment");
                if (!text.isEmpty()) row.words.add(new Lyrics.Word(from,row.text.length(),start,finish));
                end=words.end();
            }
            if (!body.substring(end).trim().isEmpty()) throw new IllegalArgumentException("Untimed QRC tail");
            String text=row.text.toString();
            if (text.length()>512) throw new IllegalArgumentException("QRC line too long");
            if (!text.trim().isEmpty() && !CREDIT.matcher(text.trim()).matches() && !Lyrics.sameTrack(text.trim(),title)
                && !Lyrics.sameTrack(text.trim(),artist) && !rows.containsKey(row.start)) rows.put(row.start,row);
        }
        Lyrics parsed=finish(rows);
        if (LyricMatch.isTruncatedForDuration(parsed,duration))
            throw new IllegalArgumentException("Truncated short-version lyrics");
        return parsed;
    }
    private static void validateHeaders(String content,String title,String artist) {
        Matcher metadata=HEADER.matcher(content);
        while (metadata.find()) {
            String key=metadata.group(1),text=metadata.group(2).trim();
            if (text.isEmpty()) continue;
            if (key.equalsIgnoreCase("ti") && !Lyrics.sameTrack(text,title) || key.equalsIgnoreCase("ar") && !Lyrics.sameTrack(text,artist)
                || key.equalsIgnoreCase("offset") && !text.equals("0")) throw new IllegalArgumentException("QRC identity/offset");
        }
    }
    static Lyrics kugou(String value,String title,String artist,long duration) {
        validateHeaders(value,title,artist); Lyrics parsed=Lyrics.parse(value);
        if (parsed.size()>0 && (parsed.startAt(parsed.size()-1)>duration+2000 || LyricMatch.isTruncatedForDuration(parsed,duration)))
            throw new IllegalArgumentException("Different recording duration");
        return parsed;
    }
    private static Lyrics finish(TreeMap<Long,Row> rows) {
        if (rows.isEmpty()) throw new IllegalArgumentException("No timed lyrics");
        long[] times=new long[rows.size()]; String[] text=new String[rows.size()]; Lyrics.Word[][] words=new Lyrics.Word[rows.size()][]; int i=0,total=0;
        for (Row row:rows.values()) {
            times[i]=row.start; text[i]=row.text.toString(); total+=text[i].length();
            words[i]=row.words.toArray(new Lyrics.Word[0]); i++;
        }
        if (total>65536) throw new IllegalArgumentException("Lyrics size");
        return Lyrics.timed(times,text,words);
    }
}
