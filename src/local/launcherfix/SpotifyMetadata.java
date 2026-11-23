package local.launcherfix;

import android.graphics.Bitmap;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded local reads only while Dock or Music Player holds focus; shares warm direct MediaBrowser handoff. */
final class SpotifyMetadata {
    interface Listener { void onMetadata(Response response, boolean connected); }
    static final long HANDOFF_GRACE_MS = 750L;

    private static final ThreadPoolExecutor WORKER = createWorker();
    private static ThreadPoolExecutor createWorker() {
        ThreadPoolExecutor exec = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), task -> {
                Thread thread = new Thread(() -> {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    task.run();
                }, "spotify-metadata");
                thread.setDaemon(true);
                return thread;
            });
        exec.allowCoreThreadTimeOut(true);
        return exec;
    }

    /**
     * Ref-counted shared direct MediaBrowser hub with a 750ms grace handoff window:
     * keeps live BitChord/Mochy bindings warm across Dock <-> Music Player focus switches
     * while disconnecting cleanly once Home goes idle or backgrounded.
     */
    static final class DirectHub {
        private static volatile DirectHub instance;
        final MochyMediaBrowser direct;
        final GenericMediaBrowser generic;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final LinkedHashSet<Runnable> directHolders = new LinkedHashSet<>();
        private final LinkedHashSet<Runnable> genericHolders = new LinkedHashSet<>();
        private final Runnable delayedStopDirect;
        private final Runnable delayedStopGeneric;

        private DirectHub(Context appContext) {
            Runnable dispatch = this::notifyHolders;
            direct = new MochyMediaBrowser(appContext, dispatch);
            generic = new GenericMediaBrowser(appContext, dispatch);
            delayedStopDirect = () -> {
                if (directHolders.isEmpty()) direct.stop();
            };
            delayedStopGeneric = () -> {
                if (genericHolders.isEmpty()) generic.stop();
            };
        }

        static DirectHub obtain(Context context) {
            DirectHub current = instance;
            if (current != null) return current;
            synchronized (DirectHub.class) {
                if (instance == null) {
                    Context app = context.getApplicationContext();
                    instance = new DirectHub(app != null ? app : context);
                }
                return instance;
            }
        }

        static DirectHub peek() { return instance; }

        private void notifyHolders() {
            if (directHolders.isEmpty() && genericHolders.isEmpty()) return;
            ArrayList<Runnable> targets = new ArrayList<>(directHolders.size() + genericHolders.size());
            targets.addAll(directHolders);
            for (Runnable r : genericHolders) {
                if (!targets.contains(r)) targets.add(r);
            }
            for (int i = 0; i < targets.size(); i++) {
                targets.get(i).run();
            }
        }

        void hold(Runnable listener, boolean wantDirect, boolean wantGeneric) {
            if (wantDirect) {
                main.removeCallbacks(delayedStopDirect);
                directHolders.add(listener);
                direct.start();
            } else {
                directHolders.remove(listener);
                if (directHolders.isEmpty()) {
                    main.removeCallbacks(delayedStopDirect);
                    main.postDelayed(delayedStopDirect, HANDOFF_GRACE_MS);
                }
            }
            if (wantGeneric) {
                main.removeCallbacks(delayedStopGeneric);
                genericHolders.add(listener);
                generic.start();
            } else {
                genericHolders.remove(listener);
                if (genericHolders.isEmpty()) {
                    main.removeCallbacks(delayedStopGeneric);
                    main.postDelayed(delayedStopGeneric, HANDOFF_GRACE_MS);
                }
            }
        }

        void release(Runnable listener) {
            hold(listener, false, false);
        }

        void trimImmediate() {
            Runnable task = () -> {
                if (directHolders.isEmpty()) {
                    main.removeCallbacks(delayedStopDirect);
                    direct.stop();
                }
                if (genericHolders.isEmpty()) {
                    main.removeCallbacks(delayedStopGeneric);
                    generic.stop();
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) task.run();
            else main.post(task);
        }
    }

    /** Pure deterministic ref-counted handoff state machine for ART unit verification. */
    static final class HandoffPolicy {
        int holders;
        boolean connected;
        long disconnectAt = -1L;

        void acquire(long nowMs) {
            holders++;
            connected = true;
            disconnectAt = -1L;
        }

        void release(long nowMs) {
            if (holders > 0) holders--;
            if (holders == 0 && connected && disconnectAt < 0L) {
                disconnectAt = nowMs + HANDOFF_GRACE_MS;
            }
        }

        boolean tick(long nowMs) {
            if (holders == 0 && connected && disconnectAt >= 0L && nowMs >= disconnectAt) {
                connected = false;
                disconnectAt = -1L;
                return true;
            }
            return false;
        }

        boolean trimNow() {
            if (holders == 0 && connected) {
                connected = false;
                disconnectAt = -1L;
                return true;
            }
            return false;
        }
    }

    static void trimMemory(int level) {
        if (level >= 20 || level == 10 || level == 15) {
            MochyMediaBrowser.trimMemory();
            DirectHub hub = DirectHub.peek();
            if (hub != null) hub.trimImmediate();
        }
    }

    static boolean workerAllowsCoreTimeout() {
        return WORKER.allowsCoreThreadTimeOut();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final Runnable poll = this::read;
    private final Runnable onHubChanged;
    private volatile boolean running;
    private volatile boolean closed;
    private volatile boolean fastPoll;
    private volatile long generation;
    private boolean reading, readAgain;
    private final DirectHub hub;
    private final MochyMediaBrowser direct;
    private final GenericMediaBrowser generic;
    private String revision = "";
    private final long activeIntervalMs;
    private final int maxDecodeSide;
    private Bitmap artwork;
    private String preferred="";
    boolean automatic() { return preferred.isEmpty(); }
    void select(String id) {
        if (!id.isEmpty() && !MediaSources.validId(id)) throw new IllegalArgumentException("Invalid source");
        preferred=id; generation++; main.removeCallbacks(poll); if(running) read();
    }
    void setFastPoll(boolean enabled) {
        if (fastPoll == enabled) return;
        fastPoll = enabled;
        if (enabled && running && !closed && !reading) {
            main.removeCallbacks(poll);
            main.postDelayed(poll, 500L);
        }
    }
    void control(String id, int key, java.util.function.Consumer<Boolean> done) {
        if(closed || !running || !MediaSources.validId(id)) { done.accept(false); return; }
        final long token=generation;
        WORKER.execute(()->{
            boolean sent=false;
            if(!closed && running && token==generation)try {
                if(direct.owns(id)) {
                    sent=direct.control(id,key);
                } else if (generic.owns(id)) {
                    sent=generic.control(id,key);
                } else {
                IBinder bridge=SpotifyBridgeProvider.reader();
                if(bridge!=null) {
                    Parcel request=Parcel.obtain(),reply=Parcel.obtain();
                    try {
                        request.writeInterfaceToken("local.launcherbridge.SpotifyMetadata.v1");
                        request.writeString(id);request.writeInt(key);
                        if(bridge.transact(2,request,reply,0)){reply.readException();sent=reply.readInt()==1;}
                    } finally {request.recycle();reply.recycle();}
                }
                }
            }catch(Exception unavailable) {}
            final boolean accepted=sent;
            main.post(()->{if(!closed){done.accept(accepted);main.removeCallbacks(poll);if(running)read();}});
        });
    }

    SpotifyMetadata(Context context, Listener listener, long activeIntervalMs, int maxDecodeSide) {
        this.listener = listener;
        this.activeIntervalMs = Math.max(1000L, activeIntervalMs);
        this.maxDecodeSide = Math.max(32, Math.min(1024, maxDecodeSide));
        this.onHubChanged = () -> {
            main.removeCallbacks(poll);
            if(running && !closed) read();
        };
        hub = DirectHub.obtain(context);
        direct = hub.direct;
        generic = hub.generic;
    }
    void start() {
        if (closed || running) return;
        running = true; generation++; main.post(poll);
    }
    void stop() {
        running = false; generation++; main.removeCallbacks(poll);
        hub.release(onHubChanged);
    }
    void close() { if (closed) return; closed = true; stop(); artwork = null; }

    private void read() {
        if (!running || closed) return;
        if(reading) { readAgain=true; return; }
        reading=true; readAgain=false;
        final long token = generation;
        final String knownRevision = revision;
        final String requestedSource=preferred;
        WORKER.execute(() -> {
            Response response=new Response(); boolean connected=false;
            try {
                IBinder bridge = SpotifyBridgeProvider.reader();
                if (bridge == null) throw new IOException("Media bridge disconnected");
                Parcel request = Parcel.obtain(), reply = Parcel.obtain();
                try {
                    request.writeInterfaceToken("local.launcherbridge.SpotifyMetadata.v1");
                    request.writeString(knownRevision);request.writeString(requestedSource);
                    if (!bridge.transact(1, request, reply, 0)) throw new IOException("Unsupported media bridge");
                    reply.readException();
                    int position = reply.dataPosition(), size = reply.readInt();
                    if (size < 12 || size > 548876) throw new IOException("Invalid media packet size");
                    reply.setDataPosition(position);
                    byte[] packet = reply.createByteArray();
                    response = parse(new DataInputStream(new ByteArrayInputStream(packet)), knownRevision, maxDecodeSide);
                } finally { request.recycle(); reply.recycle(); }
                connected = true;
            } catch (Exception unavailable) { response=new Response(); }
            boolean needsDirect=!connected || response.version==2 && response.sources.length==0;
            boolean needsGeneric=false;
            if(needsDirect) {
                try {
                    Response directFallback=null, genericFallback=null;
                    needsGeneric = requestedSource.isEmpty() || generic.owns(requestedSource);
                    if (requestedSource.isEmpty() || direct.owns(requestedSource))
                        directFallback=direct.read(knownRevision,maxDecodeSide);
                    if (needsGeneric) genericFallback=generic.read(knownRevision,maxDecodeSide,requestedSource);
                    if (genericFallback != null && (directFallback == null
                            || genericFallback.playback.playing() && !directFallback.playback.playing())) {
                        response=genericFallback; connected=true;
                    } else if (directFallback != null) {
                        response=directFallback; connected=true;
                    }
                } catch(Exception unavailable) { needsGeneric=true; }
                if (needsGeneric && !requestedSource.isEmpty() && !generic.owns(requestedSource)) {
                    // The selected direct source is not available; let the generic browser reconnect.
                    response=new Response();
                }
            }
            final Response value=response;final boolean available=connected, fallbackNeeded=needsDirect, genericNeeded=needsGeneric;
            main.post(() -> {
                reading=false;
                if (!running || closed) return;
                if(token!=generation) { main.removeCallbacks(poll); main.post(poll); return; }
                if(fallbackNeeded) {
                    hub.hold(onHubChanged, true, genericNeeded);
                } else {
                    hub.release(onHubChanged);
                }
                // Rebinding after a popup closes is pending, not an empty music session.
                if (fallbackNeeded && (direct.isConnecting() || (genericNeeded && generic.isConnecting()) || readAgain)) {
                    if (readAgain) main.post(poll);
                    return;
                }
                revision = value.revision;
                if (!value.unchanged) artwork = value.art;
                value.art=artwork;
                if(available && value.version==2 && !preferred.isEmpty() && (!fallbackNeeded || value.sources.length>0)) {
                    boolean found=false;for(Source source:value.sources)if(source.id.equals(preferred))found=true;
                    if(!found)preferred="";
                }
                listener.onMetadata(value, available);
                if (running && !closed) {
                    main.removeCallbacks(poll);
                    long nextDelay = readAgain ? 0L : !available ? 5000L
                        : (fastPoll && !fallbackNeeded ? 500L : activeIntervalMs);
                    main.postDelayed(poll, nextDelay);
                }
            });
        });
    }

    static final class Source {
        final String id, packageName, label;final int state;
        Source(String id,String packageName,String label,int state){this.id=id;this.packageName=packageName;this.label=label;this.state=state;}
    }
    static final class Response {
        String title="", artist="", revision="";
        int version;long actions;
        String source="",sessionId="";
        Source[] sources=new Source[0];
        String sourceLabel(){for(Source item:sources)if(item.id.equals(sessionId))return item.label;return source;}

        String album = "";
        String spotifyId = "";
        String youtubeId = "";
        String liveLyric = "";
        Bitmap art;
        boolean unchanged;
        MusicPlayback playback = MusicPlayback.UNKNOWN;
    }
    static Response parse(DataInputStream input, String knownRevision) throws Exception {
        return parse(input, knownRevision, 1024);
    }
    static Response parse(DataInputStream input, String knownRevision, int targetMaxSide) throws Exception {
        String title, artist, nextRevision;
        Bitmap received = null; boolean retainArt = false;
        if (input.readInt() != 0x4c4d4231) throw new IOException("Invalid media bridge version");
        int length = input.readInt();
        if (length < 2 || length > 24576) throw new IOException("Invalid metadata length");
        byte[] json = new byte[length]; input.readFully(json);
        JSONObject info = new JSONObject(new String(json, StandardCharsets.UTF_8));
        int version=info.getInt("version");String source=info.getString("source");
        if (version!=1 && version!=2 || version==1 && !"com.spotify.tv.android".equals(source)
            || version==2 && !source.isEmpty() && !MediaSources.validPackage(source)) throw new IOException("Invalid metadata source");
        title = info.getString("title"); artist = info.getString("artist"); nextRevision = info.getString("revision");
        if (title.length() > 512 || artist.length() > 512 || !nextRevision.matches("[a-f0-9]{64}"))
            throw new IOException("Invalid metadata fields");
        int bytes = input.readInt();
        if (bytes < -1 || bytes > 524288) throw new IOException("Invalid artwork size");
        if (bytes == -1) {
            if (!knownRevision.equals(nextRevision)) throw new IOException("Missing changed artwork");
            retainArt = true;
        } else if (bytes > 0) {
            byte[] encoded = new byte[bytes]; input.readFully(encoded);
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(encoded, 0, bytes, bounds);
            int maxArt = version == 1 ? 320 : 1024;
            if (bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth > maxArt || bounds.outHeight > maxArt)
                throw new IOException("Invalid artwork dimensions");
            int cap = Math.max(32, Math.min(maxArt, targetMaxSide));
            int sample = 1;
            while ((bounds.outWidth / (sample * 2)) >= cap && (bounds.outHeight / (sample * 2)) >= cap) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded = BitmapFactory.decodeByteArray(encoded, 0, bytes, opts);
            if (decoded == null) throw new IOException("Invalid artwork");
            int dw = decoded.getWidth(), dh = decoded.getHeight();
            if (dw > cap || dh > cap) {
                float scale = Math.min((float) cap / dw, (float) cap / dh);
                int sw = Math.max(1, Math.round(dw * scale));
                int sh = Math.max(1, Math.round(dh * scale));
                Bitmap scaled = Bitmap.createScaledBitmap(decoded, sw, sh, true);
                if (scaled != decoded) decoded.recycle();
                FastPixelOps.sharpen(scaled, 0.22f);
                received = scaled;
            } else {
                received = decoded;
            }
        }

        Response result = new Response();
        result.version=version;result.source=source;
        if(version==2) {
            result.sessionId=info.getString("sessionId");
            if(!result.sessionId.isEmpty() && !MediaSources.validId(result.sessionId)) throw new IOException("Invalid session");
            org.json.JSONArray choices=info.getJSONArray("sources");
            if(choices.length()>16)throw new IOException("Too many sources");
            result.sources=new Source[choices.length()];java.util.HashSet<String> ids=new java.util.HashSet<>();
            boolean selected=false;
            for(int i=0;i<choices.length();i++) {
                JSONObject item=choices.getJSONObject(i);
                String id=item.getString("id"),pkg=item.getString("package"),label=item.getString("label");int state=item.getInt("state");
                if(!MediaSources.validId(id) || !ids.add(id) || !MediaSources.validPackage(pkg) || label.length()>80 || state<0 || state>11)
                    throw new IOException("Invalid source choice");
                result.sources[i]=new Source(id,pkg,label,state);
                if(id.equals(result.sessionId)){if(!pkg.equals(source))throw new IOException("Mismatched source");selected=true;}
            }
            if(selected!=!result.sessionId.isEmpty() || result.sessionId.isEmpty()!=source.isEmpty() || !selected && choices.length()!=0)
                throw new IOException("Missing selected source");
        }
        result.title = title; result.artist = artist; result.revision = nextRevision;
        if (info.has("album")) {
            if (!(info.opt("album") instanceof String)) throw new IOException("Invalid album type");
            result.album=info.getString("album");
            if (result.album.length()>512) throw new IOException("Invalid album field");
        }
        result.art = received; result.unchanged = retainArt;
        if (info.has("spotifyId")) {
            Object id=info.opt("spotifyId");
            if (!(id instanceof String) || !((String)id).matches("(?:[A-Za-z0-9]{22})?")) throw new IOException("Invalid Spotify track id");
            result.spotifyId=(String)id;
            if(!result.spotifyId.isEmpty() && !"com.spotify.tv.android".equals(source))throw new IOException("Wrong Spotify source");
        }
        if(info.has("youtubeId")) {
            Object id=info.opt("youtubeId");
            if(!(id instanceof String) || !((String)id).isEmpty() && (!YouTubeTrack.valid((String)id) || !YouTubeTrack.source(source)))
                throw new IOException("Invalid YouTube track id");
            result.youtubeId=(String)id;
        }
        if(info.has("liveLyric")) {
            Object line=info.opt("liveLyric");
            if(!(line instanceof String) || ((String)line).length()>512)
                throw new IOException("Invalid live lyric");
            String raw=(String)line;
            if(!raw.isEmpty()) {
                String clean=MediaSources.sanitizeLiveLyric(raw);
                if(clean.isEmpty()) throw new IOException("Invalid live lyric");
                result.liveLyric=clean;
            }
        }
        JSONObject playback = info.optJSONObject("playback");
        if (playback != null) {
            long duration=playback.getLong("duration"), position=playback.getLong("position"), updated=playback.getLong("updated");
            double speed=playback.getDouble("speed"); int state=playback.getInt("state");
            if (duration < 0 || position < -1 || updated < 0 || Double.isNaN(speed) || Double.isInfinite(speed)
                    || Math.abs(speed) > 16 || state < 0 || state > 11) throw new IOException("Invalid playback clock");
            if(version==2){result.actions=playback.getLong("actions");if(result.actions<0 || result.sessionId.isEmpty())throw new IOException("Invalid controls");}
            result.playback = new MusicPlayback(true, duration, position, updated, (float)speed, state);
        }
        if(input.available()!=0)throw new IOException("Trailing metadata");
        return result;
    }
}
