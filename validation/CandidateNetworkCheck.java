package local.launcherfix;

import android.os.*;
import java.util.*;
import org.json.*;

/** Read-only public API test; no UI, account state or transport actions. */
public final class CandidateNetworkCheck {
    public static void main(String[] args)throws Exception {
        Looper.prepareMainLooper();LiveLyrics reader=new LiveLyrics((lyrics,status)->{});
        try {
            String title="ニッポン笑顔百景",artist="Momokurotei Ichimon, Momoiro Clover Z";long duration=234000;
            JSONObject response=reader.searchRecords(LyricCandidates.searchUrl(title,artist,false,true),SystemClock.elapsedRealtime()+6000);
            // This small payload omits the reading dictionary; broad alias matching was exercised in the APK UI.
            JSONArray exact=new JSONArray();
            for(int i=0;response!=null && i<response.getJSONArray("results").length();i++) {
                JSONObject item=response.getJSONArray("results").getJSONObject(i);
                if(Lyrics.sameTrack(item.optString("trackName"),title) && item.optString("artistName").contains("Momokurotei"))exact.put(item);
            }
            List<LyricCandidates.Candidate> candidates=LyricCandidates.parse(new JSONObject().put("results",exact),title,artist,duration);
            if(candidates.isEmpty())throw new AssertionError("No candidates");
            LyricCandidates.Candidate selected=candidates.get(0);
            Lyrics lyrics=LyricCandidates.selected(reader.searchRecords("https://lrclib.net/api/get/"+selected.id,
                SystemClock.elapsedRealtime()+6000),selected,title,artist,duration);
            if(lyrics==null || !lyrics.hasWords())throw new AssertionError("Selected word-timed record unavailable");
            System.out.println("CANDIDATE_NETWORK_OK candidates="+candidates.size()+" selectedId="+selected.id+" lines="+lyrics.size()+" realWords="+lyrics.hasWords());
        }finally{reader.close();}
    }
}
