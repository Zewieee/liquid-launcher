package local.launcherfix;

public final class ProviderFormatCheck {
    static int count;
    static void check(boolean value,String name) { count++; if (!value) throw new AssertionError(name); }
    static String ttml(String body) { return "<tt xmlns='http://www.w3.org/ns/ttml'><body dur='10.0'><div>"+body+"</div></body></tt>"; }
    static void rejected(String text,String name) {
        try { ProviderLyrics.ttml(text,10000); throw new AssertionError(name); }
        catch (IllegalArgumentException expected) { count++; }
    }
    public static void main(String[] args) {
        Lyrics l=ProviderLyrics.ttml(ttml("<p begin='1.0' end='4.0'><span begin='1.0' end='2.0'>Hello</span> <span begin='2.0' end='4.0'>world</span></p>"),10000);
        check(l.at(0).equals("Hello world"),"inter-span whitespace");
        check(l.hasWords() && l.coverageAt(0,1500,false)==2.5,"word coverage");
        check(l.indexAt(999)==-1,"line timestamp");
        Lyrics line=ProviderLyrics.ttml(ttml("<p begin='1.0' end='4.0'>Line only</p>"),10000);
        check(!line.hasWords() && line.at(0).equals("Line only"),"line-only no guessed words");
        Lyrics nested=ProviderLyrics.ttml(ttml("<p begin='1.0' end='4.0'><span><span begin='1.0' end='2.0'>A&amp;B</span></span><span xmlns:m='http://www.w3.org/ns/ttml#metadata' m:role='x-bg'><span begin='2.0' end='3.0'>Background</span></span><span begin='3.0' end='4.0'>C</span></p>"),10000);
        check(nested.at(0).equals("A&BC") && nested.wordsAt(0).length==2,"background and nesting");
        check(ProviderLyrics.time("01:02:03.456")==3723456,"hours");
        check(ProviderLyrics.time("1:02.5")==62500,"minutes");
        check(ProviderLyrics.time("250ms")==250 && ProviderLyrics.time("2.5s")==2500,"offset units");
        rejected(ttml("<p begin='-1' end='3'>Bad</p>"),"negative start");
        rejected(ttml("<p begin='4' end='3'>Bad</p>"),"inverted end");
        rejected(ttml("<p begin='1' end='4'><span begin='1' end='3'>A</span><span begin='2' end='4'>B</span></p>"),"overlapping segments");
        rejected("<!DOCTYPE tt [<!ENTITY x SYSTEM 'file:///private'>]><tt>&x;</tt>","DTD rejected");
        rejected("<tt><body>","malformed XML");
        rejected(ttml("<p begin='1' end='4'>A</p>").replace("dur='10.0'","dur='20.0'"),"wrong recording duration");
        String outro=ttml("<p begin='1' end='4'>A</p>").replace("dur='10.0'","dur='12.627'");
        rejected(outro,"unverified recording retains strict duration");
        check(ProviderLyrics.ttml(outro,10000,true).size()==1,"exact catalogue recording allows bounded silent outro");
        try{ProviderLyrics.ttml(outro.replace("end='4'","end='13'"),10000,true);throw new AssertionError("outro cannot extend cues");}catch(IllegalArgumentException expected){count++;}
        try{ProviderLyrics.ttml(outro.replace("12.627","16"),10000,true);throw new AssertionError("outro cap");}catch(IllegalArgumentException expected){count++;}
        String q="<QrcInfos><LyricInfo><Lyric_1 LyricContent='[ti:Song]&#10;[ar:Artist]&#10;[1000,3000]Hello (1000,1000)world(2500,1500)'/></LyricInfo></QrcInfos>";
        Lyrics qq=ProviderLyrics.qrc(q,"Song","Artist",10000);
        check(qq.at(0).equals("Hello world") && qq.coverageAt(0,1500,false)==3,"QRC trailing timing");
        check(qq.coverageAt(0,2200,false)==6,"QRC gap held");
        try { ProviderLyrics.qrc(q,"Other","Artist",10000); throw new AssertionError("QRC identity"); }
        catch (IllegalArgumentException expected) { count++; }
        Lyrics credit=ProviderLyrics.qrc(q.replace("[1000,3000]","[0,500]作词：(0,500)[1000,3000]"),"Song","Artist",10000);
        check(credit.size()==1,"QRC credits skipped");
        Lyrics unicode=ProviderLyrics.ttml(ttml("<p begin='1' end='4'><span begin='1' end='4'>😀あ</span></p>"),10000);
        check(unicode.coverageAt(0,2500,false)==1,"Unicode codepoints");
        check(qq.fromSource("QQ").source().equals("QQ"),"source caption");
        check(ProviderLyrics.qrc(q.replace("[1000,3000]","[1000,0]"),"Song","Artist",10000).hasWords(),"QRC zero line duration");
        check(ProviderLyrics.kugou("[ti:Song]\n[ar:Artist]\n[00:01]A line","Song","Artist",10000).size()==1,"Kugou timed line");
        try { ProviderLyrics.kugou("[ti:Other]\n[00:01]A line","Song","Artist",10000); throw new AssertionError("Kugou title mismatch"); }
        catch(IllegalArgumentException expected) { count++; }
        try { ProviderLyrics.kugou("[00:30]A line","Song","Artist",10000); throw new AssertionError("Kugou recording mismatch"); }
        catch(IllegalArgumentException expected) { count++; }
        System.out.println("PROVIDER_FORMAT_CHECK_OK cases="+count);
    }
}
