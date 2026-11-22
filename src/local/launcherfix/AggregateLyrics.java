package local.launcherfix;

import org.json.JSONObject;
import java.net.URLEncoder;
import java.util.*;

/** Bounded SSE adapter for the provider used by ytm-rs. No provider response controls a URL. */
final class AggregateLyrics {
    static String[] reference(JSONObject info,String spotifyId) {
        if(info==null || !spotifyId.matches("[A-Za-z0-9]{22}") || !spotifyId.equals(info.opt("spotify")))return null;
        Object video=info.opt("youtube"),isrc=info.opt("isrc");
        if(!(video instanceof String) || !YouTubeTrack.valid((String)video))return null;
        String code=isrc==null || isrc==JSONObject.NULL ? "" : isrc instanceof String ? (String)isrc : "invalid";
        if(!code.isEmpty() && !code.matches("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}"))return null;
        return new String[]{(String)video,code};
    }
    static String isrc(JSONObject info,String spotifyId) {
        if(info==null || !spotifyId.matches("[A-Za-z0-9]{22}") || !spotifyId.equals(info.opt("spotify")))return "";
        Object value=info.opt("isrc");return value instanceof String && ((String)value).matches("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}") ? (String)value : "";
    }
    static String unisonUrl(String title,String artist,String album,String spotifyId,String videoId,long duration)throws Exception {
        return "https://unison.betterlyrics.org/lyrics?song="+encode(title)+"&artist="+encode(artist)+"&album="+encode(album)
            +"&duration="+(duration/1000)+(spotifyId.matches("[A-Za-z0-9]{22}") ? "&spotify="+spotifyId : "")
            +(YouTubeTrack.valid(videoId) ? "&v="+videoId : "");
    }
    static String[] unisonReference(JSONObject response,String title,String artist,long duration) {
        return unisonReference(response,title,artist,duration,"");
    }
    static String[] unisonReference(JSONObject response,String title,String artist,long duration,String expectedIsrc) {
        try {
            if(response==null || !response.optBoolean("success",false))return null;
            JSONObject data=response.getJSONObject("data");
            LyricMatch match=new LyricMatch();
            boolean catalogue=expectedIsrc.matches("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}") && expectedIsrc.equals(data.opt("isrc"));
            if(data.optBoolean("hidden",false) || !(data.opt("song") instanceof String) || !(data.opt("artist") instanceof String)
                || !match.title(title,data.getString("song")) || !match.artist(artist,data.getString("artist")))return null;
            if(!catalogue && !(data.opt("duration") instanceof Number))return null;
            if(data.has("duration") && (!(data.opt("duration") instanceof Number) || !match.record(title,artist,duration,
                data.getString("song"),data.getString("artist"),data.getDouble("duration"))))return null;
            if(data.has("matchScore")) {
                double score=data.getDouble("matchScore");if(Double.isNaN(score) || Double.isInfinite(score) || score<.95 || score>1)return null;
            }
            String video=data.getString("videoId");if(!YouTubeTrack.valid(video))return null;
            return new String[]{video,catalogue ? expectedIsrc : ""};
        }catch(Exception invalid){return null;}
    }
    static String form(String title,String artist,String album,long duration,String video,String isrc,String jwt) throws Exception {
        if(!YouTubeTrack.valid(video) || !isrc.isEmpty() && !isrc.matches("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}"))throw new IllegalArgumentException("Recording reference required");
        return "videoId="+video+(isrc.isEmpty() ? "" : "&isrc="+isrc)+"&song="+encode(title)+"&artist="+encode(artist)+"&album="+encode(album)
            +"&duration="+(duration/1000)+"&alwaysFetchMetadata=false&token="+encode(jwt);
    }
    static Lyrics best(Lyrics first,Lyrics next) {
        if(first==null || first.size()==0)return next;if(next==null || next.size()==0)return first;
        int a=first.hasWords() ? 3 : first.isSynced() ? 2 : 1,b=next.hasWords() ? 3 : next.isSynced() ? 2 : 1;
        if(b!=a)return b>a ? next : first;
        if(first.quality()>0 && next.quality()>0 && next.quality()>=first.quality()+35)return next;
        return first;
    }
    static LiveLyrics.Result parse(String stream,String title,String artist,long duration) throws Exception {
        return parse(stream,title,artist,duration,false);
    }
    static LiveLyrics.Result parse(String stream,String title,String artist,long duration,boolean exactRecording) throws Exception {
        if(stream.length()>1048576)throw new IllegalArgumentException("Lyrics stream bounds");
        LinkedHashMap<String,Lyrics> sources=new LinkedHashMap<>();int events=0;String matchedTitle=title,matchedArtist=artist;
        for(String block:stream.replace("\r\n","\n").split("\n\n")) {
            if(++events>64)throw new IllegalArgumentException("Lyrics event count");
            if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Cancelled lyrics stream");
            if(block.length()>262144)throw new IllegalArgumentException("Lyrics event bounds");
            String event="";StringBuilder data=new StringBuilder();
            for(String line:block.split("\n")) {
                if(line.startsWith("event:"))event=line.substring(6).trim();
                else if(line.startsWith("data:"))data.append(line.substring(5).trim());
            }
            if(data.length()==0 || data.toString().equals("[DONE]"))continue;
            try {
                JSONObject info=new JSONObject(data.toString());
                if(event.equals("metadata")) {
                    if(!metadataMatches(info,title,artist,duration)){
                        android.util.Log.i("AggregateLyrics","Metadata did not match current recording");return missing();
                    }
                    matchedTitle=info.optString("song",title);matchedArtist=info.optString("artist",artist);
                    continue;
                }
                String provider=info.optString("provider","");
                if(!Arrays.asList("musixmatch","lrclib","golyrics","binimum","qq","kugou").contains(provider)){
                    android.util.Log.i("AggregateLyrics","Other event keys="+info.names()+" videoReference="+info.toString().toLowerCase(Locale.ROOT).contains("video"));continue;
                }
                JSONObject r=info.getJSONObject("results");Lyrics lyrics=null;
                android.util.Log.i("AggregateLyrics","Provider="+provider+" fields: words="+r.has("wordByWord")+" synced="+r.has("synced")+" plain="+r.has("plain")+" lyrics="+r.has("lyrics"));
                if(provider.equals("musixmatch") || provider.equals("lrclib")) {
                    for(String kind:new String[]{"wordByWord","synced","plain"}) {
                        if(!(r.opt(kind) instanceof String))continue;
                        String text=r.optString(kind,"");if(text.isEmpty())continue;
                        try {lyrics=best(lyrics,kind.equals("plain") ? Lyrics.plain(text) : EnhancedLrc.parse(text,duration));}
                        catch(IllegalArgumentException unsupported){/* Keep the other representation from this source. */}
                    }
                    if(lyrics!=null)lyrics=lyrics.fromSource(provider.equals("musixmatch") ? "Musixmatch" : "LRCLIB");
                } else if(provider.equals("golyrics") || provider.equals("binimum")) {
                    String text=r.getString("lyrics");
                    if(provider.equals("golyrics") && text.trim().startsWith("{"))text=new JSONObject(text).getString("ttml");
                    lyrics=ProviderLyrics.ttml(text,duration,exactRecording).fromSource(provider.equals("golyrics") ? "Better Lyrics TTML" : "BiniLyrics");
                } else if(provider.equals("qq") || provider.equals("kugou")) {
                    Object raw=r.get("lyrics");String text=raw instanceof JSONObject ? ((JSONObject)raw).getString("lyrics") : (String)raw;
                    if(text.trim().startsWith("{"))text=new JSONObject(text).getString("lyrics");
                    lyrics=(provider.equals("qq") ? ProviderLyrics.qrc(text,matchedTitle,matchedArtist,duration) : EnhancedLrc.parse(text,duration))
                        .fromSource(provider.equals("qq") ? "Better Lyrics · QQ" : "Better Lyrics · KuGou");
                }
                if(lyrics!=null && lyrics.size()>0)sources.put(provider,lyrics);
            } catch(Exception unavailable){android.util.Log.i("AggregateLyrics","Provider event unavailable: "+unavailable.getClass().getSimpleName());}
        }
        Lyrics selected=null;
        for(String provider:new String[]{"golyrics","binimum","qq","musixmatch","lrclib","kugou"})selected=best(selected,sources.get(provider));
        return selected==null ? missing() : new LiveLyrics.Result(selected,"ready",selected.isSynced() ? 3600000 : 600000);
    }
    private static boolean metadataMatches(JSONObject info,String title,String artist,long duration) {
        try {
            if(info.has("duration")) {
                double seconds=info.getDouble("duration");
                if(Double.isNaN(seconds)||Double.isInfinite(seconds)||Math.abs(seconds*1000-duration)>2000)return false;
            }
            LyricMatch match=new LyricMatch();
            if(info.has("song") && (!(info.opt("song") instanceof String) || !match.title(title,info.getString("song"))))return false;
            if(info.has("artist") && (!(info.opt("artist") instanceof String) || !match.artist(artist,info.getString("artist"))))return false;
            return true;
        }catch(Exception invalid){return false;}
    }
    private static LiveLyrics.Result missing(){return new LiveLyrics.Result(null,"missing",600000);}
    private static String encode(String value)throws Exception{return URLEncoder.encode(value,"UTF-8");}
}
