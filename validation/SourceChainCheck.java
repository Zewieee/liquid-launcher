package local.launcherfix;

import org.json.JSONObject;
import java.util.*;

public final class SourceChainCheck {
    static int count;
    static void check(boolean value,String name) { count++; if (!value) throw new AssertionError(name); }
    static JSONObject lrc() throws Exception {
        return new JSONObject().put("trackName","Song").put("artistName","Artist").put("duration",10)
            .put("syncedLyrics","[00:01]Synthetic first\n[00:04]Synthetic second");
    }
    static JSONObject ttml(boolean words) throws Exception {
        return new JSONObject().put("ttml","<tt><body dur='10'><div><p begin='1' end='4'>"
            +(words ? "<span begin='1' end='4'>Synthetic line</span>" : "Synthetic line")+"</p></div></body></tt>");
    }
    public static void main(String[] args) throws Exception {
        ArrayList<String> called=new ArrayList<>();
        LiveLyrics.Result rich=LyricSources.find("Song","Artist","Album & One",10000,url->{called.add(url);return ttml(true);});
        check(called.size()==1 && rich.lyrics.hasWords() && rich.lyrics.source().equals("Better Lyrics TTML"),"TTML priority");
        check(called.get(0).contains("al=Album+%26+One") && called.get(0).endsWith("&d=10"),"album and duration query");
        called.clear();
        LiveLyrics.Result old=LyricSources.find("Song","Artist","",10000,url->{called.add(url);return url.contains("lrclib.net") ? lrc() : null;});
        check(called.size()==5 && old.lyrics.source().equals("LRCLIB"),"auth/miss falls through to LRCLIB");
        called.clear();
        LiveLyrics.Result timeout=LyricSources.find("Song","Artist","",10000,url->{called.add(url);if(url.contains("betterlyrics")) throw new java.net.SocketTimeoutException();return lrc();});
        check(called.size()==5 && timeout.lyrics!=null,"provider timeout does not swallow fallback");
        called.clear();
        LiveLyrics.Result kugou=LyricSources.find("Song","Artist","",10000,url->{called.add(url);return url.contains("/kugou/") ? new JSONObject().put("provider","kugou").put("lyrics","[00:01]Kugou fallback") : null;});
        check(called.size()==6 && kugou.lyrics.source().contains("KuGou"),"Kugou final fallback");
        LiveLyrics.Result confidence=LyricSources.find("Song","Artist","",10000,url->url.contains("/getLyrics?") && !url.contains("/qq/") && !url.contains("/kugou/") ? ttml(true).put("score",.1) : url.contains("lrclib.net") ? lrc() : null);
        check(confidence.lyrics.source().equals("LRCLIB"),"low confidence skipped");
        LiveLyrics.Result malformed=LyricSources.find("Song","Artist","",10000,url->url.contains("lrclib.net") ? lrc() : new JSONObject().put("ttml","<!DOCTYPE tt><tt/>"));
        check(malformed.lyrics!=null && malformed.lyrics.source().equals("LRCLIB"),"bad XML fallback");
        String q="<QrcInfos><Lyric_1 LyricContent='[ti:Song][ar:Artist][1000,3000]Hello (1000,1000)world(2000,2000)'/></QrcInfos>";
        called.clear();
        LiveLyrics.Result qq=LyricSources.find("Song","Artist","",10000,url->{called.add(url);return url.contains("/qq/") ? new JSONObject().put("lyrics",q).put("provider","qq") : null;});
        check(called.size()==4 && qq.lyrics.hasWords() && qq.lyrics.source().contains("QQ"),"QQ word fallback");
        LiveLyrics.Result upgrade=LyricSources.find("Song","Artist","",10000,url->url.contains("/qq/") ? new JSONObject().put("lyrics",q).put("provider","qq") : ttml(false));
        check(upgrade.lyrics.source().contains("QQ") && upgrade.lyrics.hasWords(),"word source beats earlier line source");
        LiveLyrics.Result down=LyricSources.find("Song","Artist","",10000,url->{throw new java.io.IOException("temporary failure");});
        check(down.status.equals("unavailable") && down.lyrics==null,"temporary outage is not a cached lyric miss");
        LiveLyrics.Result retained=LyricSources.find("Song","Artist","",10000,url->{if(url.contains("/getLyrics?") && !url.contains("/qq/")) return ttml(false);throw new java.io.IOException("temporary failure");});
        check(retained.lyrics!=null && !retained.lyrics.hasWords(),"line fallback survives other outages");
        Thread.currentThread().interrupt();
        try { LyricSources.find("Song","Artist","",10000,url->{throw new AssertionError("No network on cancelled lookup");});throw new AssertionError("Cancel lost"); }
        catch(java.io.InterruptedIOException expected) { count++; }
        finally { Thread.interrupted(); }
        System.out.println("SOURCE_CHAIN_CHECK_OK cases="+count);
    }
}
