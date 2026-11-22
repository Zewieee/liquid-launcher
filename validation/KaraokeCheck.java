package local.launcherfix;

public final class KaraokeCheck {
    static int checks;
    static void check(boolean value,String name) { checks++; if (!value) throw new AssertionError(name); }
    static String file(String body) { return "version: '1.0'\nmetadata: {title: Song, artist: Artist}\nlines:\n"+body; }
    static Lyrics parse(String value) { return TimedLyrics.parse(value,"Song","Artist"); }
    static void rejected(String value,String name) {
        try { parse(value); throw new AssertionError(name); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        String body="- text: 'Hello world'\n  start_ms: 1000\n  end_ms: 4000\n  words:\n  - {text: 'Hello ', start_ms: 1000, end_ms: 2000}\n  - {text: world, start_ms: 2500, end_ms: 4000}\n";
        Lyrics l=parse(file(body));
        check(l.size()==1 && l.at(0).equals("Hello world"),"text intact");
        check(l.coverageAt(0,500,false)==0,"before start");
        check(l.coverageAt(0,1500,false)==3,"half first word");
        check(l.coverageAt(0,2300,false)==6,"hold in gap");
        check(l.coverageAt(0,3250,false)==8.5,"partial second word");
        check(l.coverageAt(0,5000,false)==11,"completed");
        check(l.coverageAt(0,1500,false)==3,"backward seek");
        check(l.refreshDelay(0,1500)==50,"active sweep updates");
        check(l.refreshDelay(0,2300)==200,"next word deadline");
        check(l.refreshDelay(0,5000)==1000,"idle after last word");
        check(Double.isNaN(l.coverageAt(-1,1000,false)),"before first line");
        check(Double.isNaN(l.coverageAt(0,1000,true)),"unmapped romaji");
        Lyrics inferred=parse(file(body.replace(", end_ms: 2000","")));
        check(inferred.coverageAt(0,1750,false)==3,"missing end uses next start");
        Lyrics noWords=parse(file("- {text: Hello, start_ms: 1000, end_ms: 2000}\n"));
        check(Double.isNaN(noWords.coverageAt(0,1500,false)),"no invented word timing");
        check(Double.isNaN(parse(file(body.replace("text: world","text: other"))).coverageAt(0,1500,false)),"mismatched word text fallback");
        check(Double.isNaN(parse(file(body.replace("start_ms: 2500","start_ms: 1500"))).coverageAt(0,1500,false)),"overlap fallback");
        rejected(file(body).replace("'1.0'","'2.0'"),"unknown version");
        rejected(file(body).replace("title: Song","title: Other"),"identity mismatch");
        rejected(file(body).replace("start_ms: 1000","start_ms: -1"),"negative time");
        rejected(file(body).replace("start_ms: 1000","start_ms: 1.5"),"fraction time");
        rejected(file(body).replace("version: '1.0'","version: '1.0'\nversion: '1.0'"),"duplicate keys");
        rejected("!!java.lang.ProcessBuilder {}","custom tag");
        rejected(file(body).replace("metadata: {title: Song, artist: Artist}","metadata: &x {title: Song, artist: Artist}\nother: [*x]"),"aliases");
        Lyrics unicode=parse(file("- text: '😀a'\n  start_ms: 0\n  end_ms: 2000\n  words: [{text: '😀a', start_ms: 0, end_ms: 2000}]\n"));
        check(unicode.coverageAt(0,1000,false)==1,"Unicode codepoint coverage");
        String japanese=file("- text: '今日は晴れです'\n  start_ms: 0\n  end_ms: 4000\n  words: [{text: '今日は', start_ms: 0, end_ms: 2000}, {text: '晴れです', start_ms: 2000, end_ms: 4000}]\n");
        Lyrics romaji=JapaneseRomaji.convert(parse(japanese));
        check(romaji.romajiAt(0).equals("kyou wa hare desu"),"romaji text preserved");
        check(!Double.isNaN(romaji.coverageAt(0,1000,true)),"mapped romaji timing");
        check(romaji.coverageAt(0,3000,true)>romaji.coverageAt(0,1000,true),"romaji advances");
        System.out.println("KARAOKE_CHECK_OK cases="+checks);
    }
}
