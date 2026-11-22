package local.launcherfix;
import org.json.*;import java.util.*;
public final class LatencyCheck {
 static int count;static void check(boolean value,String name){count++;if(!value)throw new AssertionError(name);}
 public static void main(String[] args)throws Exception{
  check(LyricSources.quick(SourceChainCheck.lrc(),"Song","Artist",10000).lyrics.size()==2,"fast exact result");
  check(LyricSources.quick(null,"Song","Artist",10000).lyrics==null,"quick miss preserves chain");
  check(LyricSources.quick(SourceChainCheck.lrc(),"Song - Live","Artist",10000).lyrics==null,"fast live version guard");
  check(LyricSources.quick(SourceChainCheck.lrc(),"Song","Other",10000).lyrics==null,"fast artist guard");
  check(LyricSources.quick(SourceChainCheck.lrc(),"Song","Artist",20000).lyrics==null,"fast duration guard");
  check(LyricSources.quick(SourceChainCheck.lrc(),"Song","Artist, Guest",10000).lyrics!=null,"same credited artist matching as existing search");
  check(LyricSources.lrcUrl("A & B","Artist",10000).contains("track_name=A+%26+B"),"encoded quick URL");
  JSONObject candidates=new JSONObject().put("results",new JSONArray().put(SourceChainCheck.lrc()));
  check(LyricSources.quickSearch(candidates,"Song","Artist, Guest",10000).lyrics!=null,"early candidate search handles multiple credits");
  check(LyricSources.quickSearch(candidates,"Other","Artist",10000).lyrics==null,"early candidate search keeps identity guard");
  final int[] stages={0};
  LiveLyrics.Result result=LyricSources.find("Song","Artist","","",10000,url->{
   if(url.contains("/getLyrics?") && !url.contains("/qq/") && !url.contains("/kugou/"))return SourceChainCheck.ttml(false);
   check(stages[0]>0,"valid line published before later provider reads");return null;
  },candidate->{stages[0]++;check(candidate.lyrics!=null,"no empty stage");});
  check(stages[0]==1 && result.lyrics!=null,"early stage retained despite later misses");
  List<Boolean> timing=new ArrayList<>();String q="<QrcInfos><Lyric_1 LyricContent='[1000,3000]Synthetic(1000,3000)'/></QrcInfos>";
  result=LyricSources.find("Song","Artist","","",10000,url->url.contains("/qq/") ? new JSONObject().put("provider","qq").put("lyrics",q) : SourceChainCheck.ttml(false),candidate->timing.add(candidate.lyrics.hasWords()));
  check(timing.size()==2 && !timing.get(0) && timing.get(1) && result.lyrics.hasWords(),"line stage upgrades to actual word timing");
  check(!new LiveLyrics.Result(result.lyrics,"ready",1000,false).complete,"preview cache remains resumable");
  check(new LiveLyrics.Result(result.lyrics,"ready",1000).complete,"final cache remains final");
  Thread.currentThread().interrupt();try{LyricSources.find("Song","Artist","","",10000,url->{throw new AssertionError("Cancelled read");},candidate->{throw new AssertionError("Cancelled stage");});throw new AssertionError("Cancel lost");}catch(java.io.InterruptedIOException expected){count++;}finally{Thread.interrupted();}
  System.out.println("LATENCY_CHECK_OK cases="+count);
 }
}
