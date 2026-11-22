package local.launcherfix;
import android.app.*;import android.os.*;import android.webkit.*;import android.widget.*;import android.view.*;
import android.graphics.Color;import android.net.Uri;
import java.net.*;import java.io.*;import java.nio.charset.StandardCharsets;import java.util.concurrent.*;
import org.json.JSONObject;

/** Opt-in official Web Player login. Observe its normal lyrics request; no cookie/token export or synthetic login. */
final class SpotifyAccess {
 private static volatile String access="",agent="";private static volatile long until,retryAt;
 static String authorization(){return SystemClock.elapsedRealtime()<until ? access : "";}
 static String agent(){return agent;}
 static void clear(){access="";until=0;}
 static boolean page(String value){try{Uri u=Uri.parse(value);String host=u.getHost();return "https".equals(u.getScheme()) && u.getUserInfo()==null && (u.getPort()==-1||u.getPort()==443) && ("open.spotify.com".equals(host)||"accounts.spotify.com".equals(host)||"www.spotify.com".equals(host));}catch(Exception bad){return false;}}
 static boolean lyricRequest(String value){try{Uri u=Uri.parse(value);return "https".equals(u.getScheme()) && "spclient.wg.spotify.com".equals(u.getHost()) && u.getUserInfo()==null && (u.getPort()==-1||u.getPort()==443) && u.getPath().matches("/color-lyrics/v2/track/[A-Za-z0-9]{22}");}catch(Exception bad){return false;}}
 static boolean sessionRequest(String value){try{Uri u=Uri.parse(value);return "https".equals(u.getScheme()) && u.getUserInfo()==null && (u.getPort()==-1||u.getPort()==443) && java.util.Arrays.asList("spclient.wg.spotify.com","api.spotify.com","api-partner.spotify.com").contains(u.getHost());}catch(Exception bad){return false;}}
 static Dialog show(Activity owner,boolean vi,String id,long duration,Runnable complete){
  Session session=new Session(owner,vi,id,duration,complete);session.open();return session.dialog;
 }
 private static final class Session {
  final Activity owner;final boolean vi;final String id;final long duration;final Runnable complete;
  final Dialog dialog;final Handler main=new Handler(Looper.getMainLooper());
  final ExecutorService worker=Executors.newSingleThreadExecutor();
  volatile boolean closed,checking;volatile String top="",last="";volatile HttpURLConnection connection;
  WebView web;TextView status;String userAgent="";
  Session(Activity owner,boolean vi,String id,long duration,Runnable complete){this.owner=owner;this.vi=vi;this.id=id;this.duration=duration;this.complete=complete;dialog=new Dialog(owner);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);}
  int px(int n){return (int)(n*owner.getResources().getDisplayMetrics().density);}
  void open(){try {
   if(!id.matches("[A-Za-z0-9]{22}")||duration<1000){Toast.makeText(owner,vi?"Mở một bài Spotify trước":"Open a Spotify track first",Toast.LENGTH_LONG).show();close();return;}
   LinearLayout panel=new LinearLayout(owner);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(px(18),px(14),px(18),px(14));
   panel.setBackground(WallpaperTheme.glass(owner.getResources().getDisplayMetrics().density,WallpaperTheme.sample(null)));
   TextView title=new TextView(owner);title.setText("Spotify · Lyrics");title.setTextSize(19);title.setTextColor(Color.WHITE);panel.addView(title);
   status=new TextView(owner);status.setText(vi?"Đăng nhập Spotify bằng email, rồi mở Lyrics trong trang. Back để quay lại.":"Sign in to Spotify using email, then open Lyrics on the page. Back to return.");status.setTextColor(0xccffffff);status.setTextSize(12);panel.addView(status);
   TextView disconnect=new TextView(owner);disconnect.setText(vi?"Ngừng dùng nguồn Spotify":"Disconnect Spotify lyrics");disconnect.setTextColor(Color.WHITE);disconnect.setFocusable(true);disconnect.setPadding(px(8),px(6),px(8),px(6));disconnect.setOnClickListener(v->{clear();dialog.dismiss();});panel.addView(disconnect);
   web=new WebView(owner);WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(false);
   userAgent=settings.getUserAgentString();web.setFocusable(true);web.setFocusableInTouchMode(true);
   web.setWebViewClient(new WebViewClient(){
    @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return request.isForMainFrame()&&!page(request.getUrl().toString());}
    @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap icon){top=url;}
    @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,android.net.http.SslError error){handler.cancel();main.post(()->status.setText(vi?"Không xác thực được kết nối Spotify":"Spotify connection could not be verified"));}
    @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
     if(!closed && page(top) && sessionRequest(request.getUrl().toString())) {
      String candidate="";for(java.util.Map.Entry<String,String> header:request.getRequestHeaders().entrySet())if(header.getKey().equalsIgnoreCase("Authorization"))candidate=header.getValue();
      if(candidate.matches("Bearer [A-Za-z0-9._~-]{20,8192}")) {final String value=candidate;main.post(()->check(value));}
     }
     return null;
    }
   });
   panel.addView(web,new LinearLayout.LayoutParams(px(820),px(360)));com.gathin.tvhome.Backdrop.wrap(owner,dialog,panel,true,0);FontChoice.apply(panel);
   dialog.setOnDismissListener(v->close());dialog.show();Window window=dialog.getWindow();if(window!=null)window.setLayout(-1,-1);
   web.requestFocus();web.loadUrl("https://accounts.spotify.com/login?continue="+Uri.encode("https://open.spotify.com/track/"+id));main.postDelayed(()->{if(!closed)dialog.dismiss();},600000);
  }catch(RuntimeException unavailable){Toast.makeText(owner,vi?"Chưa mở được Spotify web":"Spotify web is unavailable",Toast.LENGTH_LONG).show();close();}}
  void check(String candidate){
   if(closed||checking||candidate.equals(last)||SystemClock.elapsedRealtime()<retryAt)return;checking=true;last=candidate;status.setText(vi?"Đang kiểm tra nguồn lời Spotify…":"Checking Spotify lyrics…");
   worker.submit(()->{boolean success=false;int code=0;try {
    HttpURLConnection http=(HttpURLConnection)new URL(SpotifyLyrics.url(id)).openConnection();connection=http;
    http.setConnectTimeout(3000);http.setReadTimeout(5000);http.setInstanceFollowRedirects(false);http.setRequestProperty("Authorization",candidate);http.setRequestProperty("App-Platform","WebPlayer");http.setRequestProperty("User-Agent",userAgent);http.setRequestProperty("Accept","application/json");
    if(closed)throw new InterruptedIOException();code=http.getResponseCode();
    if(code==429||code==503){long wait=LiveLyrics.retryDelay(http.getHeaderField("Retry-After"),System.currentTimeMillis()),now=SystemClock.elapsedRealtime();retryAt=wait>=Long.MAX_VALUE-now ? Long.MAX_VALUE : now+wait;}
    if(code!=200)throw new IOException("Spotify access unavailable");
    if(http.getContentLengthLong()>262144)throw new IOException("Spotify size");ByteArrayOutputStream bytes=new ByteArrayOutputStream();long deadline=SystemClock.elapsedRealtime()+10000;
    try(InputStream input=http.getInputStream()){byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1){if(closed||Thread.currentThread().isInterrupted()||SystemClock.elapsedRealtime()>deadline)throw new InterruptedIOException();if(bytes.size()+n>262144)throw new IOException("Spotify size");bytes.write(buffer,0,n);}}
    Lyrics lyrics=SpotifyLyrics.parse(new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8)),duration);success=lyrics.size()>0;
   }catch(Exception unavailable){/* Keep the existing lyric sources; do not log credentials or response bodies. */}
   finally{HttpURLConnection http=connection;connection=null;if(http!=null)http.disconnect();}
   final boolean ready=success;final int response=code;
   main.post(()->{checking=false;if(closed)return;android.util.Log.i("SpotifyAccess","Lyrics verification HTTP="+response+" ready="+ready);
    if(ready){agent=userAgent;access=candidate;until=SystemClock.elapsedRealtime()+1200000;Toast.makeText(owner,vi?"Đã kết nối lời Spotify":"Spotify lyrics connected",Toast.LENGTH_LONG).show();dialog.dismiss();}
    else status.setText(vi?"Chưa lấy được lời. Đăng nhập rồi mở Lyrics; các nguồn cũ vẫn hoạt động.":"Lyrics unavailable. Sign in and open Lyrics; existing sources remain available.");
   });});
  }
  void close(){if(closed)return;closed=true;main.removeCallbacksAndMessages(null);HttpURLConnection http=connection;connection=null;if(http!=null)http.disconnect();worker.shutdownNow();if(web!=null){web.stopLoading();web.destroy();web=null;}if(dialog.isShowing())dialog.dismiss();complete.run();}
 }
}
