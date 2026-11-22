package local.launcherfix;

import android.os.Looper;
import org.json.*;
import java.util.*;

public final class MatchPlainCheck {
    static int count;
    static void check(boolean ok,String name) { count++; if(!ok)throw new AssertionError(name); }
    static JSONObject record(String title,String artist,double duration,String line) throws Exception {
        return new JSONObject().put("trackName",title).put("artistName",artist).put("duration",duration).put("syncedLyrics",line);
    }
    static LiveLyrics.Result search(String title,String artist,JSONObject... rows) throws Exception {
        JSONArray list=new JSONArray();for(JSONObject row:rows)list.put(row);
        return LyricSources.find(title,artist,"Different album",10000,url->url.contains("/api/search?") ? new JSONObject().put("results",list) : null);
    }
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Lyrics plain=Lyrics.plain("First\n\nSecond").fromSource("Static source").withCredit("https://example.com","Contributor","https://example.com/author");
        check(plain.size()==3 && plain.at(1).isEmpty() && !plain.isSynced(),"plain stanzas");
        check(plain.indexAt(0)==-1 && plain.indexAt(999999)==-1 && plain.startAt(0)==-1,"no artificial plain timestamps");
        check(!plain.hasWords() && Double.isNaN(plain.coverageAt(0,5000,false)) && plain.refreshDelay(-1,5000)==1000,"plain no karaoke or fast refresh");
        Lyrics converted=JapaneseRomaji.convert(Lyrics.plain("君の声\nHello").withCredit("https://example.com","Name","https://example.com/a"));
        check(!converted.isSynced() && !converted.romajiAt(0).isEmpty() && converted.contributor().equals("Name"),"plain romaji and credit preserved");
        check(Lyrics.plain(" \n ").size()==0,"blank plain is missing");
        try {Lyrics.plain(String.join("",Collections.nCopies(65537,"a")));throw new AssertionError("unbounded text");}catch(IllegalArgumentException expected){count++;}
        JSONObject only=record("Song","Artist",10,"").put("plainLyrics","First\nSecond");
        LiveLyrics.Result direct=LiveLyrics.parse(only,"Song","Artist",10000);
        check(direct.lyrics!=null && !direct.lyrics.isSynced(),"LRCLIB plain fallback");
        check(LiveLyrics.parse(only.put("syncedLyrics","[00:01]Timed"),"Song","Artist",10000).lyrics.isSynced(),"line outranks plain");
        check(search("Song!","Artist",record("Song","Artist",10,"[00:01]Matched")).lyrics!=null,"punctuation and album independent search");
        check(search("Song","Artist",record("Song","Other Artist",10,"[00:01]Wrong")).lyrics==null,"different artist rejected");
        check(search("Song","Artist",record("Song - Live","Artist",10,"[00:01]Wrong")).lyrics==null,"live version rejected");
        check(search("Song","Artist",record("Song - TV Size","Artist",10,"[00:01]Wrong")).lyrics==null,"TV size rejected");
        check(search("Song","Artist",record("Song (Remix)","Artist",10,"[00:01]Wrong")).lyrics==null,"remix rejected");
        check(search("Song - 2020 Remaster","Artist",record("Song","Artist",10,"[00:01]Matched")).lyrics!=null,"remaster label fallback");
        check(search("Song","Artist",record("Song","Artist",13,"[00:01]Wrong")).lyrics==null,"duration guard remains");
        String huge=String.join("",Collections.nCopies(513,"あ"));
        check(!new LyricMatch().title("Song",huge) && !new LyricMatch().artist("Artist",huge),"oversized candidate rejected before reading conversion");
        check(search("世界の秘密","さユり",record("sekai no himitsu","Sayuri",10,"[00:01]Matched")).lyrics!=null,"Japanese title and artist romaji match");
        check(search("Song","Artist",new JSONObject(),record("Song","Artist",10,"[00:01]Valid")).lyrics!=null,"malformed candidate does not hide next one");
        JSONObject near=record("Song","Artist",10.1,"[00:01]Near"),far=record("Song","Artist",11,"[00:01]Far");
        check(search("Song","Artist",far,near).lyrics.at(0).equals("Near"),"closest recording duration wins equal quality");
        LiveLyrics.Result tiers=LyricSources.find("Song","Artist","",10000,url->url.contains("lrclib.net/api/get?")
            ? record("Song","Artist",10,"").put("plainLyrics","Plain") : url.contains("/kugou/") ? new JSONObject().put("provider","kugou").put("lyrics","[00:01]Timed") : null);
        check(tiers.lyrics.isSynced() && tiers.lyrics.source().contains("KuGou"),"later timed source beats plain");
        JSONObject unison=ExtraSourcesCheck.unison();unison.getJSONObject("data").put("format","plain").put("syncType","plain").put("lyrics","Unison static");
        LiveLyrics.Result u=LyricSources.find("Song","Artist","Album",10000,url->url.contains("unison.") ? unison : null);
        check(u.lyrics!=null && !u.lyrics.isSynced() && u.lyrics.contributor().equals("Contributor"),"Unison plain retains attribution");
        JSONObject id=ExtraSourcesCheck.unison();id.getJSONObject("data").put("song","Localized title").put("artist","別名").put("album","Different")
            .put("matchedBy","isrc").put("isrc","USABC2600001");
        ArrayList<String> urls=new ArrayList<>();
        LiveLyrics.Result identified=LyricSources.find("Song","Artist","Album","0123456789ABCDEFGHIJKL",10000,url->{urls.add(url);return url.contains("unison.") ? id : null;});
        check(identified.lyrics!=null && urls.get(1).contains("&spotify=0123456789ABCDEFGHIJKL"),"Spotify ISRC match accepts localized names");
        id.getJSONObject("data").put("matchedBy","linked_video");
        check(LyricSources.find("Song","Artist","Album","0123456789ABCDEFGHIJKL",10000,url->url.contains("unison.") ? id : null).lyrics==null,"linked video does not bypass metadata");
        LiveLyrics live=new LiveLyrics((data,status)->{});live.setTrack("Song","Artist","Album","0123456789ABCDEFGHIJKL",10000);
        java.lang.reflect.Field key=LiveLyrics.class.getDeclaredField("key");key.setAccessible(true);String first=(String)key.get(live);
        live.setTrack("Song","Artist","Album","ZYXWVUTSRQPONMLKJIHGFE",10000);
        check(!first.equals(key.get(live)),"recording id isolates lyric cache");live.close();
        System.out.println("MATCH_PLAIN_CHECK_OK cases="+count);
    }
}
