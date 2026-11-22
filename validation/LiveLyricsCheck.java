package local.launcherfix;

import android.os.*;
import org.json.JSONObject;

public final class LiveLyricsCheck {
    static int checks;
    static LiveLyrics live;
    static int delivered;
    static void check(boolean value, String name) { checks++; if (!value) throw new AssertionError(name); }
    static JSONObject record() throws Exception {
        return new JSONObject().put("trackName","Song").put("artistName","Artist").put("duration",180)
            .put("instrumental",false).put("syncedLyrics","[00:01]Synthetic first\n[00:03]Synthetic next");
    }
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        check(LiveLyrics.parse(record(),"Song","Artist",180000).lyrics.size()==2,"valid");
        check(LiveLyrics.parse(record(),"Other","Artist",180000).status.equals("missing"),"wrong title");
        check(LiveLyrics.parse(record(),"Song","Other",180000).status.equals("missing"),"wrong artist");
        check(LiveLyrics.parse(record(),"Song","Artist",183000).status.equals("missing"),"wrong duration");
        check(LiveLyrics.parse(record(),"Song","Artist",182000).lyrics!=null,"duration tolerance");
        check(LiveLyrics.parse(record().put("instrumental",true),"Song","Artist",180000).status.equals("instrumental"),"instrumental");
        check(LiveLyrics.parse(record().put("syncedLyrics",JSONObject.NULL),"Song","Artist",180000).status.equals("missing"),"unsynced only");
        check(LiveLyrics.parse(record().put("syncedLyrics","unmarked text"),"Song","Artist",180000).lyrics==null,"no fabricated sync");
        check(LiveLyrics.retryDelay("120",0)>=120000,"retry seconds");
        check(LiveLyrics.retryDelay("Thu, 01 Jan 1970 00:02:00 GMT",0)>=120000,"retry HTTP date");
        check(LiveLyrics.retryDelay(null,0)>=60000,"retry absent");
        check(LiveLyrics.retryDelay("garbage",0)>=60000,"retry invalid");
        check(LiveLyrics.retryDelay("9223372036854775807",0)==Long.MAX_VALUE,"retry overflow");
        check(LiveLyrics.retryDelay("0",0)>=0,"retry zero");
        String file="version: '1.0'\nmetadata: {title: Song, artist: Artist}\nlines:\n- text: 'Hello world'\n  start_ms: 1000\n  end_ms: 3000\n  words: [{text: 'Hello ', start_ms: 1000, end_ms: 2000}, {text: world, start_ms: 2000, end_ms: 3000}]\n";
        JSONObject timed=record().put("hasWordSync",true).put("lyricsfile",file);
        check(LiveLyrics.parse(timed,"Song","Artist",180000).lyrics.coverageAt(0,1500,false)==3,"word source preferred");
        check(LiveLyrics.parse(timed.put("lyricsfile","!!java.lang.ProcessBuilder {}"),"Song","Artist",180000).lyrics.at(0).equals("Synthetic first"),"unsafe source LRC fallback");
        check(LiveLyrics.parse(record().put("hasWordSync",false).put("lyricsfile",file),"Song","Artist",180000).lyrics.at(0).equals("Synthetic first"),"normal line source preserved");
        System.out.println("LYRICS_RESPONSE_CHECK_OK cases="+checks); System.out.flush();
        if (args.length==0) return;
        final boolean wordMode=args[0].equals("words");
        Handler main=new Handler(Looper.getMainLooper());
        live=new LiveLyrics((lyrics,status)-> {
            if (status.equals("loading") || status.equals("waiting")) return;
            try {
                check(status.equals("ready") && lyrics!=null && lyrics.size()>10,"real HTTPS lookup");
                if (wordMode) {
                    boolean found=false;
                    for (int i=0;i<lyrics.size();i++) if (lyrics.wordsAt(i)!=null && lyrics.wordsAt(i).length>0) { found=true; break; }
                    check(found,"real source word timing");
                }
                delivered++;
                System.out.println("NETWORK_RESULT status="+status+" lines="+lyrics.size()+" delivery="+delivered);System.out.flush();
                if(delivered==1) {
                    live.stop();
                    main.postDelayed(()->live.start(),100);
                } else {
                    live.close();
                    main.postDelayed(()-> { System.out.println("LYRICS_NETWORK_CACHE_RESUME_CLOSE_OK"); System.out.flush(); System.exit(0); },150);
                }
            } catch(Throwable error) {error.printStackTrace();System.exit(1);}
        });
        live.setTrack(wordMode ? "ミカヅキ" : "世界の秘密","Sayuri",wordMode ? 261000 : 348000); live.start();
        main.postDelayed(()-> {System.err.println("LYRICS_NETWORK_TIMEOUT");live.close();System.exit(1);},25000);
        Looper.loop();
    }
}
