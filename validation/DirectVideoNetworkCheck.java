package local.launcherfix;

import android.os.*;
import org.json.*;
import java.lang.reflect.*;

/** Public lookup using production HTTP/parsers; no account state or playback commands. */
public final class DirectVideoNetworkCheck {
    public static void main(String[] args)throws Exception {
        Looper.prepareMainLooper();
        LiveLyrics live=new LiveLyrics((data,status)->{});
        Method read=LiveLyrics.class.getDeclaredMethod("readJson",String.class,long.class,long.class);read.setAccessible(true);
        long deadline=SystemClock.elapsedRealtime()+12000;boolean[] sawVideo={false};
        try {
            LiveLyrics.Result result=LyricSources.find("Explorer","Justice","Hyperdrama","","Ugr4KKK_SqY",249452,url->{
                if(!url.contains("unison.betterlyrics.org"))return null;
                sawVideo[0]|=url.contains("&v=Ugr4KKK_SqY");
                try{return (JSONObject)read.invoke(live,url,0L,deadline);}
                catch(InvocationTargetException error){throw (Exception)error.getCause();}
            },stage->{});
            if(!sawVideo[0] || result.lyrics==null || !result.lyrics.hasWords() || !result.lyrics.source().equals("Unison"))
                throw new AssertionError("Direct video source unavailable: "+result.status);
            System.out.println("DIRECT_VIDEO_NETWORK_OK lines="+result.lyrics.size()+" words="+result.lyrics.hasWords()+" source="+result.lyrics.source());
        }finally{live.close();}
    }
}
