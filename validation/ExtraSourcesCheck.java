package local.launcherfix;

import org.json.*;
import java.util.*;

public final class ExtraSourcesCheck {
    static int count;
    static void check(boolean ok,String name) { count++; if (!ok) throw new AssertionError(name); }
    static JSONObject ttml() throws Exception { return SourceChainCheck.ttml(true); }
    static JSONObject unison() throws Exception {
        return new JSONObject().put("success",true).put("data",new JSONObject()
            .put("song","Song").put("artist","Artist, Guest").put("album","Album").put("duration",10)
            .put("videoId","abcdefghijk").put("format","ttml").put("syncType","richsync")
            .put("lyrics",ttml().getString("ttml")).put("score",0).put("confidence","low")
            .put("submitter",new JSONObject().put("displayName","Contributor").put("keyId",String.join("",Collections.nCopies(64,"a")))));
    }
    static JSONObject bini() throws Exception {
        return new JSONObject().put("results",new JSONArray().put(new JSONObject().put("track_name","Song")
            .put("artist_name","Artist").put("album_name","Album").put("duration",10).put("timing_type","word")
            .put("isrc","USABC2600001").put("lyricsUrl","https://lrc.red/s/USABC2600001.ttml")));
    }
    static LiveLyrics.Result fromUnison(JSONObject info) throws Exception {
        return LyricSources.find("Song","Artist","Album",10000,url->url.contains("unison.") ? info : null);
    }
    static LiveLyrics.Result fromBini(JSONObject info,ArrayList<String> called) throws Exception {
        return LyricSources.find("Song","Artist","Album",10000,url->{called.add(url);
            if(url.contains("lrc.red/api/v1"))return info;
            if(url.contains("lrc.red") || url.contains("lyrics-storage"))return ttml();return null;});
    }
    public static void main(String[] args) throws Exception {
        LiveLyrics.Result result=fromUnison(unison());
        check(result.lyrics!=null && result.lyrics.source().equals("Unison") && result.lyrics.hasWords(),"Unison timed TTML");
        check(result.lyrics.contributor().equals("Contributor") && result.lyrics.sourceUrl().endsWith("/abcdefghijk"),"Unison attribution links");
        Lyrics converted=JapaneseRomaji.convert(result.lyrics);
        check(converted.contributor().equals("Contributor") && converted.contributorUrl().equals(result.lyrics.contributorUrl()),"romaji preserves attribution");
        check(fromUnison(unison().put("success",false)).lyrics==null,"false envelope");
        JSONObject wrong=unison();wrong.getJSONObject("data").put("song","Other");check(fromUnison(wrong).lyrics==null,"wrong title");
        wrong=unison();wrong.getJSONObject("data").put("artist","Other Artist");check(fromUnison(wrong).lyrics==null,"no artist substring match");
        wrong=unison();wrong.getJSONObject("data").put("album","Other Album");check(fromUnison(wrong).lyrics!=null,"alternate album found by fallback");
        wrong=unison();wrong.getJSONObject("data").put("duration",30);check(fromUnison(wrong).lyrics==null,"wrong duration");
        wrong=unison();wrong.getJSONObject("data").put("matchScore",.2);check(fromUnison(wrong).lyrics==null,"low match score");
        wrong=unison();wrong.getJSONObject("data").put("hidden",true);check(fromUnison(wrong).lyrics==null,"hidden record");
        wrong=unison();wrong.getJSONObject("data").put("videoId","../unsafe");check(fromUnison(wrong).lyrics==null,"invalid video link");
        wrong=unison();wrong.getJSONObject("data").getJSONObject("submitter").put("keyId","../unsafe");check(fromUnison(wrong).lyrics==null,"invalid contributor link");
        wrong=unison();wrong.getJSONObject("data").getJSONObject("submitter").put("displayName","Bad\nname");check(fromUnison(wrong).lyrics==null,"control chars in credit");
        wrong=unison();wrong.getJSONObject("data").put("format","plain").put("syncType","plain").put("lyrics","No timing");check(fromUnison(wrong).lyrics!=null && !fromUnison(wrong).lyrics.isSynced(),"plain remains unsynced");
        JSONObject line=unison();line.getJSONObject("data").put("format","lrc").put("syncType","linesync").put("lyrics","[00:01]Synthetic line");
        LiveLyrics.Result lineResult=fromUnison(line);check(lineResult.lyrics!=null && !lineResult.lyrics.hasWords(),"Unison LRC has no word sweep");
        LiveLyrics.Result upgrade=LyricSources.find("Song","Artist","Album",10000,url->url.contains("unison.") ? line : url.contains("lrc.red/api/v1") ? bini() : url.contains("lrc.red") ? ttml() : null);
        check(upgrade.lyrics.source().equals("BiniLyrics") && upgrade.lyrics.hasWords(),"Bini word timing beats Unison line");
        ArrayList<String> called=new ArrayList<>();
        result=fromBini(bini(),called);check(result.lyrics!=null && result.lyrics.hasWords() && result.lyrics.source().equals("BiniLyrics"),"Bini two-stage TTML");
        check(called.size()==4 && called.get(2).contains("album=Album") && called.get(2).endsWith("duration=10"),"Bini encoded metadata and bounded chain");
        String[] unsafe={"http://lrc.red/s/USABC2600001.ttml","https://lrc.red.evil/s/USABC2600001.ttml","https://127.0.0.1/a.ttml","https://lrc.red/s/USABC2600002.ttml","https://user@lrc.red/s/USABC2600001.ttml","https://lrc.red/s/USABC2600001.ttml?refresh=1"};
        for(String url:unsafe) {
            JSONObject bad=bini();bad.getJSONArray("results").getJSONObject(0).put("lyricsUrl",url);called.clear();
            check(fromBini(bad,called).lyrics==null && called.size()==9,"no untrusted lyric fetch: "+url);
        }
        JSONObject bad=bini();bad.getJSONArray("results").getJSONObject(0).put("duration",40);called.clear();
        check(fromBini(bad,called).lyrics==null && called.size()==9,"Bini duration mismatch no fetch");
        LiveLyrics.Result denied=LyricSources.find("Song","Artist","Album",10000,url->url.contains("lrc.red/api/v1") ? bini() : url.contains("lrclib.net") ? SourceChainCheck.lrc() : null);
        check(denied.lyrics!=null && denied.lyrics.source().equals("LRCLIB"),"denied Bini download preserves LRCLIB");
        System.out.println("EXTRA_SOURCES_CHECK_OK cases="+count);
    }
}
