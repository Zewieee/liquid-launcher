package local.launcherbridge;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Creates silent, temporary test sessions. Never sends commands to another app. */
public final class LiveMediaCheck {
    static Method packet,control;static MediaSessionManager manager;static int count;
    static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);count++;}
    static JSONObject read(String preferred)throws Exception {
        byte[] bytes=(byte[])packet.invoke(null,manager,"",preferred);
        DataInputStream input=new DataInputStream(new ByteArrayInputStream(bytes));
        check(input.readInt()==0x4c4d4231,"frame");byte[] json=new byte[input.readInt()];input.readFully(json);
        return new JSONObject(new String(json,"UTF-8"));
    }
    static boolean send(String id,int key)throws Exception{return (Boolean)control.invoke(null,manager,id,key);}
    static PlaybackState state(int value,long actions){return new PlaybackState.Builder().setActions(actions).setState(value,12000,value==3 ? 1 : 0).build();}
    static void await(AtomicInteger counter,int expected)throws Exception {
        long end=SystemClock.elapsedRealtime()+2500;
        while(counter.get()<expected && SystemClock.elapsedRealtime()<end)Thread.sleep(30);
        check(counter.get()==expected,"callback count "+expected);
    }
    public static void main(String[] args)throws Exception {
        Looper.prepareMainLooper();
        Method contextMethod=SpotifyMediaBridge.class.getDeclaredMethod("shellContext");contextMethod.setAccessible(true);
        Context context=(Context)contextMethod.invoke(null);
        Field shell=SpotifyMediaBridge.class.getDeclaredField("shell");shell.setAccessible(true);shell.set(null,context);
        manager=(MediaSessionManager)context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        packet=SpotifyMediaBridge.class.getDeclaredMethod("packet",MediaSessionManager.class,String.class,String.class);packet.setAccessible(true);
        control=SpotifyMediaBridge.class.getDeclaredMethod("control",MediaSessionManager.class,String.class,int.class);control.setAccessible(true);
        MediaSession a=new MediaSession(context,"liquid119-test-a"),b=new MediaSession(context,"liquid119-test-b");
        AtomicInteger aCalls=new AtomicInteger(),bCalls=new AtomicInteger();
        a.setCallback(new MediaSession.Callback(){@Override public void onPlay(){aCalls.incrementAndGet();}},new Handler(Looper.getMainLooper()));
        b.setCallback(new MediaSession.Callback(){
            @Override public void onPlay(){b.setPlaybackState(state(3,566));bCalls.incrementAndGet();}
            @Override public void onPause(){b.setPlaybackState(state(2,566));bCalls.incrementAndGet();}
            @Override public void onSkipToNext(){bCalls.incrementAndGet();}
            @Override public void onSkipToPrevious(){bCalls.incrementAndGet();}
        },new Handler(Looper.getMainLooper()));
        a.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,"Silent test A").putString(MediaMetadata.METADATA_KEY_ARTIST,"Fixture").putLong(MediaMetadata.METADATA_KEY_DURATION,200000).build());
        b.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,"Silent test B").putString(MediaMetadata.METADATA_KEY_ARTIST,"Fixture").putLong(MediaMetadata.METADATA_KEY_DURATION,200000).build());
        a.setPlaybackState(state(2,4));b.setPlaybackState(state(3,566));a.setActive(true);b.setActive(true);
        a.setFlags(3);b.setFlags(3);
        new Thread(()->{
            int exit=0;
            try {
                Field idsField=SpotifyMediaBridge.class.getDeclaredField("ids");idsField.setAccessible(true);
                Map<?,?> ids=(Map<?,?>)idsField.get(null);
                String aid=null,bid=null;
                long ready=SystemClock.elapsedRealtime()+2500;
                do {
                    read("");
                    for(MediaController controller:manager.getActiveSessions(null)) {
                        MediaMetadata metadata=controller.getMetadata();
                        String title=metadata==null ? "" : metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
                        if("Silent test A".equals(title))aid=(String)ids.get(controller.getSessionToken());
                        if("Silent test B".equals(title))bid=(String)ids.get(controller.getSessionToken());
                    }
                    if(aid!=null && bid!=null)break;Thread.sleep(80);
                }while(SystemClock.elapsedRealtime()<ready);
                check(aid!=null && bid!=null && !aid.equals(bid),"distinct token IDs even same package");
                JSONObject selected=read(aid);
                check(selected.getString("title").equals("Silent test A"),"manual paused session");
                check(selected.getString("source").equals("com.android.shell") && selected.getString("spotifyId").isEmpty(),"non Spotify identity");
                check(read(bid).getJSONObject("playback").getInt("state")==3,"other session playback");
                check(send(bid,85),"pause accepted");await(bCalls,1);
                check(read(bid).getJSONObject("playback").getInt("state")==2,"paused state observed");
                check(send(bid,85),"play accepted");await(bCalls,2);
                check(send(bid,87),"next accepted");await(bCalls,3);
                check(send(bid,88),"previous accepted");await(bCalls,4);
                check(aCalls.get()==0,"unselected source untouched");
                check(!send(aid,87),"unsupported action rejected");
                check(!send(bid,26),"arbitrary key rejected");
                b.release();Thread.sleep(150);
                check(!send(bid,85),"released session never rerouted");
                JSONObject fallback=read(bid);
                check(!fallback.getString("sessionId").equals(bid),"missing manual source falls back");
                check(aCalls.get()==0,"no fallback command");
                System.out.println("LIVE_MEDIA_CHECK_OK cases="+count+" silentFixturesOnly=true");
            }catch(Throwable error){error.printStackTrace();exit=1;}
            finally {a.release();b.release();}
            System.exit(exit);
        },"media119-check").start();
        Looper.loop();
    }
}
