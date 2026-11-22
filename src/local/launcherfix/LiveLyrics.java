package local.launcherfix;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded parallel keyless lookup per track while the popup is visible. Never sends account credentials. */
final class LiveLyrics {
    private static final String USER_AGENT = "LiquidLauncher/145 (Android TV lyrics; com.gathin.tvhome.liquid)";
    private final android.content.Context context;
    interface Listener { void onLyrics(Lyrics lyrics, String status); }
    static final class Result {
        final Lyrics lyrics; final String status; final long expires; final boolean complete,manual;
        final long selectedId;
        final String selectedKey;
        Result(Lyrics lyrics, String status, long lifetime) {
            this(lyrics,status,lifetime,true);
        }
        Result(Lyrics lyrics,String status,long lifetime,boolean complete) {
            this(lyrics,status,lifetime,complete,false,0,"");
        }
        Result(Lyrics lyrics,String status,long lifetime,boolean complete,boolean manual,long selectedId) {
            this(lyrics,status,lifetime,complete,manual,selectedId,selectedId>0 ? "LRCLIB:"+selectedId : "");
        }
        Result(Lyrics lyrics,String status,long lifetime,boolean complete,boolean manual,long selectedId,String selectedKey) {
            this.lyrics=lyrics;this.status=status;this.complete=complete;this.manual=manual;
            this.selectedId=selectedId;this.selectedKey=selectedKey==null ? "" : selectedKey;
            expires=SystemClock.elapsedRealtime()+lifetime;
        }
    }
    private static final Map<String,Result> CACHE = new LinkedHashMap<String,Result>(8, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Result> eldest) { return size() > 8; }
    };
    private static final Map<String,Long> BLOCKED_UNTIL=new LinkedHashMap<>();
    private static final Object PREFETCH_LOCK = new Object();
    private static LiveLyrics sPrefetcher;
    private static String sPrefetchKey = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final ThreadPoolExecutor worker = createWorker();
    private final ExecutorService pool = createIoPool();
    private static ThreadPoolExecutor createWorker() {
        ThreadPoolExecutor exec = new ThreadPoolExecutor(1, 1, 20L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, "music-lyrics");
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.setDaemon(true);
            return thread;
        });
        exec.allowCoreThreadTimeOut(true);
        return exec;
    }
    private static ExecutorService createIoPool() {
        ThreadPoolExecutor exec = new ThreadPoolExecutor(8, 8, 15L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, "music-lyrics-io");
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.setDaemon(true);
            return thread;
        });
        exec.allowCoreThreadTimeOut(true);
        return exec;
    }

    boolean workerAllowsCoreTimeout() {
        return worker.allowsCoreThreadTimeOut();
    }

    static String buildKey(String title, String artist, String album, String spotifyId, String youtubeId, long duration) {
        String safeTitle = title == null ? "" : title;
        String safeArtist = artist == null ? "" : artist;
        String safeAlbum = album == null ? "" : album;
        String safeSp = spotifyId != null && spotifyId.matches("[A-Za-z0-9]{22}") ? spotifyId : "";
        String safeYt = youtubeId != null && YouTubeTrack.valid(youtubeId) ? youtubeId : "";
        return safeTitle.isEmpty() || safeArtist.isEmpty() || duration < 1000 || duration > 3600000 ? ""
            : safeTitle + "\n" + safeArtist + "\n" + safeAlbum + "\n" + safeSp + "\n" + safeYt + "\n" + duration;
    }

    static boolean hasFreshCache(String trackKey) {
        if (trackKey == null || trackKey.isEmpty()) return false;
        synchronized (CACHE) {
            Result value = CACHE.get(trackKey);
            return value != null && value.complete && value.expires > SystemClock.elapsedRealtime();
        }
    }

    static int cacheSize() {
        synchronized (CACHE) { return CACHE.size(); }
    }

    /**
     * Warms ONLY the single currently playing track into transient in-memory CACHE so opening
     * the Music Player shows lyrics + ruby in 0ms. Never writes to disk or downloads batches.
     */
    static boolean prefetch(android.content.Context context, String title, String artist, String album,
                            String spotifyId, String youtubeId, long duration) {
        String targetKey = buildKey(title, artist, album, spotifyId, youtubeId, duration);
        if (targetKey.isEmpty() || hasFreshCache(targetKey)) return false;
        synchronized (PREFETCH_LOCK) {
            if (targetKey.equals(sPrefetchKey) && sPrefetcher != null && !sPrefetcher.closed && sPrefetcher.active) {
                return false;
            }
            if (sPrefetcher == null || sPrefetcher.closed) {
                sPrefetcher = new LiveLyrics(context, (data, status) -> {});
            }
            sPrefetchKey = targetKey;
            sPrefetcher.setTrack(title == null ? "" : title, artist == null ? "" : artist,
                album == null ? "" : album, spotifyId == null ? "" : spotifyId,
                youtubeId == null ? "" : youtubeId, duration);
            sPrefetcher.start();
            return true;
        }
    }

    static void cancelPrefetch() {
        synchronized (PREFETCH_LOCK) {
            sPrefetchKey = "";
            if (sPrefetcher != null) sPrefetcher.stop();
        }
    }

    static void trimMemory(int level) {
        if (level >= 20 || level == 10 || level == 15) {
            synchronized (PREFETCH_LOCK) {
                sPrefetchKey = "";
                if (sPrefetcher != null) {
                    sPrefetcher.close();
                    sPrefetcher = null;
                }
            }
            long now = SystemClock.elapsedRealtime();
            synchronized (BLOCKED_UNTIL) {
                BLOCKED_UNTIL.entrySet().removeIf(e -> e.getValue() == null || e.getValue() <= now);
            }
        }
        if (level >= 40 || level == 15) {
            synchronized (CACHE) { CACHE.clear(); }
        }
    }
    private final Set<HttpURLConnection> activeConnections = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private Future<?> pending;
    private volatile long generation;
    private volatile boolean closed;
    private boolean active;
    private String key="", title="", artist="", album="", spotifyId="", youtubeId="";
    private long duration;

    LiveLyrics(Listener listener) { this(null,listener); }
    LiveLyrics(android.content.Context context,Listener listener) {
        this.context=context==null ? null : context.getApplicationContext(); this.listener=listener;
    }
    void refresh() {
        if(closed || isManual())return;cancel();synchronized(CACHE){CACHE.remove(key);}if(active && !key.isEmpty())request();
    }
    String trackKey(){return key;}
    boolean isManual(){
        synchronized(CACHE){
            Result value=CACHE.get(key);
            if(value!=null && value.expires>SystemClock.elapsedRealtime())return value.manual;
        }
        return !key.isEmpty() && LyricChoiceStore.getChoice(context,title,artist,duration)!=null;
    }
    long selectedId(){
        synchronized(CACHE){
            Result value=CACHE.get(key);
            if(value!=null && value.expires>SystemClock.elapsedRealtime())return value.manual ? value.selectedId : 0;
        }
        return key.isEmpty() ? 0 : LyricChoiceStore.get(context,title,artist,duration);
    }
    String selectedKey(){
        synchronized(CACHE){
            Result value=CACHE.get(key);
            if(value!=null && value.expires>SystemClock.elapsedRealtime())return value.manual ? value.selectedKey : "";
        }
        LyricChoiceStore.Choice c=key.isEmpty() ? null : LyricChoiceStore.getChoice(context,title,artist,duration);
        return c==null ? "" : c.key();
    }
    boolean choose(String requestedKey,Lyrics selected) {
        return choose(requestedKey,selected,0);
    }
    boolean choose(String requestedKey,Lyrics selected,long selectedId) {
        return choose(requestedKey,selected,selectedId>0 ? LyricChoiceStore.Choice.lrclib(selectedId) : null,selectedId);
    }
    boolean choose(String requestedKey,Lyrics selected,LyricChoiceStore.Choice choice) {
        return choose(requestedKey,selected,choice,choice!=null ? choice.id : 0L);
    }
    private boolean choose(String requestedKey,Lyrics selected,LyricChoiceStore.Choice choice,long selectedId) {
        if(closed || key.isEmpty() || !key.equals(requestedKey) || selected!=null && (selected.size()==0 || selectedId<0))return false;
        cancel();
        if(selected==null)LyricChoiceStore.clear(context,title,artist,duration);
        else if(choice!=null)LyricChoiceStore.putChoice(context,title,artist,duration,choice);
        String selKey=choice!=null ? choice.key() : (selectedId>0 ? "LRCLIB:"+selectedId : "");
        synchronized(CACHE){if(selected==null)CACHE.remove(key);else CACHE.put(key,new Result(selected,"ready",3600000,false,true,selectedId,selKey));}
        listener.onLyrics(selected,selected==null ? "loading" : "ready");if(active)request();return true;
    }
    JSONObject searchRecords(String url,long deadline)throws Exception {
        if(!url.startsWith("https://lrclib.net/api/search?") && !url.startsWith("https://lrclib.net/api/v2/search?")
            && !url.matches("https://lrclib\\.net/api/get/[1-9][0-9]{0,18}")
            && !url.startsWith("https://lrc.red/search.json?")
            && !url.matches("https://lrc\\.red/s/[A-Z]{2}[A-Z0-9]{3}[0-9]{7}\\.ttml")
            && !url.startsWith("https://lyrics.kugou.com/search?")
            && !url.matches("https://lyrics\\.kugou\\.com/download\\?fmt=lrc&charset=utf8&client=pc&ver=1&id=[0-9]{1,20}&accesskey=[A-Za-z0-9]{8,64}"))
            throw new IllegalArgumentException("Invalid lyric search URL");
        return readJson(url,generation,deadline);
    }
    void setTrack(String title,String artist,long duration) { setTrack(title,artist,"",duration); }
    void setTrack(String title, String artist, String album, long duration) {
        setTrack(title,artist,album,"",duration);
    }
    void setTrack(String title,String artist,String album,String spotifyId,long duration) {
        setTrack(title,artist,album,spotifyId,"",duration);
    }
    void setTrack(String title,String artist,String album,String spotifyId,String youtubeId,long duration) {
        if (!spotifyId.matches("[A-Za-z0-9]{22}")) spotifyId="";
        if(!YouTubeTrack.valid(youtubeId))youtubeId="";
        String next = buildKey(title, artist, album, spotifyId, youtubeId, duration);
        if (next.equals(key)) return;
        cancel(); key=next; this.title=title; this.artist=artist; this.album=album; this.spotifyId=spotifyId; this.duration=duration;
        this.youtubeId=youtubeId;
        listener.onLyrics(null, key.isEmpty() ? "waiting" : "loading");
        if (active && !key.isEmpty()) request();
    }
    void start() { if (closed || active) return; active=true; if (!key.isEmpty()) request(); }
    void stop() { active=false; cancel(); }
    void close() { if (closed) return; closed=true; stop(); worker.shutdownNow(); pool.shutdownNow(); }
    private void cancel() {
        generation++;
        if (pending != null) { pending.cancel(true); pending=null; }
        disconnectAll();
    }
    private void disconnectAll() {
        for (HttpURLConnection conn : activeConnections) {
            try { conn.disconnect(); } catch (Exception ignored) {}
        }
        activeConnections.clear();
    }
    private void request() {
        Result cached;
        synchronized (CACHE) { cached=CACHE.get(key); }
        boolean preview=cached!=null && cached.expires>SystemClock.elapsedRealtime();
        if(preview){listener.onLyrics(cached.lyrics,cached.status);if(cached.complete)return;}
        final Result manual=preview && cached.manual ? cached : null;
        final long token=generation; final String requestedKey=key, requestedTitle=title, requestedArtist=artist, requestedAlbum=album;
        final String requestedId=spotifyId,requestedVideo=youtubeId;
        final long requestedDuration=duration;
        if(!preview)listener.onLyrics(null, "loading");
        final Progress progress=new Progress(token,requestedKey,preview ? cached : null);
        pending=worker.submit(() -> {
            Result result;
            try {
                if(manual!=null) {
                    Lyrics converted=JapaneseRomaji.convert(manual.lyrics);
                    android.util.Log.i("LiveLyrics","Manual source="+converted.source()+" wordTiming="+converted.hasWords());
                    result=new Result(converted,"ready",3600000,true,true,manual.selectedId,manual.selectedKey);
                }else result=fetch(requestedTitle, requestedArtist, requestedAlbum, requestedId, requestedVideo, requestedDuration, token,progress);
            }
            catch (Exception error) {
                if (!closed && generation == token) android.util.Log.w("LiveLyrics", "Lookup failed", error);
                Result currentBest=progress.snapshot();
                result=manual!=null ? new Result(manual.lyrics,"ready",3600000,true,true,manual.selectedId,manual.selectedKey)
                    : currentBest==null ? new Result(null, "unavailable", 60000) : new Result(currentBest.lyrics,"ready",60000,false);
            }
            final Result response=result;
            if (response != null && response.complete && response.lyrics != null) {
                synchronized (CACHE) {
                    Result existing = CACHE.get(requestedKey);
                    if (existing == null || !existing.complete || existing.expires <= SystemClock.elapsedRealtime()) {
                        CACHE.put(requestedKey, response);
                    }
                }
            }
            main.post(() -> {
                if (closed || !active || generation != token || !key.equals(requestedKey)) return;
                synchronized (CACHE) { CACHE.put(requestedKey, response); }
                pending=null; listener.onLyrics(response.lyrics, response.status);
            });
        });
    }
    private final class Progress {
        final long token,started=SystemClock.elapsedRealtime();final String requestedKey;Result best;boolean reported;
        Progress(long token,String requestedKey,Result cached){this.token=token;this.requestedKey=requestedKey;best=cached;}
        synchronized Result snapshot() { return best; }
        synchronized boolean hasWords() { return best != null && best.lyrics != null && best.lyrics.hasWords(); }
        synchronized boolean hasSynced() { return best != null && best.lyrics != null && best.lyrics.isSynced(); }
        synchronized int bestQuality() { return best != null && best.lyrics != null ? best.lyrics.quality() : 0; }
        synchronized void offer(Result candidate){
            if(candidate==null || candidate.lyrics==null || closed || generation!=token || Thread.currentThread().isInterrupted())return;
            Lyrics chosen=AggregateLyrics.best(best==null ? null : best.lyrics,candidate.lyrics);
            if(best!=null && chosen==best.lyrics)return;
            best=new Result(chosen,"ready",120000,false,candidate.manual,candidate.selectedId,candidate.selectedKey);final Result stage=best;
            main.post(()->{
                if(closed || !active || generation!=token || !key.equals(requestedKey))return;
                synchronized(CACHE){CACHE.put(requestedKey,stage);}
                if(!reported){reported=true;android.util.Log.i("LiveLyrics","First lyrics source="+stage.lyrics.source()+" elapsedMs="+(SystemClock.elapsedRealtime()-started));}
                listener.onLyrics(stage.lyrics,"ready");
            });
        }
    }
    private static final class WaveOutcome {
        final Result result;
        final boolean instrumental;
        final boolean errored;
        final boolean fastWave;
        final boolean wordCapable;
        WaveOutcome(Result result, boolean instrumental, boolean errored, boolean fastWave, boolean wordCapable) {
            this.result = result; this.instrumental = instrumental; this.errored = errored;
            this.fastWave = fastWave; this.wordCapable = wordCapable;
        }
        static WaveOutcome hit(Lyrics lyrics, boolean fastWave, boolean wordCapable) {
            return lyrics != null && lyrics.size() > 0
                ? new WaveOutcome(new Result(lyrics, "ready", lyrics.isSynced() ? 3600000 : 600000), false, false, fastWave, wordCapable)
                : new WaveOutcome(null, false, false, fastWave, wordCapable);
        }
        static WaveOutcome miss(boolean instrumental, boolean errored, boolean fastWave, boolean wordCapable) {
            return new WaveOutcome(null, instrumental, errored, fastWave, wordCapable);
        }
    }
    private Result fetch(String title,String artist,String album,String spotifyId,String youtubeId,long duration,long token,Progress progress) throws Exception {
        String access=LyricsAccess.token(context);
        long deadline=SystemClock.elapsedRealtime()+(access.isEmpty() ? 15000 : 28000);
        LyricChoiceStore.Choice savedChoice=LyricChoiceStore.getChoice(context,title,artist,duration);
        if(savedChoice!=null)try {
            long restoreDl=Math.min(deadline,SystemClock.elapsedRealtime()+4000L);
            Lyrics restored=null;
            if("LrcRed".equals(savedChoice.provider)) {
                String docUrl=ExtraLyricSources.lrcRedDocUrl(savedChoice.extId);
                if(!docUrl.isEmpty()) {
                    JSONObject doc=readJson(docUrl,token,restoreDl);
                    if(doc!=null) restored=ProviderLyrics.ttml(doc.getString("ttml"),duration,true).fromSource("LrcRed");
                }
            } else if("KuGou".equals(savedChoice.provider)) {
                String dlUrl=ExtraLyricSources.kugouDownloadUrl(savedChoice.extId,savedChoice.extKey);
                if(!dlUrl.isEmpty()) {
                    JSONObject dl=readJson(dlUrl,token,restoreDl);
                    restored=ExtraLyricSources.parseKugouDownload(dl,duration+15000L);
                }
            } else if(savedChoice.id>0) {
                JSONObject savedRecord=readJson("https://lrclib.net/api/get/"+savedChoice.id,token,restoreDl);
                restored=LyricCandidates.selectedById(savedRecord,savedChoice.id,title,artist,duration);
            }
            if(restored!=null && restored.size()>0) {
                progress.offer(new Result(restored,"ready",120000,false,true,savedChoice.id,savedChoice.key()));
                Lyrics converted=restored;
                try { converted=JapaneseRomaji.convert(restored); }
                catch(InterruptedIOException cancelled){throw cancelled;}
                catch(RuntimeException ignored){}
                if(closed || generation!=token)throw new InterruptedIOException("Cancelled saved lyrics");
                android.util.Log.i("LiveLyrics","Restored saved manual choice key="+savedChoice.key()+" wordTiming="+converted.hasWords());
                return new Result(converted,"ready",3600000,true,true,savedChoice.id,savedChoice.key());
            }
        }catch(InterruptedIOException cancelled){throw cancelled;}
        catch(Exception unavailable){android.util.Log.i("LiveLyrics","Saved manual choice unavailable; using auto chain");}

        if(!spotifyId.isEmpty() && !SpotifyAccess.authorization().isEmpty())try {
            JSONObject response=readJson(SpotifyLyrics.url(spotifyId),token,deadline);
            if(response!=null){
                Lyrics parsed=SpotifyLyrics.parse(response,duration);
                if(parsed.size()>0) progress.offer(new Result(parsed,"ready",600000));
            }
        }catch(java.net.SocketTimeoutException unavailable){android.util.Log.i("LiveLyrics","Spotify lyrics timed out; using fallback");}
        catch(InterruptedIOException cancelled){throw cancelled;}
        catch(Exception unavailable){android.util.Log.i("LiveLyrics","Spotify lyrics unavailable; using fallback");}

        String resolvedVideo = youtubeId;
        String resolvedIsrc = ExtraLyricSources.cachedIsrc(youtubeId);
        if (!access.isEmpty() && !progress.hasWords()) try {
            String[] reference = YouTubeTrack.valid(youtubeId) ? new String[]{youtubeId, resolvedIsrc} : null;
            boolean exact = reference != null && !reference[1].isEmpty();
            String expectedIsrc = resolvedIsrc;
            if (reference == null && !spotifyId.isEmpty()) {
                try {
                    JSONObject mapping = readJson("https://link.binimum.org/api/v1/resolve/spotify/" + spotifyId, token, deadline - 12000);
                    expectedIsrc = AggregateLyrics.isrc(mapping, spotifyId);
                    reference = AggregateLyrics.reference(mapping, spotifyId);
                    exact = reference != null && !reference[1].isEmpty();
                } catch (InterruptedIOException cancelled) { throw cancelled; }
                catch (Exception ignored) {}
            }
            if (reference != null) {
                resolvedVideo = reference[0];
                if (!reference[1].isEmpty()) { resolvedIsrc = reference[1]; ExtraLyricSources.rememberIsrc(resolvedVideo, resolvedIsrc); }
                Result aggregate = AggregateLyrics.parse(
                    readAggregate(AggregateLyrics.form(title, artist, album, duration, reference[0], reference[1], access), token, deadline - 10000),
                    title, artist, duration, exact);
                progress.offer(aggregate);
            }
        } catch (InterruptedIOException cancelled) { throw cancelled; }
        catch (Exception unavailable) { android.util.Log.i("LiveLyrics", "Aggregate source unavailable; using parallel public chain"); }

        // Parallel Wave 1: query all primary keyless public sources concurrently (BitChord parity).
        final String baseSearchTitle = LyricMatch.searchTitle(title, artist);
        final String searchTitle = baseSearchTitle.contains(" - ") || baseSearchTitle.contains(" / ")
            ? new LyricMatch().dedupeBilingualTitle(baseSearchTitle) : baseSearchTitle;
        final String primaryArtist = LyricMatch.primaryArtist(artist);
        final String videoForWave = resolvedVideo;
        final String isrcForWave = resolvedIsrc;
        final boolean identified = spotifyId.matches("[A-Za-z0-9]{22}");
        boolean sawInstrumental = false;
        boolean sawUnavailable = false;

        if (!progress.hasWords()) {
            final long raceBudget = Math.min(deadline, SystemClock.elapsedRealtime() + 6000L);
            List<Callable<WaveOutcome>> primaryTasks = new ArrayList<>(5);

            // 1. LRCLIB (fast exact + cleaned search fallback)
            primaryTasks.add(() -> {
                boolean err = false;
                boolean instr = false;
                Lyrics bestLocal = null;
                try {
                    JSONObject exactJson = readJson(LyricSources.lrcUrl(title, artist, duration), token, Math.min(raceBudget, SystemClock.elapsedRealtime() + 2600L));
                    if (exactJson != null) {
                        Result r = LiveLyrics.parse(exactJson, title, artist, duration);
                        if ("instrumental".equals(r.status)) instr = true;
                        if (r.lyrics != null) {
                            bestLocal = r.lyrics;
                            progress.offer(r);
                            if (r.lyrics.isSynced()) return WaveOutcome.hit(r.lyrics, true, false);
                        }
                    }
                } catch (InterruptedIOException cancelled) { throw cancelled; }
                catch (Exception e) { err = true; }
                try {
                    String searchUrl = "https://lrclib.net/api/search?track_name=" + URLEncoder.encode(searchTitle, "UTF-8");
                    JSONObject listJson = readJson(searchUrl, token, Math.min(raceBudget, SystemClock.elapsedRealtime() + 2600L));
                    if (listJson != null) {
                        Result r = LyricSources.quickSearch(listJson, title, artist, duration);
                        if (r.lyrics != null) {
                            bestLocal = AggregateLyrics.best(bestLocal, r.lyrics);
                            progress.offer(r);
                        }
                    }
                } catch (InterruptedIOException cancelled) { throw cancelled; }
                catch (Exception e) { err = true; }
                return bestLocal != null ? WaveOutcome.hit(bestLocal, true, false) : WaveOutcome.miss(instr, err, true, false);
            });

            // 2. Better Lyrics TTML (keyless boidu.dev mirror -> api.betterlyrics.org)
            primaryTasks.add(() -> {
                boolean err = false;
                try {
                    JSONObject boidu = readJson(ExtraLyricSources.boiduTtmlUrl(searchTitle, primaryArtist, album, duration), token, Math.min(raceBudget, SystemClock.elapsedRealtime() + 2600L));
                    Lyrics parsed = ExtraLyricSources.parseBetterLyricsTtml(boidu, duration);
                    if (parsed != null) return WaveOutcome.hit(parsed, true, true);
                } catch (InterruptedIOException cancelled) { throw cancelled; }
                catch (Exception e) { err = true; }
                try {
                    String orgUrl = "https://api.betterlyrics.org/getLyrics?s=" + URLEncoder.encode(title, "UTF-8")
                        + "&a=" + URLEncoder.encode(artist, "UTF-8") + "&al=" + URLEncoder.encode(album, "UTF-8") + "&d=" + (duration / 1000L);
                    JSONObject orgJson = readJson(orgUrl, token, Math.min(raceBudget, SystemClock.elapsedRealtime() + 2000L));
                    Lyrics parsed = ExtraLyricSources.parseBetterLyricsTtml(orgJson, duration);
                    if (parsed != null) return WaveOutcome.hit(parsed, true, true);
                } catch (InterruptedIOException cancelled) { throw cancelled; }
                catch (Exception e) { err = true; }
                return WaveOutcome.miss(false, err, true, true);
            });

            // 3. BiniLyrics / LrcRed (cached ISRC -> lrc.red/api/v1 -> lrc.red/search.json)
            primaryTasks.add(() -> {
                boolean err = false;
                LyricMatch match = new LyricMatch();
                if (!isrcForWave.isEmpty()) {
                    try {
                        String docUrl = ExtraLyricSources.lrcRedDocUrl(isrcForWave);
                        JSONObject doc = readJson(docUrl, token, Math.min(raceBudget, SystemClock.elapsedRealtime() + 2400L));
                        if (doc != null) {
                            Lyrics parsed = ProviderLyrics.ttml(doc.getString("ttml"), duration, true).fromSource("LrcRed");
                            if (parsed.size() > 0) return WaveOutcome.hit(parsed.withQuality(LyricMatch.computeQuality(parsed, duration, 0L, true)), true, true);
                        }
                    } catch (InterruptedIOException cancelled) { throw cancelled; }
                    catch (Exception e) { err = true; }
                }
                try {
                    String biniUrl = "https://lrc.red/api/v1?track=" + URLEncoder.encode(searchTitle, "UTF-8")
                        + "&artist=" + URLEncoder.encode(primaryArtist, "UTF-8")
                        + (album.isEmpty() ? "" : "&album=" + URLEncoder.encode(album, "UTF-8"))
                        + "&duration=" + (duration / 1000L);
                    JSONObject biniJson = readJson(biniUrl, token, Math.min(raceBudget, SystemClock.elapsedRealtime() + 2400L));
                    if (biniJson != null) {
                        String[] foundIsrc = {""};
                        Lyrics parsed = LyricSources.biniLookup(biniJson, title, artist, album, duration,
                            url -> readJson(url, token, Math.min(raceBudget, SystemClock.elapsedRealtime() + 2400L)), match, foundIsrc);
                        if (!foundIsrc[0].isEmpty()) ExtraLyricSources.rememberIsrc(videoForWave, foundIsrc[0]);
                        if (parsed != null && parsed.size() > 0) return WaveOutcome.hit(parsed, true, true);
                    }
                } catch (InterruptedIOException cancelled) { throw cancelled; }
                catch (Exception e) { err = true; }
                try {
                    JSONObject searchJson = readJson(ExtraLyricSources.lrcRedSearchUrl(searchTitle, primaryArtist), token, Math.min(raceBudget, SystemClock.elapsedRealtime() + 2400L));
                    String isrc = ExtraLyricSources.parseLrcRedSearch(searchJson, title, artist, duration, match);
                    if (!isrc.isEmpty() && !isrc.equals(isrcForWave)) {
                        ExtraLyricSources.rememberIsrc(videoForWave, isrc);
                        JSONObject doc = readJson(ExtraLyricSources.lrcRedDocUrl(isrc), token, Math.min(raceBudget, SystemClock.elapsedRealtime() + 2400L));
                        if (doc != null) {
                            Lyrics parsed = ProviderLyrics.ttml(doc.getString("ttml"), duration, true).fromSource("LrcRed");
                            if (parsed.size() > 0) return WaveOutcome.hit(parsed.withQuality(LyricMatch.computeQuality(parsed, duration, 0L, true)), true, true);
                        }
                    }
                } catch (InterruptedIOException cancelled) { throw cancelled; }
                catch (Exception e) { err = true; }
                return WaveOutcome.miss(false, err, true, true);
            });

            // 4. Unison (exact videoId / spotifyId + cleaned fallback)
            primaryTasks.add(() -> {
                boolean err = false;
                LyricMatch match = new LyricMatch();
                try {
                    String uUrl = AggregateLyrics.unisonUrl(title, artist, album, spotifyId, videoForWave, duration);
                    JSONObject info = readJson(uUrl, token, Math.min(raceBudget, SystemClock.elapsedRealtime() + 2400L));
                    if (info != null) {
                        Result r = LyricSources.unisonMatchedResult(info, title, artist, album, duration, match, identified);
                        if (r.lyrics != null) return WaveOutcome.hit(r.lyrics, true, true);
                    }
                } catch (InterruptedIOException cancelled) { throw cancelled; }
                catch (Exception e) { err = true; }
                if (!searchTitle.equals(title) || !primaryArtist.equals(artist)) {
                    try {
                        String uClean = AggregateLyrics.unisonUrl(searchTitle, primaryArtist, "", spotifyId, videoForWave, duration);
                        JSONObject info = readJson(uClean, token, Math.min(raceBudget, SystemClock.elapsedRealtime() + 2000L));
                        if (info != null) {
                            Result r = LyricSources.unisonMatchedResult(info, title, artist, "", duration, match, identified);
                            if (r.lyrics != null) return WaveOutcome.hit(r.lyrics, true, true);
                        }
                    } catch (InterruptedIOException cancelled) { throw cancelled; }
                    catch (Exception e) { err = true; }
                }
                return WaveOutcome.miss(false, err, true, true);
            });

            // 5. LyricsPlus (syllable-timed JSON; slow mirror does NOT block Wave 2 fallbacks)
            primaryTasks.add(() -> {
                boolean err = false;
                try {
                    String lpUrl = ExtraLyricSources.lyricsPlusUrl(searchTitle, primaryArtist, album, duration, isrcForWave);
                    JSONObject lpJson = readJson(lpUrl, token, Math.min(raceBudget, SystemClock.elapsedRealtime() + 3600L));
                    Lyrics parsed = ExtraLyricSources.parseLyricsPlus(lpJson, title, artist, duration, new LyricMatch());
                    if (parsed != null && parsed.size() > 0) return WaveOutcome.hit(parsed, false, true);
                } catch (InterruptedIOException cancelled) { throw cancelled; }
                catch (Exception e) { err = true; }
                return WaveOutcome.miss(false, err, false, true);
            });

            // Fallback tasks: launched immediately once the 4 fast sources miss (or after 1.4s without synced lyrics),
            // without waiting for a slow LyricsPlus timeout!
            List<Callable<WaveOutcome>> fallbackTasks = new ArrayList<>(3);

            // F1. KuGou Direct public search + download
            fallbackTasks.add(() -> {
                boolean err = false;
                try {
                    JSONObject search = readJson(ExtraLyricSources.kugouSearchUrl(searchTitle, primaryArtist, duration), token, Math.min(raceBudget, SystemClock.elapsedRealtime() + 2400L));
                    String[] picked = ExtraLyricSources.parseKugouCandidate(search, title, artist, duration, new LyricMatch());
                    if (picked != null) {
                        String dlUrl = ExtraLyricSources.kugouDownloadUrl(picked[0], picked[1]);
                        if (!dlUrl.isEmpty()) {
                            JSONObject dl = readJson(dlUrl, token, Math.min(raceBudget, SystemClock.elapsedRealtime() + 2200L));
                            Lyrics parsed = ExtraLyricSources.parseKugouDownload(dl, duration);
                            if (parsed != null && parsed.size() > 0) return WaveOutcome.hit(parsed, false, false);
                        }
                    }
                } catch (InterruptedIOException cancelled) { throw cancelled; }
                catch (Exception e) { err = true; }
                return WaveOutcome.miss(false, err, false, false);
            });

            // F2. LRCLIB romaji title fallback (only if Japanese kana title)
            final String roman = new LyricMatch().reading(searchTitle);
            if (!roman.equals(searchTitle) && !roman.isEmpty()) {
                fallbackTasks.add(() -> {
                    boolean err = false;
                    try {
                        String romUrl = "https://lrclib.net/api/search?track_name=" + URLEncoder.encode(roman, "UTF-8");
                        JSONObject listJson = readJson(romUrl, token, Math.min(raceBudget, SystemClock.elapsedRealtime() + 2600L));
                        if (listJson != null) {
                            Result r = LyricSources.quickSearch(listJson, title, artist, duration);
                            if (r.lyrics != null) return WaveOutcome.hit(r.lyrics, false, false);
                        }
                    } catch (InterruptedIOException cancelled) { throw cancelled; }
                    catch (Exception e) { err = true; }
                    return WaveOutcome.miss(false, err, false, false);
                });
            }

            // F3. YouTube Music official Lyrics tab (keyless, strictly keyed on validated youtubeId)
            if (YouTubeTrack.valid(videoForWave)) {
                fallbackTasks.add(() -> {
                    boolean err = false;
                    try {
                        JSONObject next = postYouTubeJson("next", ExtraLyricSources.youtubeNextBody(videoForWave), token, Math.min(raceBudget, SystemClock.elapsedRealtime() + 2400L));
                        String[] ep = ExtraLyricSources.extractYouTubeLyricsEndpoint(next);
                        if (ep != null) {
                            JSONObject browse = postYouTubeJson("browse", ExtraLyricSources.youtubeBrowseBody(ep[0], ep[1]), token, Math.min(raceBudget, SystemClock.elapsedRealtime() + 2400L));
                            Lyrics plain = ExtraLyricSources.parseYouTubeMusicLyrics(browse);
                            if (plain != null && plain.size() > 0) return WaveOutcome.hit(plain, false, false);
                        }
                    } catch (InterruptedIOException cancelled) { throw cancelled; }
                    catch (Exception e) { err = true; }
                    return WaveOutcome.miss(false, err, false, false);
                });
            }

            WaveSummary summary = runAdaptiveRace(primaryTasks, fallbackTasks, 4, 4, progress, token, raceBudget);
            sawInstrumental |= summary.instrumental;
            sawUnavailable |= summary.errored;
        }

        if (closed || generation != token || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Cancelled lyrics lookup");
        }
        Result result = progress.snapshot();
        if (result == null || result.lyrics == null) {
            if (sawInstrumental) return new Result(null, "instrumental", 3600000);
            return new Result(null, sawUnavailable ? "unavailable" : "missing", sawUnavailable ? 60000 : 600000);
        }
        result = new Result(result.lyrics, result.status, result.lyrics.isSynced() ? 3600000 : (sawUnavailable ? 60000 : 600000));
        long started = SystemClock.elapsedRealtime();
        Lyrics converted;
        try { converted = JapaneseRomaji.convert(result.lyrics); }
        catch (InterruptedIOException cancelled) { throw cancelled; }
        catch (RuntimeException unavailable) {
            if (closed || generation != token || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled lyrics reading");
            android.util.Log.w("LiveLyrics", "Readings unavailable; original lyrics retained", unavailable);
            return new Result(result.lyrics, result.status, 60000);
        }
        if (closed || generation != token) throw new InterruptedIOException("Cancelled lyrics lookup");
        android.util.Log.i("LiveLyrics", "Source=" + converted.source() + " wordTiming=" + converted.hasWords()
            + " readingsMs=" + (SystemClock.elapsedRealtime() - started));
        return new Result(converted, result.status, Math.max(1000, result.expires - SystemClock.elapsedRealtime()));
    }

    private static final class WaveSummary {
        final boolean instrumental, errored;
        WaveSummary(boolean instrumental, boolean errored) { this.instrumental = instrumental; this.errored = errored; }
    }

    /**
     * Runs primary providers concurrently and launches fallback providers (KuGou / YouTube Music / Romaji)
     * as soon as the fast primary providers miss (or after 1.4s without synced lyrics), never stalling behind
     * a slow mirror such as LyricsPlus.
     */
    private WaveSummary runAdaptiveRace(List<Callable<WaveOutcome>> primaryTasks,
                                        List<Callable<WaveOutcome>> fallbackTasks,
                                        int fastCount, int wordCapableCount,
                                        Progress progress, long token, long raceDeadline) throws InterruptedIOException {
        CompletionService<WaveOutcome> ecs = new ExecutorCompletionService<>(pool);
        List<Future<WaveOutcome>> futures = new ArrayList<>(primaryTasks.size() + fallbackTasks.size());
        for (Callable<WaveOutcome> task : primaryTasks) futures.add(ecs.submit(task));
        boolean instrumental = false, errored = false, fallbackLaunched = fallbackTasks.isEmpty(), tightened = false;
        long startedAt = SystemClock.elapsedRealtime();
        long fallbackTriggerAt = startedAt + 900L;
        long activeDeadline = raceDeadline;
        int fastRemaining = fastCount;
        int wordRemaining = wordCapableCount;
        int completed = 0;
        try {
            while (completed < futures.size() || !fallbackLaunched) {
                if (closed || generation != token || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Cancelled parallel lyrics race");
                }
                long now = SystemClock.elapsedRealtime();
                if (!fallbackLaunched && !progress.hasSynced() && (fastRemaining <= 0 || now >= fallbackTriggerAt)) {
                    fallbackLaunched = true;
                    for (Callable<WaveOutcome> task : fallbackTasks) futures.add(ecs.submit(task));
                }
                if (completed >= futures.size()) break;
                long remaining = activeDeadline - now;
                if (remaining <= 0) break;
                long waitMs = (!fallbackLaunched && !progress.hasSynced())
                    ? Math.min(remaining, Math.max(1L, fallbackTriggerAt - now))
                    : remaining;
                Future<WaveOutcome> done;
                try {
                    done = ecs.poll(waitMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Cancelled parallel lyrics race");
                }
                if (done == null) {
                    if (!fallbackLaunched && !progress.hasSynced() && SystemClock.elapsedRealtime() >= fallbackTriggerAt) {
                        fallbackLaunched = true;
                        for (Callable<WaveOutcome> task : fallbackTasks) futures.add(ecs.submit(task));
                        continue;
                    }
                    break;
                }
                completed++;
                try {
                    WaveOutcome outcome = done.get();
                    if (outcome == null) continue;
                    if (outcome.fastWave) fastRemaining = Math.max(0, fastRemaining - 1);
                    if (outcome.wordCapable) wordRemaining = Math.max(0, wordRemaining - 1);
                    if (outcome.instrumental) instrumental = true;
                    if (outcome.errored) errored = true;
                    if (outcome.result != null && outcome.result.lyrics != null) {
                        progress.offer(outcome.result);
                        if (progress.hasWords()) {
                            fallbackLaunched = true;
                            if (wordRemaining <= 0 || progress.bestQuality() >= 550) break;
                            activeDeadline = Math.min(activeDeadline, SystemClock.elapsedRealtime() + 350L);
                        } else if (progress.hasSynced()) {
                            fallbackLaunched = true;
                            if (wordRemaining <= 0) break;
                            long elapsed = SystemClock.elapsedRealtime() - startedAt;
                            if (fastRemaining <= 0 && elapsed >= 2000L) break;
                            long upgradeWindow = fastRemaining <= 0 ? 400L : 1600L;
                            if (!tightened) {
                                tightened = true;
                                activeDeadline = Math.min(activeDeadline, SystemClock.elapsedRealtime() + upgradeWindow);
                            } else if (fastRemaining <= 0) {
                                activeDeadline = Math.min(activeDeadline, SystemClock.elapsedRealtime() + 400L);
                            }
                        }
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Cancelled parallel lyrics race");
                } catch (Exception taskError) {
                    errored = true;
                }
            }
        } finally {
            for (Future<WaveOutcome> f : futures) f.cancel(true);
        }
        return new WaveSummary(instrumental, errored);
    }

    private String readAggregate(String form,long token,long deadline) throws Exception {
        if(closed || generation!=token || Thread.currentThread().isInterrupted())throw new InterruptedIOException("Cancelled aggregate lookup");
        long now=SystemClock.elapsedRealtime();synchronized(BLOCKED_UNTIL){Long blocked=BLOCKED_UNTIL.get("lyrics.api.dacubeking.com");if(blocked!=null && now<blocked)throw new IOException("Aggregate source cooling down");}
        long remaining=deadline-now;if(remaining<=0)throw new java.net.SocketTimeoutException("Aggregate lookup deadline");
        HttpURLConnection http=(HttpURLConnection)new URL(LyricsAccess.ORIGIN+"/v2/lyrics").openConnection();
        activeConnections.add(http);
        http.setConnectTimeout((int)Math.min(3000,remaining));http.setReadTimeout((int)Math.min(4000,remaining));http.setInstanceFollowRedirects(false);
        http.setRequestMethod("POST");http.setDoOutput(true);http.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
        http.setRequestProperty("User-Agent",LyricsAccess.agent(context));http.setRequestProperty("Accept","text/event-stream");
        byte[] body=form.getBytes(StandardCharsets.UTF_8);http.setFixedLengthStreamingMode(body.length);
        try {
            if(closed || generation!=token)throw new InterruptedIOException("Cancelled aggregate lookup");
            try(java.io.OutputStream output=http.getOutputStream()){output.write(body);}
            int status=http.getResponseCode();
            if(status==401 || status==403){LyricsAccess.invalidate(context);throw new IOException("Lyrics access needs verification");}
            if(status==429 || status==503){
                long wait=retryDelay(http.getHeaderField("Retry-After"),System.currentTimeMillis());now=SystemClock.elapsedRealtime();
                synchronized(BLOCKED_UNTIL){BLOCKED_UNTIL.put("lyrics.api.dacubeking.com",wait>=Long.MAX_VALUE-now ? Long.MAX_VALUE : now+wait);}
            }
            android.util.Log.i("LiveLyrics","Aggregate HTTP="+status);
            if(status!=200)throw new IOException("Aggregate service status="+status);
            if(http.getContentLengthLong()>1048576)throw new IOException("Aggregate response size");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream input=http.getInputStream()) {
                byte[] buffer=new byte[4096];int count;
                try {
                    while((count=input.read(buffer))!=-1){
                        if(closed || generation!=token || Thread.currentThread().isInterrupted())throw new InterruptedIOException("Cancelled aggregate lookup");
                        if(bytes.size()+count>1048576)throw new IOException("Aggregate response bounds");
                        bytes.write(buffer,0,count);if(SystemClock.elapsedRealtime()>deadline)break;
                    }
                }catch(java.net.SocketTimeoutException partial){if(bytes.size()==0)throw partial;}
            }
            if(closed || generation!=token)throw new InterruptedIOException("Cancelled aggregate lookup");
            android.util.Log.i("LiveLyrics","Aggregate response bytes="+bytes.size());
            return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
        }finally{activeConnections.remove(http);http.disconnect();}
    }

    private JSONObject postYouTubeJson(String endpoint, String jsonBody, long token, long deadline) throws Exception {
        if (!"next".equals(endpoint) && !"browse".equals(endpoint)) throw new IllegalArgumentException("Invalid YouTube endpoint");
        if (closed || generation != token || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled YouTube lyrics lookup");
        String host = "music.youtube.com";
        long now = SystemClock.elapsedRealtime();
        synchronized (BLOCKED_UNTIL) {
            Long blocked = BLOCKED_UNTIL.get(host);
            if (blocked != null && now < blocked) throw new IOException("YouTube lyrics cooling down");
        }
        long remaining = deadline - now;
        if (remaining <= 0) throw new java.net.SocketTimeoutException("YouTube lyrics deadline");
        HttpURLConnection http = (HttpURLConnection) new URL("https://music.youtube.com/youtubei/v1/" + endpoint + "?prettyPrint=false").openConnection();
        activeConnections.add(http);
        http.setConnectTimeout((int) Math.min(2000L, remaining));
        http.setReadTimeout((int) Math.min(2500L, remaining));
        http.setInstanceFollowRedirects(false);
        http.setRequestMethod("POST");
        http.setDoOutput(true);
        http.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        http.setRequestProperty("Accept", "application/json");
        http.setRequestProperty("Origin", "https://music.youtube.com");
        http.setRequestProperty("Referer", "https://music.youtube.com/");
        http.setRequestProperty("User-Agent", USER_AGENT);
        byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
        http.setFixedLengthStreamingMode(payload.length);
        int maxBytes = "next".equals(endpoint) ? 524288 : 262144;
        try {
            if (closed || generation != token) throw new InterruptedIOException("Cancelled YouTube lyrics lookup");
            try (java.io.OutputStream out = http.getOutputStream()) { out.write(payload); }
            int status = http.getResponseCode();
            if (status == 429 || status == 503) {
                long wait = retryDelay(http.getHeaderField("Retry-After"), System.currentTimeMillis());
                now = SystemClock.elapsedRealtime();
                synchronized (BLOCKED_UNTIL) { BLOCKED_UNTIL.put(host, wait >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + wait); }
                throw new IOException("YouTube lyrics temporarily unavailable");
            }
            if (status == 400 || status == 401 || status == 403 || status == 404) return null;
            if (status != 200) throw new IOException("YouTube lyrics status=" + status);
            if (http.getContentLengthLong() > maxBytes) throw new IOException("YouTube lyrics response too large");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = http.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (closed || generation != token || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled YouTube lyrics lookup");
                    if (bytes.size() + count > maxBytes) throw new IOException("YouTube lyrics response limit");
                    if (SystemClock.elapsedRealtime() > deadline) throw new java.net.SocketTimeoutException("YouTube lyrics deadline");
                    bytes.write(buffer, 0, count);
                }
            }
            return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            activeConnections.remove(http);
            http.disconnect();
        }
    }

    private JSONObject readJson(String address,long token,long deadline) throws Exception {
        if (closed || generation!=token || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled lyrics lookup");
        URL url=new URL(address);
        if (!"https".equals(url.getProtocol())) throw new IllegalArgumentException("HTTPS required");
        String host=url.getHost();
        if (!allowedHost(host)) throw new IllegalArgumentException("Disallowed lyrics host");
        boolean lrc=host.equals("lrclib.net"),spotify=host.equals("spclient.wg.spotify.com");
        String spotifyAuthorization=spotify ? SpotifyAccess.authorization() : "";
        if(spotify && (spotifyAuthorization.isEmpty() || !SpotifyAccess.lyricRequest(address)))return null;
        boolean ttml=host.equals("lrc.red") && url.getPath().startsWith("/s/") || host.equals("lyrics-storage.binimum.org");
        long now=SystemClock.elapsedRealtime();
        synchronized (BLOCKED_UNTIL) {
            Long blocked=BLOCKED_UNTIL.get(host);
            if (blocked!=null && now<blocked) throw new IOException("Lyrics source cooling down");
        }
        long remaining=deadline-now;
        if (remaining<=0) throw new java.net.SocketTimeoutException("Lyrics lookup deadline");
        HttpURLConnection http=(HttpURLConnection)url.openConnection();
        activeConnections.add(http);
        http.setConnectTimeout((int)Math.min(lrc ? 2500L : 1800L,remaining));
        http.setReadTimeout((int)Math.min(lrc ? 3200L : 2600L,remaining));
        http.setInstanceFollowRedirects(false);
        http.setRequestProperty("User-Agent",spotify ? SpotifyAccess.agent() : USER_AGENT);
        if(spotify){http.setRequestProperty("Authorization",spotifyAuthorization);http.setRequestProperty("App-Platform","WebPlayer");}
        http.setRequestProperty("Accept",ttml ? "application/ttml+xml, application/xml, text/xml" : "application/json");
        try {
            if (closed || generation!=token) throw new InterruptedIOException("Cancelled lyrics lookup");
            int status=http.getResponseCode();
            if (status==429 || status==503) {
                long wait=retryDelay(http.getHeaderField("Retry-After"),System.currentTimeMillis());
                now=SystemClock.elapsedRealtime(); long blocked=wait>=Long.MAX_VALUE-now ? Long.MAX_VALUE : now+wait;
                synchronized (BLOCKED_UNTIL) { BLOCKED_UNTIL.put(host,blocked); }
                throw new IOException("Lyrics source temporarily unavailable");
            }
            // Public-cache misses requiring authentication are skipped, never retried with alternate credentials.
            if (status==401 || status==402 || status==403 || status==404 || status==204) {if(spotify && (status==401||status==403))SpotifyAccess.clear();return null;}
            if (status!=200) throw new IOException("Lyrics service status="+status);
            if (SystemClock.elapsedRealtime()>=deadline) throw new java.net.SocketTimeoutException("Lyrics source deadline");
            if (http.getContentLengthLong()>262144) throw new IOException("Lyrics response too large");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try (InputStream input=http.getInputStream()) {
                byte[] buffer=new byte[4096]; int count;
                while ((count=input.read(buffer))!=-1) {
                    if (closed || generation!=token || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled lyrics lookup");
                    if (bytes.size()+count>262144) throw new IOException("Lyrics response limit");
                    if (SystemClock.elapsedRealtime()>deadline) throw new java.net.SocketTimeoutException("Lyrics source deadline");
                    bytes.write(buffer,0,count);
                }
            }
            String text=new String(bytes.toByteArray(),StandardCharsets.UTF_8);
            if (ttml) return new JSONObject().put("ttml",text);
            return lrc && (url.getPath().equals("/api/search") || url.getPath().equals("/api/v2/search")) ? new JSONObject().put("results",new org.json.JSONArray(text)) : new JSONObject(text);
        } finally { activeConnections.remove(http); http.disconnect(); }
    }
    private static boolean allowedHost(String host) {
        return "lrclib.net".equals(host)
            || "api.betterlyrics.org".equals(host)
            || "lyrics-api.boidu.dev".equals(host)
            || "unison.betterlyrics.org".equals(host)
            || "lrc.red".equals(host)
            || "lyrics-api.binimum.org".equals(host)
            || "lyrics-storage.binimum.org".equals(host)
            || "lyricsplus.prjktla.my.id".equals(host)
            || "lyrics.kugou.com".equals(host)
            || "link.binimum.org".equals(host)
            || "spclient.wg.spotify.com".equals(host);
    }
    static Result parse(JSONObject info, String title, String artist, long duration) throws Exception {
        return parseCandidate(info, title, artist, duration, duration);
    }
    static Result parseCandidate(JSONObject info, String title, String artist, long expectedDuration, long trackDuration) throws Exception {
        double seconds=info.getDouble("duration");
        if (!Lyrics.sameTrack(title, info.getString("trackName")) || !Lyrics.sameTrack(artist, info.getString("artistName"))
                || Double.isNaN(seconds) || Double.isInfinite(seconds) || Math.abs(seconds*1000-expectedDuration) > 2000)
            return new Result(null, "missing", 600000);
        if (info.optBoolean("instrumental", false)) return new Result(null, "instrumental", 3600000);
        Lyrics parsed=null;
        if (info.optBoolean("hasWordSync",false) && !info.isNull("lyricsfile")) {
            try { parsed=TimedLyrics.parse(info.getString("lyricsfile"),title,artist); }
            catch (IllegalArgumentException invalidTiming) { /* Existing LRC remains the safe fallback. */ }
        }
        if (parsed==null) parsed=Lyrics.parse(info.isNull("syncedLyrics") ? "" : info.optString("syncedLyrics", ""));
        if (parsed.size()>0 && parsed.isSynced() && LyricMatch.isTruncatedForDuration(parsed, trackDuration)) {
            return new Result(null, "missing", 600000);
        }
        if (parsed.size()==0) parsed=Lyrics.plain(info.isNull("plainLyrics") ? "" : info.optString("plainLyrics",""));
        if (parsed.size()==0) return new Result(null, "missing", 3600000);
        Lyrics scored=parsed.withQuality(LyricMatch.computeQuality(parsed, trackDuration, Math.round(seconds*1000.0), false));
        return new Result(scored, "ready", 3600000);
    }
    static long retryDelay(String value, long wallNow) {
        long milliseconds=60000;
        try {
            long seconds=Long.parseLong(value.trim());
            milliseconds=seconds > Long.MAX_VALUE/1000 ? Long.MAX_VALUE : Math.max(0, seconds)*1000;
        } catch (Exception notSeconds) {
            try {
                java.text.SimpleDateFormat format=new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US);
                format.setLenient(false);
                milliseconds=Math.max(0, format.parse(value).getTime()-wallNow);
            } catch (Exception invalid) { /* Use the conservative default when the header is absent/invalid. */ }
        }
        // Wait at least Retry-After, adding jitter without overflowing a hostile header value.
        long jitter=(long)(Math.random()*1000);
        return milliseconds >= Long.MAX_VALUE-jitter ? Long.MAX_VALUE : milliseconds+jitter;
    }
}
