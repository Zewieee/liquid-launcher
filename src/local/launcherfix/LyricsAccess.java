package local.launcherfix;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.webkit.*;
import android.widget.*;
import android.view.*;
import org.json.JSONObject;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;

/** The provider's own verification page; only its verified HTTPS exchange supplies access. */
final class LyricsAccess {
    static final String ORIGIN="https://lyrics.api.dacubeking.com";
    private static final String CHALLENGE=ORIGIN+"/challenge", PREFS="lyrics_access";
    static long expiresAt(String jwt) {
        try {
            if (jwt.length()>8192 || !jwt.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) return 0;
            byte[] payload=android.util.Base64.decode(jwt.split("\\.")[1],android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP);
            JSONObject info=new JSONObject(new String(payload,StandardCharsets.UTF_8));
            if (!(info.opt("exp") instanceof Number)) return 0;
            long seconds=info.getLong("exp"); return seconds<=0 || seconds>Long.MAX_VALUE/1000 ? 0 : seconds*1000;
        } catch (Exception invalid) { return 0; }
    }
    static String token(Context context) {
        if (context==null) return "";
        String jwt=context.getSharedPreferences(PREFS,0).getString("jwt","");
        return expiresAt(jwt)>System.currentTimeMillis()+60000 ? jwt : "";
    }
    static String agent(Context context) {
        return context.getSharedPreferences(PREFS,0).getString("agent","LiquidLauncher/115 (Android TV lyrics)");
    }
    static void invalidate(Context context) {
        if (context!=null) context.getSharedPreferences(PREFS,0).edit().remove("jwt").apply();
    }
    static boolean challengePage(String address) {
        if (address==null) return false;
        Uri uri=Uri.parse(address);
        return "https".equals(uri.getScheme()) && "lyrics.api.dacubeking.com".equals(uri.getHost())
            && uri.getUserInfo()==null && (uri.getPort()==-1 || uri.getPort()==443) && "/challenge".equals(uri.getPath());
    }
    static Dialog show(Activity owner,boolean vi,Runnable complete) {
        if (System.currentTimeMillis()<owner.getSharedPreferences(PREFS,0).getLong("retry_until",0)) {
            Toast.makeText(owner,vi ? "Nguồn lời đang giới hạn truy cập; thử lại sau" : "Lyrics access is rate limited; try later",Toast.LENGTH_LONG).show(); return null;
        }
        Verification session=new Verification(owner,vi,complete); session.open(); return session.dialog;
    }
    private static final class Verification {
        final Activity owner; final boolean vi; final Runnable complete; final Dialog dialog;
        final Handler main=new Handler(Looper.getMainLooper());
        final ExecutorService worker=Executors.newSingleThreadExecutor();
        final String nonce=UUID.randomUUID().toString();
        WebView web; TextView status; volatile boolean closed; boolean exchanging;
        volatile HttpURLConnection connection;
        final Runnable timeout;
        Verification(Activity owner,boolean vi,Runnable complete) {
            this.owner=owner; this.vi=vi; this.complete=complete;
            timeout=()->finish(false,vi ? "Xác minh chưa hoàn tất; bạn có thể thử lại sau" : "Verification did not finish; you can try again later");
            dialog=new Dialog(owner); dialog.setOwnerActivity(owner);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        }
        void open() {
            try {
                float dp=owner.getResources().getDisplayMetrics().density;
                LinearLayout panel=new LinearLayout(owner) {
                    @Override protected void onMeasure(int width,int height) {
                        int wanted=(int)(540*dp);if(MeasureSpec.getMode(width)!=MeasureSpec.UNSPECIFIED)wanted=Math.min(wanted,MeasureSpec.getSize(width));
                        super.onMeasure(MeasureSpec.makeMeasureSpec(wanted,MeasureSpec.EXACTLY),height);
                    }
                }; panel.setOrientation(LinearLayout.VERTICAL);
                panel.setPadding((int)(24*dp),(int)(20*dp),(int)(24*dp),(int)(20*dp));
                WallpaperTheme.Tone tone=WallpaperTheme.sample(owner instanceof com.gathin.tvhome.MainActivity
                    && ((com.gathin.tvhome.MainActivity)owner).wallpaperPlayer!=null ? ((com.gathin.tvhome.MainActivity)owner).wallpaperPlayer.current : null);
                panel.setBackground(WallpaperTheme.glass(dp,tone));
                TextView title=new TextView(owner); title.setText(vi ? "Kết nối nguồn lời" : "Connect lyrics sources"); title.setTextColor(Color.WHITE); title.setTextSize(20);
                panel.addView(title,new LinearLayout.LayoutParams(-1,-2));
                status=new TextView(owner); status.setText(vi ? "Đang xác minh… Nếu có yêu cầu, hãy hoàn tất bên dưới. Nhấn Back để quay lại." : "Verifying… Complete any request below. Press Back to return.");
                status.setTextColor(0xbfffffff); status.setTextSize(13); status.setPadding(0,(int)(10*dp),0,(int)(14*dp)); panel.addView(status,new LinearLayout.LayoutParams(-1,-2));
                web=new WebView(owner); web.setFocusable(true); web.setFocusableInTouchMode(true);
                web.setBackgroundColor(0xffeeeeee);
                WebSettings settings=web.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
                settings.setAllowFileAccess(false); settings.setAllowContentAccess(false); settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
                settings.setJavaScriptCanOpenWindowsAutomatically(false); settings.setSupportMultipleWindows(false);
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(web,true);
                web.addJavascriptInterface(new Object() {
                    @JavascriptInterface public void receive(String receivedNonce,String type,String value) {
                        if (!nonce.equals(receivedNonce) || value==null || value.length()>4096) return;
                        main.post(()->accept(type,value));
                    }
                },"LiquidLyrics");
                web.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request) {
                        return request.isForMainFrame() && !challengePage(request.getUrl().toString()) && !"about:blank".equals(request.getUrl().toString());
                    }
                    @Override public void onPageFinished(WebView view,String url) {
                        installBridge(view,url);
                        if(challengePage(url))readCompletedResponse();
                    }
                    @Override public void onPageCommitVisible(WebView view,String url) { installBridge(view,url); }
                    @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap icon) { installBridge(view,url); }
                    @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error) {
                        if (request.isForMainFrame()) finish(false,vi ? "Chưa mở được trang xác minh" : "Could not open verification");
                    }
                    @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,android.net.http.SslError error) {
                        handler.cancel(); finish(false,vi ? "Kết nối xác minh chưa an toàn" : "Verification connection could not be secured");
                    }
                });
                panel.addView(web,new LinearLayout.LayoutParams(-1,(int)(190*dp)));
                com.gathin.tvhome.Backdrop.wrap(owner,dialog,panel,true,0);FontChoice.apply(panel);
                dialog.setOnDismissListener(ignored->dispose()); dialog.show();
                Window window=dialog.getWindow(); if (window!=null) window.setLayout(-1,-1);
                web.requestFocus(); main.postDelayed(timeout,120000);main.postDelayed(this::readCompletedResponse,8000);web.loadUrl(CHALLENGE);
            } catch (RuntimeException unavailable) { finish(false,vi ? "TV chưa mở được phần xác minh" : "Verification is unavailable on this TV"); }
        }
        void installBridge(WebView view,String url) {
            if (closed || !challengePage(url) || !challengePage(view.getUrl())) return;
            view.evaluateJavascript("(function(){if(window!==window.top||window.__liquidLyrics)return;window.__liquidLyrics=true;window.addEventListener('message',function(e){if(e.source!==window||e.origin!=="+JSONObject.quote(ORIGIN)+"||!e.data)return;var d=e.data;if(d.type==='turnstile-token'||d.type==='turnstile-error'||d.type==='turnstile-timeout')LiquidLyrics.receive("+JSONObject.quote(nonce)+",String(d.type),String(d.token||d.error||''));});})()",null);
        }
        void readCompletedResponse() {
            if(closed || exchanging || web==null || !challengePage(web.getUrl()))return;
            // A page may finish its normal widget callback before WebView installs the listener.
            web.evaluateJavascript("(function(){var t='';try{if(window.turnstile)t=turnstile.getResponse()||'';}catch(e){}if(t)LiquidLyrics.receive("+JSONObject.quote(nonce)+",'turnstile-token',t);return [document.readyState,typeof window.turnstile,!!t,!!window.__liquidLyrics,document.querySelectorAll('iframe').length].join('|');})()",
                value->{if(!closed)android.util.Log.i("LyricsAccess","Verification browser state="+value);});
        }
        void accept(String type,String value) {
            if (closed || exchanging || web==null || !challengePage(web.getUrl())) return;
            if (!"turnstile-token".equals(type)) { finish(false,vi ? "Xác minh chưa hoàn tất; thử lại sau" : "Verification did not finish; try later"); return; }
            if (!value.matches("[A-Za-z0-9._-]{20,4096}")) return;
            android.util.Log.i("LyricsAccess","Provider verification response received");
            exchanging=true; status.setText(vi ? "Đang kết nối nguồn lời…" : "Connecting lyrics sources…");
            final String agent=web.getSettings().getUserAgentString();
            worker.submit(()-> {
                String jwt=""; String failure=vi ? "Chưa kết nối được nguồn lời" : "Could not connect lyrics sources";
                try {
                    long deadline=SystemClock.elapsedRealtime()+15000;
                    if (closed || Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                    byte[] body=new JSONObject().put("token",value).toString().getBytes(StandardCharsets.UTF_8);
                    HttpURLConnection http=(HttpURLConnection)new URL(ORIGIN+"/verify-turnstile").openConnection(); connection=http;
                    http.setConnectTimeout(5000); http.setReadTimeout(6000); http.setInstanceFollowRedirects(false);
                    http.setRequestMethod("POST"); http.setDoOutput(true); http.setRequestProperty("Content-Type","application/json");
                    http.setRequestProperty("User-Agent",agent); http.setFixedLengthStreamingMode(body.length);
                    if (closed || Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                    try(OutputStream output=http.getOutputStream()) { output.write(body); }
                    int code=http.getResponseCode();
                    android.util.Log.i("LyricsAccess","Verification exchange HTTP="+code);
                    if (code==429 || code==503) {
                        long wait=LiveLyrics.retryDelay(http.getHeaderField("Retry-After"),System.currentTimeMillis()), now=System.currentTimeMillis();
                        owner.getSharedPreferences(PREFS,0).edit().putLong("retry_until",wait>=Long.MAX_VALUE-now ? Long.MAX_VALUE : now+wait).apply();
                    }
                    if (code!=200) throw new IOException("Verification refused");
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                    try(InputStream input=http.getInputStream()) {
                        byte[] buffer=new byte[2048]; int count;
                        while((count=input.read(buffer))!=-1) {
                            if (closed || Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime()>deadline) throw new java.io.InterruptedIOException();
                            if (bytes.size()+count>16384) throw new IOException("Verification bounds"); bytes.write(buffer,0,count);
                        }
                    }
                    jwt=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8)).getString("jwt");
                    if (expiresAt(jwt)<=System.currentTimeMillis()+60000) throw new IOException("Verification expired");
                } catch (Exception unavailable) { android.util.Log.i("LyricsAccess","Verification exchange unavailable: "+unavailable.getClass().getSimpleName());jwt=""; }
                finally { HttpURLConnection current=connection; connection=null; if(current!=null)current.disconnect(); }
                final String token=jwt, message=failure;
                main.post(()-> {
                    if (closed) return;
                    if (!token.isEmpty()) owner.getSharedPreferences(PREFS,0).edit().putString("jwt",token).putString("agent",agent).apply();
                    finish(!token.isEmpty(),token.isEmpty() ? message : (vi ? "Đã kết nối nguồn lời" : "Lyrics sources connected"));
                });
            });
        }
        void finish(boolean success,String message) {
            if (closed) return;
            if (message!=null) Toast.makeText(owner,message,Toast.LENGTH_LONG).show();
            if (dialog.isShowing()) dialog.dismiss(); else dispose();
        }
        void dispose() {
            if (closed) return; closed=true; main.removeCallbacksAndMessages(null);
            HttpURLConnection current=connection; connection=null; if(current!=null)current.disconnect(); worker.shutdownNow();
            if (web!=null) { web.stopLoading(); web.removeJavascriptInterface("LiquidLyrics"); web.destroy(); web=null; }
            complete.run();
        }
    }
}
