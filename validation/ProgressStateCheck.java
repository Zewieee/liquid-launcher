package local.launcherfix;
import android.os.*;import java.lang.reflect.*;import java.util.*;
public final class ProgressStateCheck {
 static int count;static void check(boolean value,String name){count++;if(!value)throw new AssertionError(name);}
 static void set(LiveLyrics live,String name,Object value)throws Exception{Field f=LiveLyrics.class.getDeclaredField(name);f.setAccessible(true);f.set(live,value);}
 static Object progress(LiveLyrics live,String key)throws Exception{set(live,"active",true);set(live,"key",key);Class<?> c=Class.forName("local.launcherfix.LiveLyrics$Progress");Constructor<?> init=c.getDeclaredConstructor(LiveLyrics.class,long.class,String.class,LiveLyrics.Result.class);init.setAccessible(true);return init.newInstance(live,0L,key,null);}
 static void offer(Object progress,LiveLyrics.Result result)throws Exception{Method m=progress.getClass().getDeclaredMethod("offer",LiveLyrics.Result.class);m.setAccessible(true);m.invoke(progress,result);}
 public static void main(String[] args)throws Exception{
  Looper.prepareMainLooper();Handler main=new Handler(Looper.getMainLooper());List<Boolean> delivered=new ArrayList<>();int[] stale={0},closed={0};
  LiveLyrics valid=new LiveLyrics((lyrics,status)->{if(lyrics!=null)delivered.add(lyrics.hasWords());});
  LiveLyrics changed=new LiveLyrics((lyrics,status)->{if(lyrics!=null)stale[0]++;});
  LiveLyrics ended=new LiveLyrics((lyrics,status)->{if(lyrics!=null)closed[0]++;});
  LiveLyrics.Result line=new LiveLyrics.Result(ProviderLyrics.ttml(SourceChainCheck.ttml(false).getString("ttml"),10000),"ready",10000);
  LiveLyrics.Result words=new LiveLyrics.Result(ProviderLyrics.ttml(SourceChainCheck.ttml(true).getString("ttml"),10000),"ready",10000);
  Object active=progress(valid,"valid-state");offer(active,line);offer(active,words);offer(active,line);
  Object old=progress(changed,"old-state");offer(old,line);set(changed,"generation",1L);set(changed,"key","new-state");
  Object gone=progress(ended,"closed-state");offer(gone,line);ended.close();
  main.post(()->{try{
   check(delivered.equals(Arrays.asList(false,true)),"line before words with no downgrade");
   check(stale[0]==0,"queued old-track stage discarded");check(closed[0]==0,"queued closed-popup stage discarded");
   Field cache=LiveLyrics.class.getDeclaredField("CACHE");cache.setAccessible(true);Map<?,?> entries=(Map<?,?>)cache.get(null);LiveLyrics.Result cached=(LiveLyrics.Result)entries.get("valid-state");
   check(cached!=null && cached.lyrics.hasWords() && !cached.complete,"latest preview cached for resumable work");
   check(!entries.containsKey("old-state") && !entries.containsKey("closed-state"),"stale stages cannot fill cache");
   valid.close();changed.close();System.out.println("PROGRESS_STATE_CHECK_OK cases="+count);System.out.flush();System.exit(0);
  }catch(Throwable error){error.printStackTrace();System.exit(1);}});Looper.loop();
 }
}
