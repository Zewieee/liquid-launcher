package local.launcherfix;

import org.json.*;
import java.util.*;

public final class CandidateCheck {
    static int count;
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);count++;}
    static JSONObject record(long id,String name,String artist,double seconds)throws Exception {
        return new JSONObject().put("id",id).put("trackName",name).put("artistName",artist).put("albumName","Album")
            .put("duration",seconds).put("hasSyncedLyrics",true).put("hasPlainLyrics",true).put("hasWordSync",false)
            .put("syncedLyrics","[00:01]Synthetic first\n[00:04]Synthetic next").put("plainLyrics","Synthetic first\nSynthetic next");
    }
    static List<LyricCandidates.Candidate> candidates(JSONObject... records)throws Exception {
        JSONArray list=new JSONArray();for(JSONObject record:records)list.put(record);
        return LyricCandidates.parse(new JSONObject().put("results",list),"Song","Artist",10000);
    }
    public static void main(String[] args)throws Exception {
        JSONObject record=record(1,"Song","Artist",10);
        List<LyricCandidates.Candidate> list=candidates(record);
        check(list.size()==1 && list.get(0).synced,"full record listed");
        JSONObject summary=new JSONObject(record.toString());summary.remove("syncedLyrics");summary.remove("plainLyrics");
        check(candidates(summary).size()==1,"lightweight summary supported");
        check(candidates(record(2,"Song","Artist",18)).size()==1,"manual duration tolerance extends beyond automatic");
        check(candidates(record(2,"Song","Artist",26)).isEmpty(),"outside duration tolerance rejected");
        check(candidates(record(2,"Song - Live","Artist",10)).isEmpty(),"live variant rejected");
        check(candidates(record(2,"Song (Remix)","Artist",10)).isEmpty(),"remix variant rejected");
        check(candidates(record(2,"Song - TV size","Artist",10)).isEmpty(),"TV size rejected");
        check(candidates(record(2,"Song (Official Audio)","Artist",10)).size()==1,"decorative title suffix supported");
        check(candidates(record(2,"Song","Artist feat. Guest",10)).size()==1,"artist credit delimiters supported");
        check(candidates(record(2,"Song","Other",10)).isEmpty(),"different artist rejected");
        check(candidates(record(2,"Other","Artist",10)).isEmpty(),"different song rejected");
        check(candidates(record(2,"Song","Artist",10).put("instrumental",true)).isEmpty(),"instrumental not offered");
        check(candidates(record(2,"Song","Artist",10).put("id",-1)).isEmpty(),"negative id rejected");
        check(candidates(record(2,"Song","Artist",10).put("id",1.5)).isEmpty(),"fractional id rejected");
        check(candidates(record(2,"Song","Artist",10).put("id","2")).isEmpty(),"typed id rejected");
        check(candidates(record(2,"Song","Artist",10).put("duration","10")).isEmpty(),"typed duration rejected");
        check(candidates(record(2,"Song","Artist",10).put("trackName",String.join("",Collections.nCopies(513,"a")))).isEmpty(),"oversized metadata rejected");
        check(candidates(record,record).size()==1,"duplicates removed");
        ArrayList<JSONObject> many=new ArrayList<>();for(int i=1;i<=30;i++)many.add(record(i,"Song","Artist",10));
        check(candidates(many.toArray(new JSONObject[0])).size()==8,"bounded candidate count");
        JSONObject plain=record(3,"Song","Artist",10).put("hasSyncedLyrics",false).put("syncedLyrics",JSONObject.NULL);
        check(!candidates(plain).get(0).synced,"plain record labeled unsynced");
        check(candidates(plain,record).get(0).synced,"timed choices ranked before plain");
        Lyrics selected=LyricCandidates.selected(record,list.get(0),"Song","Artist",10000);
        check(selected!=null && selected.isSynced() && selected.at(0).equals("Synthetic first"),"selected full record parsed");
        check(LyricCandidates.selected(record(99,"Song","Artist",10),list.get(0),"Song","Artist",10000)==null,"changed record id rejected");
        check(LyricCandidates.selected(record(1,"Other","Artist",10),list.get(0),"Song","Artist",10000)==null,"changed title rejected");
        check(LyricCandidates.selected(record(1,"Song","Artist",12),list.get(0),"Song","Artist",10000)==null,"changed duration rejected");
        JSONObject late=record(1,"Song","Artist",10).put("syncedLyrics","[00:20]Outside recording");
        check(LyricCandidates.selected(late,list.get(0),"Song","Artist",10000)==null,"invalid cue bounds rejected");
        check(LyricCandidates.selected(plain,candidates(plain).get(0),"Song","Artist",10000)!=null,"plain remains available");
        check(!LyricCandidates.selected(plain,candidates(plain).get(0),"Song","Artist",10000).hasWords(),"plain never creates word timing");
        check(LyricCandidates.searchUrl("A & B","Artist",false,true).contains("track_name=A+%26+B&artist_name=Artist"),"encoded summary URL");
        check(LyricCandidates.searchUrl("Song","Artist",true,false).equals("https://lrclib.net/api/search?track_name=Song"),"stable broad fallback URL");
        System.out.println("CANDIDATE_CHECK_OK cases="+count);
    }
}
