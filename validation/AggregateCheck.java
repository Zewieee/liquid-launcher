package local.launcherfix;
import org.json.*;import java.util.*;
public final class AggregateCheck {
    static int count;
    static void check(boolean ok,String name){count++;if(!ok)throw new AssertionError(name);}
    static String event(String provider,JSONObject result)throws Exception{return "data: "+new JSONObject().put("provider",provider).put("results",result)+"\n\n";}
    static String line(String text)throws Exception{return event("lrclib",new JSONObject().put("synced",text));}
    static LiveLyrics.Result parse(String value)throws Exception{return AggregateLyrics.parse(value,"Song","Artist",10000);}
    static void invalidLrc(String text,String name){try{EnhancedLrc.parse(text,10000);throw new AssertionError(name);}catch(IllegalArgumentException expected){count++;}}
    public static void main(String[] args)throws Exception{
        String words="[00:01]<00:01>Hello <00:02>world<00:04>";
        Lyrics l=EnhancedLrc.parse(words,10000);
        check(l.hasWords() && l.at(0).equals("Hello world"),"enhanced word text");
        check(l.coverageAt(0,1500,false)==3,"real word interval");
        check(EnhancedLrc.parse("[00:01]<00:01>Last",10000).wordsAt(0)[0].end==1000,"last word no estimated duration");
        check(!EnhancedLrc.parse("[00:01]A line",10000).hasWords(),"ordinary LRC no synthetic words");
        check(!EnhancedLrc.parse("[00:01]Unknown <00:02>word",10000).hasWords(),"untimed prefix stays line-only");
        check(!EnhancedLrc.parse("[00:01][00:05]<00:01>Repeat<00:02>",10000).hasWords(),"no rebased repetition timing");
        check(EnhancedLrc.parse("[00:01]First\n[00:04]",10000).at(1).isEmpty(),"blank interlude retained");
        invalidLrc("[00:01]<00:03>One<00:02>Two","inverted word time");
        invalidLrc("[00:02]<00:01>Too early","word before line");
        invalidLrc("[00:20]Outside recording","line outside recording");
        invalidLrc("[offset:20]\n[00:01]Shifted","offset cannot silently shift timing");
        invalidLrc(String.join("",Collections.nCopies(65537,"a")),"oversized LRC");
        String mm=event("musixmatch",new JSONObject().put("wordByWord",words).put("synced","[00:01]Line fallback"));
        LiveLyrics.Result rich=parse(mm);check(rich.lyrics.hasWords() && rich.lyrics.source().equals("Musixmatch"),"Musixmatch real word timing");
        check(parse(mm.replace("\n","\r\n")).lyrics.hasWords(),"CRLF SSE");
        check(parse("data: {broken\n\n"+mm+"data: [DONE]\n\n").lyrics.hasWords(),"malformed event does not hide valid source");
        check(parse(event("musixmatch",new JSONObject().put("wordByWord","[00:20]Bad").put("synced","[00:01]Good"))).lyrics.isSynced(),"source format fallback");
        check(!parse(event("lrclib",new JSONObject().put("synced"," ").put("plain","Static lines"))).lyrics.isSynced(),"empty timed does not mask plain");
        check(parse(event("lrclib",new JSONObject().put("plain",false))).lyrics==null,"non-text provider value is not displayed as lyrics");
        check(parse(event("lrclib",new JSONObject().put("plain","Static"))+mm).lyrics.hasWords(),"word beats plain");
        String xml="<tt><body dur='10'><div><p begin='1' end='4'><span begin='1' end='4'>Synthetic</span></p></div></body></tt>";
        check(parse(mm+event("golyrics",new JSONObject().put("lyrics",new JSONObject().put("ttml",xml).toString()))).lyrics.source().equals("Better Lyrics TTML"),"preferred TTML on equal timing");
        check(parse(event("binimum",new JSONObject().put("lyrics",xml))).lyrics.source().equals("BiniLyrics"),"Bini TTML");
        check(parse(event("kugou",new JSONObject().put("lyrics",new JSONObject().put("lyrics","[00:01]Kugou").toString()))).lyrics.source().contains("KuGou"),"nested Kugou JSON");
        String qrc="<QrcInfos><Lyric_1 LyricContent='[1000,3000]Synthetic(1000,3000)'/></QrcInfos>";
        check(parse(event("qq",new JSONObject().put("lyrics",new JSONObject().put("lyrics",qrc).toString()))).lyrics.hasWords(),"nested QQ QRC");
        check(parse("event: metadata\ndata: {\"duration\":50}\n\n"+mm).lyrics==null,"different recording rejected");
        check(parse("event: metadata\ndata: {\"duration\":\"invalid\"}\n\n"+mm).lyrics==null,"malformed metadata does not bypass guard");
        check(parse("event: metadata\ndata: {\"song\":\"Song - Live\",\"artist\":\"Artist\"}\n\n"+mm).lyrics==null,"live version rejected");
        check(parse(event("unknown",new JSONObject().put("lyrics",xml))+mm).lyrics.hasWords(),"unknown provider ignored");
        try{parse(String.join("",Collections.nCopies(1048577,"a")));throw new AssertionError("stream size");}catch(IllegalArgumentException expected){count++;}
        try{parse(String.join("",Collections.nCopies(65,"event: keepalive\n\n")));throw new AssertionError("event count");}catch(IllegalArgumentException expected){count++;}
        Thread.currentThread().interrupt();try{parse(mm);throw new AssertionError("cancel");}catch(java.io.InterruptedIOException expected){count++;}finally{Thread.interrupted();}
        String id="4fTpi0cXVxpXRAv1i38Lug";
        JSONObject mapping=new JSONObject().put("spotify",id).put("isrc","JPX401800138").put("youtube","UEBliYVwP4U");
        check(AggregateLyrics.reference(mapping,id)[0].equals("UEBliYVwP4U"),"exact Spotify recording maps to video");
        check(AggregateLyrics.reference(mapping,"0000000000000000000000")==null,"different Spotify recording rejected");
        check(AggregateLyrics.reference(mapping,"")==null,"missing Spotify ID rejected");
        mapping.put("youtube","https://evil/video");check(AggregateLyrics.reference(mapping,id)==null,"mapping is ID only");
        mapping.put("youtube","UEBliYVwP4U").put("isrc","bad");check(AggregateLyrics.reference(mapping,id)==null,"ISRC required");
        check(AggregateLyrics.reference(null,id)==null,"missing mapping retains fallback");
        try{AggregateLyrics.form("Song","Artist","",10000,"","JPX401800138","synthetic.access.token");throw new AssertionError("empty video");}catch(IllegalArgumentException expected){count++;}
        String form=AggregateLyrics.form("Song & One","Artist","Album",10000,"UEBliYVwP4U","JPX401800138","synthetic.access.token");
        check(form.contains("song=Song+%26+One") && form.contains("alwaysFetchMetadata=false") && form.contains("token=synthetic.access.token"),"encoded API request");
        System.out.println("AGGREGATE_CHECK_OK cases="+count);
    }
}
