package local.launcherfix;

import java.io.*;
import org.json.*;

public final class MultiMediaCheck {
    static int count;
    static final String A="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", B="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);count++;}
    static JSONObject choice(String id,String pkg) throws Exception {
        return new JSONObject().put("id",id).put("package",pkg).put("label","音楽 · Nhạc").put("state",3);
    }
    static JSONObject info() throws Exception {
        return new JSONObject().put("version",2).put("source","org.videolan.vlc").put("sessionId",A)
            .put("sources",new JSONArray().put(choice(A,"org.videolan.vlc")))
            .put("title","曲名").put("artist","Artist").put("album","").put("spotifyId","")
            .put("revision",A+A).put("playback",new JSONObject().put("duration",200000).put("position",12000)
                .put("updated",10000).put("speed",1).put("state",3).put("actions",566));
    }
    static SpotifyMetadata.Response parse(JSONObject info) throws Exception {
        byte[] json=info.toString().getBytes("UTF-8");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(0x4c4d4231);out.writeInt(json.length);out.write(json);out.writeInt(0);
        return SpotifyMetadata.parse(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),"");
    }
    static void rejects(JSONObject data,String name)throws Exception {
        try{parse(data);}catch(IOException|JSONException expected){count++;return;}throw new AssertionError(name);
    }
    public static void main(String[] args)throws Exception {
        check(MediaSources.choose(new String[]{A,B},new int[]{2,3},"")==1,"playing beats paused");
        check(MediaSources.choose(new String[]{A,B},new int[]{3,2},B)==1,"manual beats playing");
        check(MediaSources.choose(new String[]{A,B},new int[]{2,3},"gone")==1,"missing manual returns auto");
        check(MediaSources.choose(new String[0],new int[0],A)==-1,"empty list");
        check(MediaSources.choose(new String[]{A,B},new int[]{2,6},"")==1,"buffering beats paused");
        check(MediaSources.choose(new String[]{A,B},new int[]{3,3},"")==0,"platform priority for two playing");
        check(MediaSources.choose(new String[]{A,B},new int[]{1,2},"")==1,"paused beats stopped");
        check(MediaSources.validPackage("app.revanced.android.apps.youtube.music"),"arbitrary valid package");
        check(!MediaSources.validPackage("../bad") && !MediaSources.validId("bad"),"bad identifiers");
        check(MediaSources.supports(32,87,false) && !MediaSources.supports(32,88,false),"next only");
        check(MediaSources.supports(16,88,false) && !MediaSources.supports(16,87,false),"previous only");
        check(MediaSources.supports(2,85,true) && !MediaSources.supports(2,85,false),"pause supported");
        check(MediaSources.supports(4,85,false) && !MediaSources.supports(4,85,true),"play supported");
        check(MediaSources.supports(512,85,true) && MediaSources.supports(512,85,false),"toggle supported");
        check(!MediaSources.supports(Long.MAX_VALUE,26,true),"reject arbitrary key");
        SpotifyMetadata.Response result=parse(info());
        check(result.source.equals("org.videolan.vlc") && result.sources.length==1 && result.sessionId.equals(A),"non Spotify metadata");
        check(result.sourceLabel().equals("音楽 · Nhạc") && result.playback.positionAt(11000)==13000,"label and clock");
        check(result.spotifyId.isEmpty() && result.actions==566,"generic lyrics without Spotify id");
        JSONObject two=info();two.getJSONArray("sources").put(choice(B,"com.spotify.tv.android"));
        check(parse(two).sources.length==2,"two sources");
        JSONObject empty=info().put("source","").put("sessionId","").put("sources",new JSONArray());empty.remove("playback");
        check(parse(empty).sources.length==0 && !parse(empty).playback.known,"no session");
        rejects(info().put("source","other.package"),"package mismatch");
        rejects(info().put("sessionId",B),"unlisted selected session");
        rejects(info().put("sources",new JSONArray().put(choice(A,"org.videolan.vlc")).put(choice(A,"org.videolan.vlc"))),"duplicate ids");
        rejects(info().put("sessionId","../bad"),"malformed id");
        rejects(info().put("sources",new JSONArray().put(choice(A,"../bad"))),"bad package");
        rejects(info().put("spotifyId","0123456789ABCDEFGHIJKL"),"cross app Spotify id rejected");
        JSONObject actions=info();actions.getJSONObject("playback").put("actions",-1);rejects(actions,"negative actions");
        rejects(info().put("source","").put("sessionId","") ,"sources without selection");
        JSONArray many=new JSONArray();for(int i=0;i<17;i++)many.put(choice(String.format("%032x",i),"org.videolan.vlc"));
        rejects(info().put("sources",many),"bounded sources");
        JSONObject badState=info();badState.getJSONArray("sources").getJSONObject(0).put("state",12);rejects(badState,"bad state");
        android.graphics.Bitmap hd=android.graphics.Bitmap.createBitmap(800,600,android.graphics.Bitmap.Config.ARGB_8888);
        hd.eraseColor(0xff305080);ByteArrayOutputStream hdOut=new ByteArrayOutputStream();
        hd.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,hdOut);hd.recycle();
        byte[] hdArt=hdOut.toByteArray(),hdJson=info().toString().getBytes("UTF-8");
        ByteArrayOutputStream hdFrame=new ByteArrayOutputStream();DataOutputStream hdDos=new DataOutputStream(hdFrame);
        hdDos.writeInt(0x4c4d4231);hdDos.writeInt(hdJson.length);hdDos.write(hdJson);hdDos.writeInt(hdArt.length);hdDos.write(hdArt);
        SpotifyMetadata.Response hdParsed=SpotifyMetadata.parse(new DataInputStream(new ByteArrayInputStream(hdFrame.toByteArray())),"");
        check(hdParsed.art!=null && hdParsed.art.getWidth()==800 && hdParsed.art.getHeight()==600,"v2 accepts 800px HD artwork");
        android.graphics.Bitmap tooBig=android.graphics.Bitmap.createBitmap(1025,32,android.graphics.Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream bigOut=new ByteArrayOutputStream();tooBig.compress(android.graphics.Bitmap.CompressFormat.PNG,100,bigOut);tooBig.recycle();
        byte[] bigArt=bigOut.toByteArray();
        ByteArrayOutputStream bigFrame=new ByteArrayOutputStream();DataOutputStream bigDos=new DataOutputStream(bigFrame);
        bigDos.writeInt(0x4c4d4231);bigDos.writeInt(hdJson.length);bigDos.write(hdJson);bigDos.writeInt(bigArt.length);bigDos.write(bigArt);
        try{SpotifyMetadata.parse(new DataInputStream(new ByteArrayInputStream(bigFrame.toByteArray())),"");throw new AssertionError("v2 oversized art");}
        catch(IOException expected){count++;}
        System.out.println("MULTI_MEDIA_CHECK_OK cases="+count);
    }
}
