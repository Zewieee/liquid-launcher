package local.launcherfix;

import android.os.*;

public final class ManualLyricsCheck {
    static int count;
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);count++;}
    public static void main(String[] args)throws Exception {
        Looper.prepareMainLooper();Handler main=new Handler(Looper.getMainLooper());
        LiveLyrics live=new LiveLyrics((lyrics,status)->{});
        Lyrics chosen=Lyrics.parse("[00:01]Synthetic chosen\n[00:04]Next");
        live.setTrack("Song","Artist",10000);String key=live.trackKey();
        check(!live.isManual(),"automatic initially");
        check(live.choose(key,chosen,42) && live.isManual(),"manual selection cached");
        check(live.selectedId()==42,"chosen record ID retained");
        live.refresh();check(live.isManual(),"provider access refresh cannot overwrite choice");
        live.start();
        main.postDelayed(()->{try {
            live.stop();check(live.isManual(),"manual survives focus loss and conversion");
            check(live.selectedId()==42,"record marker survives conversion");
            live.setTrack("Other","Artist",10000);check(!live.isManual(),"choice isolated to track");
            check(!live.choose(key,chosen),"stale selection rejected");
            live.setTrack("Song","Artist",10000);check(live.isManual(),"recent same-track choice retained");
            check(live.choose(key,null) && !live.isManual(),"automatic clears manual choice");
            check(live.selectedId()==0,"automatic clears record marker");
            check(!live.choose(key,Lyrics.plain("")),"empty selection rejected");
            live.close();check(!live.choose(key,chosen),"closed player rejects selection");
            System.out.println("MANUAL_LYRICS_CHECK_OK cases="+count);System.out.flush();System.exit(0);
        }catch(Throwable error){error.printStackTrace(System.out);System.exit(1);}},700);
        Looper.loop();
    }
}
