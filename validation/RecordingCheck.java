package local.launcherfix;

import android.os.Looper;
import org.json.*;
import java.lang.reflect.Field;

public final class RecordingCheck {
    static int count;
    static final String VIDEO="UEBliYVwP4U",OTHER="n89SKAymNfA",SPOTIFY="4fTpi0cXVxpXRAv1i38Lug";
    static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);count++;}
    static JSONObject unison()throws Exception {
        return new JSONObject().put("success",true).put("data",new JSONObject().put("song","Song").put("artist","Artist")
            .put("duration",10).put("videoId",VIDEO).put("matchScore",.99));
    }
    static String[] reference(JSONObject info){return AggregateLyrics.unisonReference(info,"Song","Artist",10000);}
    static String key(LiveLyrics live)throws Exception {Field key=LiveLyrics.class.getDeclaredField("key");key.setAccessible(true);return (String)key.get(live);}
    public static void main(String[] args)throws Exception {
        Looper.prepareMainLooper();
        for(String value:new String[]{VIDEO,"https://www.youtube.com/watch?v="+VIDEO+"&list=PLtest","https://music.youtube.com/watch?v="+VIDEO,
            "https://youtu.be/"+VIDEO+"?si=abc","https://youtube.com/embed/"+VIDEO,"https://m.youtube.com/shorts/"+VIDEO,
            "youtube:video:"+VIDEO,"vnd.youtube:"+VIDEO})
            check(YouTubeTrack.extract("org.smarttube.beta",value,"").equals(VIDEO),"supported ID "+value);
        for(String value:new String[]{"https://youtube.com.evil/watch?v="+VIDEO,"https://evil/youtube.com/watch?v="+VIDEO,
            "http://youtube.com/watch?v="+VIDEO,"https://user@youtube.com/watch?v="+VIDEO,"https://youtube.com:444/watch?v="+VIDEO,
            "https://youtube.com/watch?v="+VIDEO+"&v="+OTHER,"https://youtu.be/"+VIDEO+"/extra","some text "+VIDEO,
            "spotify:track:"+SPOTIFY,"https://youtube.com/watch?v=../bad","https://youtube.com/watch?v=%GG"})
            check(YouTubeTrack.extract("org.smarttube.beta",value,"").isEmpty(),"reject bad ID "+value);
        check(YouTubeTrack.extract("com.spotify.tv.android",VIDEO,"").isEmpty(),"Spotify ID never guessed as YouTube");
        check(YouTubeTrack.extract("other.player",VIDEO,"").isEmpty(),"unknown player not guessed");
        check(YouTubeTrack.extract("com.google.android.youtube.tv","","https://youtu.be/"+VIDEO).equals(VIDEO),"media URI fallback");
        check(YouTubeTrack.extract("org.smarttube.beta",VIDEO,"https://youtu.be/"+OTHER).isEmpty(),"conflicting identities rejected");
        check(YouTubeTrack.extract("org.smarttube.beta",VIDEO,"https://youtu.be/"+VIDEO).equals(VIDEO),"consistent identities accepted");
        JSONObject mapping=new JSONObject().put("spotify",SPOTIFY).put("youtube",VIDEO);
        check(AggregateLyrics.reference(mapping,SPOTIFY)[1].isEmpty(),"valid catalogue video without ISRC");
        check(AggregateLyrics.reference(mapping.put("isrc",JSONObject.NULL),SPOTIFY)[0].equals(VIDEO),"null ISRC optional");
        check(AggregateLyrics.reference(mapping.put("isrc","bad"),SPOTIFY)==null,"supplied invalid ISRC rejected");
        check(AggregateLyrics.reference(mapping.put("isrc","").put("youtube",JSONObject.NULL),SPOTIFY)==null,"ISRC alone is not a video");
        String form=AggregateLyrics.form("Song","Artist","",10000,VIDEO,"","synthetic.token");
        check(form.startsWith("videoId="+VIDEO+"&song=") && !form.contains("isrc="),"gateway without optional ISRC");
        check(reference(unison())[0].equals(VIDEO),"strict metadata fallback resolves video");
        JSONObject wrong=unison();wrong.getJSONObject("data").put("song","Song - Live");check(reference(wrong)==null,"live version rejected");
        wrong=unison();wrong.getJSONObject("data").put("artist","Other");check(reference(wrong)==null,"artist rejected");
        wrong=unison();wrong.getJSONObject("data").put("duration",13);check(reference(wrong)==null,"different duration rejected");
        wrong=unison();wrong.getJSONObject("data").remove("duration");check(reference(wrong)==null,"missing duration is not a reference");
        wrong=unison();wrong.getJSONObject("data").remove("duration");wrong.getJSONObject("data").put("isrc","JPX401800138");
        check(AggregateLyrics.unisonReference(wrong,"Song","Artist",10000,"JPX401800138")[1].equals("JPX401800138"),"independent ISRC resolves absent duration");
        check(AggregateLyrics.unisonReference(wrong,"Song","Artist",10000,"JPX401800139")==null,"different ISRC without duration rejected");
        check(AggregateLyrics.isrc(new JSONObject().put("spotify",SPOTIFY).put("isrc","JPX401800138"),SPOTIFY).equals("JPX401800138"),"ISRC retained when video mapping missing");
        check(AggregateLyrics.isrc(new JSONObject().put("spotify","other").put("isrc","JPX401800138"),SPOTIFY).isEmpty(),"ISRC must belong to same Spotify ID");
        wrong=unison();wrong.getJSONObject("data").put("matchScore",.8);check(reference(wrong)==null,"weak match rejected");
        wrong=unison();wrong.getJSONObject("data").put("hidden",true);check(reference(wrong)==null,"hidden record rejected");
        wrong=unison();wrong.getJSONObject("data").put("videoId","https://evil/");check(reference(wrong)==null,"reference is ID only");
        JSONObject info=MultiMediaCheck.info();String pkg="org.smarttube.beta";
        info.put("source",pkg).put("sources",new JSONArray().put(MultiMediaCheck.choice(MultiMediaCheck.A,pkg))).put("youtubeId",VIDEO);
        check(MultiMediaCheck.parse(info).youtubeId.equals(VIDEO),"video metadata crosses bridge packet");
        MultiMediaCheck.rejects(info.put("youtubeId","../bad"),"bad video packet");count++;
        MultiMediaCheck.rejects(MultiMediaCheck.info().put("youtubeId",VIDEO),"video on non YouTube source");count++;
        check(MultiMediaCheck.parse(MultiMediaCheck.info()).youtubeId.isEmpty(),"old helper packet compatible");
        LiveLyrics live=new LiveLyrics((lyrics,status)->{});
        try {
            live.setTrack("Song","Artist","","",VIDEO,10000);String first=key(live);
            live.setTrack("Song","Artist","","",OTHER,10000);check(!key(live).equals(first),"different video invalidates cache key");
            live.setTrack("Song","Artist","","",10000);check(!key(live).equals(first),"legacy caller has no stale video");
        }finally{live.close();}
        final boolean[] byVideo={false};
        LyricSources.find("Song","Artist","","",VIDEO,10000,url->{
            if(url.contains("unison.betterlyrics.org")){byVideo[0]|=url.contains("&v="+VIDEO);return null;}return null;
        },result->{});
        check(byVideo[0],"known video reaches existing Unison source");
        JSONObject early=unison();early.getJSONObject("data").put("format","plain").put("syncType","plain").put("lyrics","Synthetic text")
            .put("submitter",new JSONObject().put("displayName","Curator").put("keyId",String.join("",java.util.Collections.nCopies(64,"a"))));
        LiveLyrics.Result preview=LyricSources.unisonResult(early,"Song","Artist","",10000,false);
        check(preview.lyrics!=null && !preview.lyrics.isSynced() && preview.lyrics.contributor().equals("Curator"),"early source uses same parser and attribution");
        System.out.println("RECORDING_CHECK_OK cases="+count);
    }
}
