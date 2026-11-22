package local.launcherfix;
import org.json.*;
import java.util.*;

/** Spotify's supplied line clock only; word timing is never inferred. */
final class SpotifyLyrics {
 static String url(String id) {
  if(!id.matches("[A-Za-z0-9]{22}"))throw new IllegalArgumentException("Spotify track ID");
  return "https://spclient.wg.spotify.com/color-lyrics/v2/track/"+id+"?format=json&market=from_token";
 }
 static Lyrics parse(JSONObject response,long duration)throws Exception {
  JSONObject data=response.getJSONObject("lyrics");String sync=data.getString("syncType");
  if(!sync.equals("LINE_SYNCED") && !sync.equals("UNSYNCED"))throw new IllegalArgumentException("Unsupported Spotify timing");
  JSONArray lines=data.getJSONArray("lines");if(lines.length()>2048)throw new IllegalArgumentException("Spotify line count");
  TreeMap<Long,String> timed=new TreeMap<>();StringBuilder plain=new StringBuilder();int size=0;
  for(int i=0;i<lines.length();i++) {
   JSONObject line=lines.getJSONObject(i);Object words=line.get("words");
   if(!(words instanceof String) || ((String)words).length()>512)throw new IllegalArgumentException("Spotify line text");
   String text=(String)words;size+=text.length();if(size>65536)throw new IllegalArgumentException("Spotify lyrics size");
   if(sync.equals("UNSYNCED")){if(plain.length()>0)plain.append('\n');plain.append(text);continue;}
   String start=line.getString("startTimeMs");if(!start.matches("[0-9]{1,8}"))throw new IllegalArgumentException("Spotify time");
   long ms=Long.parseLong(start);if(ms>duration+2000)throw new IllegalArgumentException("Spotify recording bounds");
   if(!timed.containsKey(ms))timed.put(ms,text);
  }
  Lyrics result;
  if(sync.equals("UNSYNCED"))result=Lyrics.plain(plain.toString());
  else {long[] times=new long[timed.size()];String[] text=new String[timed.size()];int i=0;
   for(Map.Entry<Long,String> line:timed.entrySet()){times[i]=line.getKey();text[i++]=line.getValue();}
   result=Lyrics.timed(times,text,null);
  }
  return result.fromSource(data.optString("providerDisplayName","").equalsIgnoreCase("Musixmatch") ? "Spotify · Musixmatch" : "Spotify");
 }
}
