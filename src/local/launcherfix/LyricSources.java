package local.launcherfix;

import org.json.JSONObject;
import org.json.JSONArray;
import java.io.InterruptedIOException;
import java.net.URLEncoder;

/** Prefer supplied word/syllable timing; retain a line-synced result as fallback. */
final class LyricSources {
    interface Reader { JSONObject read(String url) throws Exception; }
    static LiveLyrics.Result find(String title,String artist,String album,long duration,Reader reader) throws Exception {
        return find(title,artist,album,"",duration,reader);
    }
    static LiveLyrics.Result find(String title,String artist,String album,String spotifyId,long duration,Reader reader) throws Exception {
        return find(title,artist,album,spotifyId,duration,reader,result->{});
    }
    static String lrcUrl(String title,String artist,long duration) throws Exception {
        return "https://lrclib.net/api/get?track_name="+encode(title)+"&artist_name="+encode(artist)+"&duration="+String.format(java.util.Locale.ROOT,"%.3f",duration/1000.0);
    }
    static LiveLyrics.Result quick(JSONObject info,String title,String artist,long duration) throws Exception {
        return info==null ? ready(null) : search(new JSONObject().put("results",new JSONArray().put(info)),title,artist,duration,new LyricMatch());
    }
    static String quickSearchUrl(String title)throws Exception{return "https://lrclib.net/api/search?track_name="+encode(LyricMatch.cleanTitle(title));}
    static LiveLyrics.Result quickSearch(JSONObject info,String title,String artist,long duration)throws Exception{return info==null ? ready(null) : search(info,title,artist,duration,new LyricMatch());}
    static LiveLyrics.Result unisonResult(JSONObject info,String title,String artist,String album,long duration,boolean identified)throws Exception {
        return info==null ? ready(null) : ready(unison(info,title,artist,album,duration,null,identified));
    }
    static LiveLyrics.Result unisonMatchedResult(JSONObject info,String title,String artist,String album,long duration,LyricMatch match,boolean identified)throws Exception {
        return info==null ? ready(null) : ready(unison(info,title,artist,album,duration,match,identified));
    }
    static Lyrics biniLookup(JSONObject response,String title,String artist,String album,long duration,Reader reader,LyricMatch match,String[] isrcOut)throws Exception {
        return bini(response,title,artist,album,duration,reader,match,isrcOut);
    }
    static LiveLyrics.Result find(String title,String artist,String album,String spotifyId,long duration,Reader reader,java.util.function.Consumer<LiveLyrics.Result> progress) throws Exception {
        return find(title,artist,album,spotifyId,"",duration,reader,progress);
    }
    static LiveLyrics.Result find(String title,String artist,String album,String spotifyId,String videoId,long duration,Reader reader,java.util.function.Consumer<LiveLyrics.Result> progress) throws Exception {
        boolean identified=spotifyId.matches("[A-Za-z0-9]{22}");
        String query="s="+encode(title)+"&a="+encode(artist)+"&al="+encode(album)+"&d="+(duration/1000);
        String[] urls={"https://api.betterlyrics.org/getLyrics?"+query,
            AggregateLyrics.unisonUrl(title,artist,album,spotifyId,videoId,duration),
            "https://lrc.red/api/v1?track="+encode(title)+"&artist="+encode(artist)+"&album="+encode(album)+"&duration="+(duration/1000),
            "https://api.betterlyrics.org/qq/getLyrics?"+query,
            lrcUrl(title,artist,duration),
            "https://api.betterlyrics.org/kugou/getLyrics?"+query};
        LiveLyrics.Result best=null; boolean unavailable=false;
        for (int i=0;i<urls.length;i++) {
            if (i==5 && best!=null && best.lyrics.isSynced()) break;
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled source lookup");
            try {
                JSONObject info=reader.read(urls[i]); if (info==null) continue;
                LiveLyrics.Result result;
                if (i==4) result=LiveLyrics.parse(info,title,artist,duration);
                else if (i==1) result=ready(unison(info,title,artist,album,duration,null,identified));
                else if (i==2) result=ready(bini(info,title,artist,album,duration,reader,null));
                else {
                    if (info.has("score")) {
                        double score=info.getDouble("score");
                        if (Double.isNaN(score) || Double.isInfinite(score) || score<.85 || score>1) continue;
                    }
                    Lyrics lyrics;
                    if (i==0) lyrics=ProviderLyrics.ttml(info.getString("ttml"),duration).fromSource("Better Lyrics TTML");
                    else if (i==3) {
                        if (!"qq".equals(info.optString("provider","qq"))) continue;
                        lyrics=ProviderLyrics.qrc(info.getString("lyrics"),title,artist,duration).fromSource("Better Lyrics · QQ");
                    } else {
                        if (!"kugou".equals(info.optString("provider","kugou"))) continue;
                        lyrics=ProviderLyrics.kugou(info.getString("lyrics"),title,artist,duration).fromSource("Better Lyrics · KuGou");
                    }
                    result=new LiveLyrics.Result(lyrics.size()>0 ? lyrics : null,lyrics.size()>0 ? "ready" : "missing",3600000);
                }
                if (result.status.equals("instrumental") && best==null) return result;
                if (result.lyrics!=null) {
                    progress.accept(result);
                    if (result.lyrics.hasWords()) return result;
                    if (best==null || rank(result.lyrics)>rank(best.lyrics)) best=result;
                }
            } catch (java.net.SocketTimeoutException timeout) { unavailable=true; }
            catch (InterruptedIOException cancelled) { throw cancelled; }
            catch (Exception missingOrUnavailable) { unavailable=true; }
            // Kugou is line-only and cannot improve an existing timed-line fallback.
            if (i==4 && best!=null && best.lyrics.isSynced()) break;
        }
        if (best!=null && best.lyrics.isSynced()) return best;
        LyricMatch match=new LyricMatch(); String clean=LyricMatch.cleanTitle(title), primary=LyricMatch.primaryArtist(artist);
        String roman=match.reading(clean);
        String[] fallback={quickSearchUrl(title),
            "https://unison.betterlyrics.org/lyrics?song="+encode(clean)+"&artist="+encode(primary)+"&duration="+(duration/1000),
            "https://lrc.red/api/v1?track="+encode(clean)+"&artist="+encode(primary)+"&duration="+(duration/1000),
            roman.equals(clean) ? "" : "https://lrclib.net/api/search?track_name="+encode(roman)};
        for (int i=0;i<fallback.length;i++) {
            if (fallback[i].isEmpty()) continue;
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled fallback lookup");
            try {
                JSONObject info=reader.read(fallback[i]); if (info==null) continue;
                LiveLyrics.Result result=i==0 || i==3 ? search(info,title,artist,duration,match)
                    : i==1 ? ready(unison(info,title,artist,"",duration,match,false))
                    : ready(bini(info,title,artist,"",duration,reader,match));
                if (result.lyrics!=null) {
                    progress.accept(result);
                    if (result.lyrics.hasWords()) return result;
                    if (best==null || rank(result.lyrics)>rank(best.lyrics)) best=result;
                }
            } catch (java.net.SocketTimeoutException timeout) { unavailable=true; }
            catch (InterruptedIOException cancelled) { throw cancelled; }
            catch (Exception missingOrUnavailable) { unavailable=true; }
        }
        if (best!=null && !best.lyrics.isSynced()) return new LiveLyrics.Result(best.lyrics,"ready",unavailable ? 60000 : 600000);
        return best!=null ? best : new LiveLyrics.Result(null,unavailable ? "unavailable" : "missing",unavailable ? 60000 : 600000);
    }
    private static int rank(Lyrics lyrics) { return lyrics.hasWords() ? 3 : lyrics.isSynced() ? 2 : 1; }
    private static LiveLyrics.Result search(JSONObject info,String title,String artist,long duration,LyricMatch match) throws Exception {
        JSONArray records=info.getJSONArray("results");
        LiveLyrics.Result strictBest=null, mvBest=null;
        double strictDist=Double.POSITIVE_INFINITY, mvDist=Double.POSITIVE_INFINITY;
        for (int i=0;i<Math.min(20,records.length());i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled candidate match");
            try {
                JSONObject record=records.getJSONObject(i); String name=record.getString("trackName"), who=record.getString("artistName");
                double seconds=record.getDouble("duration");
                boolean strict=match.record(title,artist,duration,name,who,seconds);
                boolean mv=!strict && match.recordMvFallback(title,artist,duration,name,who,seconds);
                if (!strict && !mv) continue;
                LiveLyrics.Result candidate=strict
                    ? LiveLyrics.parse(record,name,who,duration)
                    : LiveLyrics.parseCandidate(record,name,who,Math.round(seconds*1000.0),duration);
                double delta=Math.abs(seconds*1000-duration);
                if (candidate.lyrics!=null) {
                    if (strict) {
                        if (strictBest==null || rank(candidate.lyrics)>rank(strictBest.lyrics)
                            || rank(candidate.lyrics)==rank(strictBest.lyrics) && delta<strictDist) {
                            strictBest=candidate; strictDist=delta;
                        }
                    } else if (strictBest==null) {
                        if (mvBest==null || rank(candidate.lyrics)>rank(mvBest.lyrics)
                            || rank(candidate.lyrics)==rank(mvBest.lyrics) && delta<mvDist) {
                            mvBest=candidate; mvDist=delta;
                        }
                    }
                }
            } catch (RuntimeException | org.json.JSONException invalidCandidate) { /* A malformed result does not hide valid candidates. */ }
        }
        LiveLyrics.Result chosen=strictBest!=null ? strictBest : mvBest;
        return chosen==null ? ready(null) : chosen;
    }
    private static LiveLyrics.Result ready(Lyrics lyrics) {
        return new LiveLyrics.Result(lyrics!=null && lyrics.size()>0 ? lyrics : null,
            lyrics!=null && lyrics.size()>0 ? "ready" : "missing",3600000);
    }
    private static boolean sameArtist(String requested,String returned) {
        if (Lyrics.sameTrack(requested,returned)) return true;
        for (String artist:requested.split("[,;]")) {
            boolean found=false;
            for (String credited:returned.split("[,;]")) if (!artist.trim().isEmpty() && Lyrics.sameTrack(artist,credited)) found=true;
            if (!found) return false;
        }
        return true;
    }
    private static boolean albumMatches(String requested,String returned) {
        return requested.isEmpty() || returned.isEmpty() || Lyrics.sameTrack(requested,returned);
    }
    private static boolean durationMatches(double seconds,long duration) {
        return !Double.isNaN(seconds) && !Double.isInfinite(seconds) && Math.abs(seconds*1000-duration)<=2000;
    }
    private static Lyrics unison(JSONObject response,String title,String artist,String album,long duration,LyricMatch match,boolean identified) throws Exception {
        if (!response.optBoolean("success",false)) return null;
        JSONObject data=response.getJSONObject("data");
        boolean recording=identified && "isrc".equals(data.optString("matchedBy","")) && data.optString("isrc","").matches("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}");
        if (data.optBoolean("hidden",false) || !recording && (match==null
            ? !Lyrics.sameTrack(title,data.getString("song")) || !sameArtist(artist,data.getString("artist")) || !albumMatches(album,data.optString("album",""))
            : !match.title(title,artist,data.getString("song")) || !match.artist(artist,data.getString("artist")))) return null;
        long candDurMs=0L;
        boolean mvDur=false;
        if (data.has("duration")) {
            double sec=data.getDouble("duration");
            boolean strictDur=durationMatches(sec,duration);
            mvDur=!strictDur && match!=null && match.recordMvFallback(title,artist,duration,data.getString("song"),data.getString("artist"),sec);
            if (!strictDur && !mvDur) return null;
            candDurMs=Math.round(sec*1000.0);
        }
        if (data.has("matchScore")) {
            double score=data.getDouble("matchScore");
            if (Double.isNaN(score) || Double.isInfinite(score) || score<.85 || score>1) return null;
        }
        String video=data.getString("videoId"); JSONObject submitter=data.getJSONObject("submitter");
        String name=submitter.getString("displayName").trim(), key=submitter.getString("keyId");
        if (!video.matches("[A-Za-z0-9_-]{11}") || !key.matches("[a-fA-F0-9]{64}")
            || name.isEmpty() || name.length()>80 || name.matches("(?s).*[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069].*")) return null;
        String format=data.getString("format"), sync=data.getString("syncType"); Lyrics lyrics;
        if (format.equals("ttml") && (sync.equals("richsync") || sync.equals("linesync")))
            lyrics=ProviderLyrics.ttml(data.getString("lyrics"),duration,recording || mvDur);
        else if (format.equals("lrc") && sync.equals("linesync"))
            lyrics=ProviderLyrics.kugou(data.getString("lyrics"),data.getString("song"),data.getString("artist"),duration);
        else if (format.equals("plain") && sync.equals("plain")) lyrics=Lyrics.plain(data.getString("lyrics"));
        else return null;
        Lyrics built=lyrics.fromSource("Unison").withCredit("https://unison.betterlyrics.org/song/"+video,name,
            "https://unison.betterlyrics.org/curator/"+key);
        return built.withQuality(LyricMatch.computeQuality(built,duration,candDurMs,recording));
    }
    private static Lyrics bini(JSONObject response,String title,String artist,String album,long duration,Reader reader,LyricMatch match) throws Exception {
        return bini(response,title,artist,album,duration,reader,match,null);
    }
    private static Lyrics bini(JSONObject response,String title,String artist,String album,long duration,Reader reader,LyricMatch match,String[] isrcOut) throws Exception {
        JSONArray results=response.getJSONArray("results"); JSONObject selected=null, mvSelected=null;
        for (int i=0;i<Math.min(5,results.length());i++) {
            JSONObject item=results.getJSONObject(i);
            String trackName=item.optString("track_name",""), artistName=item.optString("artist_name","");
            double sec=item.optDouble("duration",-1);
            boolean strict=match==null
                ? Lyrics.sameTrack(title,trackName) && sameArtist(artist,artistName)
                    && albumMatches(album,item.optString("album_name","")) && durationMatches(sec,duration)
                : match.record(title,artist,duration,trackName,artistName,sec);
            boolean mv=!strict && match!=null && match.recordMvFallback(title,artist,duration,trackName,artistName,sec);
            if (!strict && !mv) continue;
            String isrc=item.optString("isrc",""); if (!isrc.matches("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")) continue;
            String url=item.optString("lyricsUrl","");
            if (!url.equals("https://lrc.red/s/"+isrc+".ttml") && !url.equals("https://lyrics-storage.binimum.org/"+isrc+".ttml")) continue;
            if (strict) {
                if (selected==null) selected=item;
                if (item.optString("timing_type","").equals("word")) { selected=item; break; }
            } else if (selected==null) {
                if (mvSelected==null || item.optString("timing_type","").equals("word")) mvSelected=item;
            }
        }
        JSONObject chosen=selected!=null ? selected : mvSelected;
        if (chosen==null) return null;
        if (isrcOut!=null && isrcOut.length>0) isrcOut[0]=chosen.optString("isrc","");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled Bini lookup");
        JSONObject body=reader.read(chosen.getString("lyricsUrl"));
        if (body==null) return null;
        Lyrics built=ProviderLyrics.ttml(body.getString("ttml"),duration,true).fromSource("BiniLyrics");
        long candMs=Math.round(chosen.optDouble("duration",0)*1000.0);
        return built.withQuality(LyricMatch.computeQuality(built,duration,candMs,true));
    }
    private static String encode(String text) throws Exception { return URLEncoder.encode(text,"UTF-8"); }
}
