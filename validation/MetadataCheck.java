package local.launcherfix;
import android.net.*;import android.os.*;import android.graphics.*;import org.json.*;
import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;
public class MetadataCheck {
 static class Case {String name;byte[] packet;boolean valid;int width;Case(String n,byte[] p,boolean v,int w){name=n;packet=p;valid=v;width=w;}}
 static final List<Case> cases=new ArrayList<>();static int index;static Handler main;
 static JSONObject info() throws Exception {return new JSONObject().put("version",1).put("source","com.spotify.tv.android").put("revision",String.join("",Collections.nCopies(64,"a"))).put("title","曲名 🎵").put("artist","Nghệ sĩ");}
 static byte[] frame(int magic,byte[] json,int artLength,byte[] art)throws Exception{
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
  out.writeInt(magic);out.writeInt(json.length);out.write(json);out.writeInt(artLength);out.write(art);return bytes.toByteArray();
 }
 static byte[] packet(JSONObject json,int len,byte[] art)throws Exception{return frame(0x4c4d4231,json.toString().getBytes(StandardCharsets.UTF_8),len,art);}
 static byte[] png(int width)throws Exception {Bitmap b=Bitmap.createBitmap(width,32,Bitmap.Config.ARGB_8888);b.eraseColor(0xff2070ab);ByteArrayOutputStream bytes=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.PNG,100,bytes);b.recycle();return bytes.toByteArray();}
 static void next(){
  Case c=cases.get(index);
  try {
   SpotifyMetadata.Response response=null;boolean connected=false;
   try { response=SpotifyMetadata.parse(new DataInputStream(new ByteArrayInputStream(c.packet)), "valid-unchanged".equals(c.name)?String.join("",Collections.nCopies(64,"a")):"");connected=true; }
   catch(Exception invalid) { if(c.valid)throw invalid; }
   if(connected!=c.valid)throw new AssertionError(c.name+" connected="+connected);
   if(connected&&(!response.title.equals("曲名 🎵")||!response.artist.equals("Nghệ sĩ")))throw new AssertionError("Unicode metadata");
   if(c.width>0&&(response.art==null||response.art.getWidth()!=c.width))throw new AssertionError("Artwork decoding");
   if("valid-unchanged".equals(c.name)&&!response.unchanged)throw new AssertionError("Changed revision caching");
   if("valid-clock".equals(c.name)&&(!response.playback.playing()||response.playback.positionAt(11000)!=13000))throw new AssertionError("Playing clock");
   if("valid-paused".equals(c.name)&&(response.playback.playing()||response.playback.positionAt(90000)!=12000))throw new AssertionError("Paused clock");
   if("valid-album".equals(c.name)&&!response.album.equals("アルバム"))throw new AssertionError("Album Unicode");
   if("valid-id".equals(c.name)&&!response.spotifyId.equals("0123456789ABCDEFGHIJKL"))throw new AssertionError("Spotify id");
   if("valid-unicode".equals(c.name)&&!response.spotifyId.isEmpty())throw new AssertionError("Old helper id fallback");
   if("valid-unicode".equals(c.name)&&!response.album.isEmpty())throw new AssertionError("Old helper album fallback");
   if("valid-unicode".equals(c.name)&&response.playback.known)throw new AssertionError("Old helper must not invent state");
   System.out.println("PASS "+c.name);System.out.flush();index++;
  }catch(Throwable error){error.printStackTrace();System.exit(1);}
 }
 static JSONObject clock()throws Exception{return new JSONObject().put("duration",240000).put("position",12000).put("updated",10000).put("speed",1).put("state",3);}
 public static void main(String[] args)throws Exception{
  Looper.prepareMainLooper();main=new Handler(Looper.getMainLooper());byte[] empty=new byte[0];
  cases.add(new Case("valid-id",packet(info().put("spotifyId","0123456789ABCDEFGHIJKL"),0,empty),true,0));
  cases.add(new Case("invalid-id",packet(info().put("spotifyId","../bad"),0,empty),false,0));
  cases.add(new Case("null-id",packet(info().put("spotifyId",JSONObject.NULL),0,empty),false,0));
  cases.add(new Case("typed-id",packet(info().put("spotifyId",42),0,empty),false,0));
  cases.add(new Case("valid-unicode",packet(info(),0,empty),true,0));byte[] image=png(32);
  cases.add(new Case("valid-artwork",packet(info(),image.length,image),true,32));
  cases.add(new Case("unchanged-without-revision",packet(info(),-1,empty),false,0));
  cases.add(new Case("bad-magic",frame(9,empty,0,empty),false,0));
  for(int length:new int[]{-1,8193}){ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream o=new DataOutputStream(b);o.writeInt(0x4c4d4231);o.writeInt(length);cases.add(new Case("json-length-"+length,b.toByteArray(),false,0));}
  byte[] good=packet(info(),0,empty);cases.add(new Case("truncated-json",Arrays.copyOf(good,20),false,0));
  cases.add(new Case("invalid-json",frame(0x4c4d4231,"xx".getBytes(StandardCharsets.UTF_8),0,empty),false,0));
  cases.add(new Case("wrong-version",packet(info().put("version",2),0,empty),false,0));
  cases.add(new Case("wrong-source",packet(info().put("source","other.app"),0,empty),false,0));
  cases.add(new Case("long-title",packet(info().put("title",String.join("",Collections.nCopies(513,"a"))),0,empty),false,0));
  cases.add(new Case("bad-revision",packet(info().put("revision","bad"),0,empty),false,0));
  cases.add(new Case("negative-art",packet(info(),-2,empty),false,0));
  cases.add(new Case("huge-art",packet(info(),524289,empty),false,0));
  cases.add(new Case("truncated-art",packet(info(),10,new byte[]{1}),false,0));
  cases.add(new Case("invalid-image",packet(info(),2,new byte[]{1,2}),false,0));
  byte[] big=png(321);cases.add(new Case("oversized-dimensions",packet(info(),big.length,big),false,0));
  cases.add(new Case("valid-unchanged",packet(info(),-1,empty),true,0));
  cases.add(new Case("valid-clock",packet(info().put("playback",clock()),0,empty),true,0));
  cases.add(new Case("valid-paused",packet(info().put("playback",clock().put("state",2)),0,empty),true,0));
  cases.add(new Case("negative-duration",packet(info().put("playback",clock().put("duration",-1)),0,empty),false,0));
  cases.add(new Case("negative-position",packet(info().put("playback",clock().put("position",-2)),0,empty),false,0));
  cases.add(new Case("negative-timestamp",packet(info().put("playback",clock().put("updated",-1)),0,empty),false,0));
  cases.add(new Case("huge-speed",packet(info().put("playback",clock().put("speed",1000000)),0,empty),false,0));
  cases.add(new Case("invalid-state",packet(info().put("playback",clock().put("state",12)),0,empty),false,0));
  cases.add(new Case("missing-clock-fields",packet(info().put("playback",new JSONObject()),0,empty),false,0));
  cases.add(new Case("valid-album",packet(info().put("album","アルバム"),0,empty),true,0));
  cases.add(new Case("long-album",packet(info().put("album",String.join("",Collections.nCopies(513,"a"))),0,empty),false,0));
  cases.add(new Case("null-album",packet(info().put("album",JSONObject.NULL),0,empty),false,0));
  while(index<cases.size())next();
  System.out.println("METADATA_CHECK_OK cases="+index);
 }
}
