package local.launcherbridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.media.session.MediaSession;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.Looper;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import local.launcherfix.MediaSources;
import local.launcherfix.YouTubeTrack;

/** Shell-owned local bridge. Only the launcher UID can read or control a live session. */
public final class SpotifyMediaBridge {
    private static final int MAGIC = 0x4c4d4231;
    private static final String DESCRIPTOR = "local.launcherbridge.SpotifyMetadata.v1";
    private static final String LAUNCHER = "com.gathin.tvhome.liquid", SPOTIFY = "com.spotify.tv.android";
    private static String cachedRevision = "";
    private static byte[] cachedArt = new byte[0];
    private static volatile int launcherUid;
    private static IBinder owner;
    private static Context shell;
    private static final Map<MediaSession.Token,String> ids = new LinkedHashMap<>();

    private static Context shellContext() throws Exception {
        Class<?> type = Class.forName("android.app.ActivityThread");
        Object thread = type.getDeclaredMethod("systemMain").invoke(null);
        Context system = (Context) type.getDeclaredMethod("getSystemContext").invoke(thread);
        return system.createPackageContext("com.android.shell", 0);
    }

    private static Bundle publish(Bundle connection) throws Exception {
        // app_process has no registered application thread. Use Android's own shell-content path.
        Class<?> type = Class.forName("android.app.ActivityManager");
        java.lang.reflect.Method service = type.getDeclaredMethod("getService"); service.setAccessible(true);
        Object manager = service.invoke(null);
        String authority = LAUNCHER + ".spotifybridge";
        IBinder token = new Binder();
        java.lang.reflect.Method acquire = manager.getClass().getMethod("getContentProviderExternal",
            String.class, int.class, IBinder.class, String.class); acquire.setAccessible(true);
        Object holder = acquire.invoke(manager, authority, 0, token, "liquid_spotify_bridge");
        if (holder == null) throw new IllegalStateException("Launcher provider unavailable");
        try {
            java.lang.reflect.Field field = holder.getClass().getField("provider"); field.setAccessible(true);
            Object provider = field.get(holder);
            java.lang.reflect.Method call = provider.getClass().getMethod("call", String.class, String.class,
                String.class, String.class, Bundle.class); call.setAccessible(true);
            return (Bundle) call.invoke(provider, "com.android.shell", authority, "connect", null, connection);
        } finally {
            java.lang.reflect.Method release = manager.getClass().getMethod("removeContentProviderExternalAsUser",
                String.class, IBinder.class, int.class); release.setAccessible(true);
            release.invoke(manager, authority, token, 0);
        }
    }

    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 2000) throw new SecurityException("Start through authorized ADB shell");
        Looper.prepareMainLooper();
        Context context = shellContext();
        shell = context;
        MediaSessionManager manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        // Prove platform authorization before publishing the reader. Do not change permissions.
        manager.getActiveSessions(null);
        Handler main = new Handler(Looper.getMainLooper());
        Binder reader = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code != 1 && code != 2) return super.onTransact(code, data, reply, flags);
                if (Binder.getCallingUid() != launcherUid) throw new SecurityException("Only launcher can read metadata");
                data.enforceInterface(DESCRIPTOR);
                String known = data.readString();
                if (known == null || known.length() > 64) throw new IllegalArgumentException("Invalid reference");
                String preferred = code==1 && data.dataAvail()>0 ? data.readString() : "";
                if (preferred==null || (!preferred.isEmpty() && !MediaSources.validId(preferred)))
                    throw new IllegalArgumentException("Invalid selected session");
                int key = code==2 ? data.readInt() : 0;
                if (data.dataAvail()!=0) throw new IllegalArgumentException("Unexpected fields");
                long identity = Binder.clearCallingIdentity();
                try {
                    if (code==2) {
                        boolean sent=control(manager,known,key);
                        reply.writeNoException();reply.writeInt(sent ? 1 : 0);return true;
                    }
                    byte[] packet = packet(manager, known, preferred);
                    reply.writeNoException(); reply.writeByteArray(packet); return true;
                } catch (Exception unavailable) { throw new IllegalStateException("Media session unavailable", unavailable); }
                finally { Binder.restoreCallingIdentity(identity); }
            }
        };
        Runnable register = new Runnable() {
            boolean reported;
            @Override public void run() {
                if (owner != null && owner.isBinderAlive()) return;
                try {
                    launcherUid = context.getPackageManager().getApplicationInfo(LAUNCHER, 0).uid;
                    Bundle connection = new Bundle(); connection.putBinder("reader", reader);
                    Bundle result = publish(connection);
                    IBinder lifetime = result == null ? null : result.getBinder("owner");
                    if (lifetime == null) throw new IllegalStateException("Launcher not ready");
                    owner = lifetime;
                    lifetime.linkToDeath(() -> main.post(() -> {
                        if (owner == lifetime) { owner = null; main.postDelayed(this, 500); }
                    }), 0);
                    System.out.println("SPOTIFY_BRIDGE_READY uid=2000 binderOnly=true launcherUid=" + launcherUid);
                    System.out.flush();
                    reported = false;
                } catch (Exception unavailable) {
                    if (!reported) { System.err.println("SPOTIFY_BRIDGE_CONNECT_WAIT " + unavailable); unavailable.printStackTrace(System.err); reported = true; }
                    owner = null; main.postDelayed(this, 5000);
                }
            }
        };
        main.post(register); Looper.loop();
    }

    private static synchronized byte[] packet(MediaSessionManager manager, String known, String preferred) throws Exception {
        Snapshot snapshot = read(manager, preferred);
        byte[] json = snapshot.info.toString().getBytes(StandardCharsets.UTF_8);
        if (json.length>24576) throw new IllegalStateException("Metadata too large");
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC); output.writeInt(json.length); output.write(json);
            boolean unchanged = snapshot.revision.equals(known);
            output.writeInt(unchanged ? -1 : snapshot.art.length);
            if (!unchanged) output.write(snapshot.art);
            output.flush(); return bytes.toByteArray();
        }
    }

    private static List<MediaController> sessions(MediaSessionManager manager) {
        List<MediaController> live = new ArrayList<>();
        for (MediaController controller : manager.getActiveSessions(null)) {
            if (live.size()>=16) break;
            if (LAUNCHER.equals(controller.getPackageName()) || !MediaSources.validPackage(controller.getPackageName())) continue;
            PlaybackState state=controller.getPlaybackState();
            if (state==null) continue;
            if (state.getState()==PlaybackState.STATE_NONE && controller.getMetadata()==null) continue;
            live.add(controller);
        }
        java.util.HashSet<MediaSession.Token> tokens = new java.util.HashSet<>();
        for (MediaController controller:live) {
            MediaSession.Token token=controller.getSessionToken(); tokens.add(token);
            if (!ids.containsKey(token)) ids.put(token,UUID.randomUUID().toString().replace("-",""));
        }
        ids.keySet().retainAll(tokens);
        return live;
    }

    private static synchronized boolean control(MediaSessionManager manager,String id,int key) {
        if (!MediaSources.validId(id)) return false;
        for (MediaController controller:sessions(manager)) {
            if (!id.equals(ids.get(controller.getSessionToken()))) continue;
            PlaybackState state=controller.getPlaybackState();
            if (state==null) return false;
            boolean playing=state.getState()==PlaybackState.STATE_PLAYING;
            if (!MediaSources.supports(state.getActions(),key,playing)) return false;
            MediaController.TransportControls controls=controller.getTransportControls();
            if (key==87) controls.skipToNext();
            else if (key==88) controls.skipToPrevious();
            else if (key==89 || key==90) {
                MediaMetadata meta=controller.getMetadata();
                long duration=meta==null ? 0L : Math.max(0L, meta.getLong(MediaMetadata.METADATA_KEY_DURATION));
                long target=MediaSources.seekTarget(state.getPosition(), state.getLastPositionUpdateTime(),
                    state.getPlaybackSpeed(), state.getState(), duration, android.os.SystemClock.elapsedRealtime(), key);
                if (target<0L) return false;
                controls.seekTo(target);
            }
            else if (playing) controls.pause(); else controls.play();
            return true;
        }
        return false;
    }

    private static Snapshot read(MediaSessionManager manager,String preferred) throws Exception {
        MediaMetadata metadata = null;
        List<MediaController> controllers = sessions(manager);
        String[] references=new String[controllers.size()];int[] states=new int[controllers.size()];
        JSONArray choices=new JSONArray();
        for (int i=0;i<controllers.size();i++) {
            MediaController controller=controllers.get(i);
            references[i]=ids.get(controller.getSessionToken()); states[i]=controller.getPlaybackState().getState();
            String label=controller.getPackageName();
            try { label=shell.getPackageManager().getApplicationLabel(shell.getPackageManager().getApplicationInfo(label,0)).toString(); }
            catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
            label=label.substring(0,Math.min(80,label.length()));
            choices.put(new JSONObject().put("id",references[i]).put("package",controller.getPackageName())
                .put("label",label).put("state",states[i]));
        }
        int index=MediaSources.choose(references,states,preferred);
        MediaController selected=index<0 ? null : controllers.get(index);
        String sessionId=index<0 ? "" : references[index];
        String source=selected==null ? "" : selected.getPackageName();
        if (selected != null) metadata = selected.getMetadata();
        String title = text(metadata, MediaMetadata.METADATA_KEY_TITLE);
        if (title.isEmpty()) title = text(metadata, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String displaySubtitle = text(metadata, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        String liveLyric = MediaSources.extractLiveLyric(displaySubtitle);
        String artist = text(metadata, MediaMetadata.METADATA_KEY_ARTIST);
        if (artist.isEmpty()) artist = text(metadata, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (artist.isEmpty() && liveLyric.isEmpty()) artist = displaySubtitle;
        if (artist.isEmpty()) artist = text(metadata, MediaMetadata.METADATA_KEY_AUTHOR);
        Bitmap art = null;
        if (metadata != null) {
            for (String key : new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART,
                    MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
                art = metadata.getBitmap(key);
                if (art != null && !art.isRecycled()) break;
            }
        }
        String youtubeId=YouTubeTrack.extract(source,text(metadata,MediaMetadata.METADATA_KEY_MEDIA_ID),text(metadata,MediaMetadata.METADATA_KEY_MEDIA_URI));
        StringBuilder identity = new StringBuilder(sessionId).append('\n').append(youtubeId).append('\n').append(title).append('\n').append(artist).append('\n')
            .append(text(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID));
        if (art != null && !art.isRecycled()) {
            identity.append(':').append(art.getWidth()).append('x').append(art.getHeight());
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++)
                identity.append(':').append(art.getPixel((2*x+1)*art.getWidth()/16, (2*y+1)*art.getHeight()/16));
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        String revision = hex.toString();
        if (!revision.equals(cachedRevision)) {
            byte[] encoded = new byte[0];
            if (art != null && !art.isRecycled()) {
                encoded = encodeArtwork(art);
            }
            cachedArt = encoded; cachedRevision = revision;
        }
        JSONObject info = new JSONObject().put("version", 2).put("source", source)
            .put("sessionId",sessionId).put("sources",choices)
            .put("revision", revision).put("title", title).put("artist", artist)
            .put("album", text(metadata, MediaMetadata.METADATA_KEY_ALBUM))
            .put("youtubeId",youtubeId)
            .put("spotifyId", SPOTIFY.equals(source) ? spotifyId(text(metadata,MediaMetadata.METADATA_KEY_MEDIA_ID)) : "");
        if (!liveLyric.isEmpty()) info.put("liveLyric", liveLyric);
        PlaybackState playback = selected == null ? null : selected.getPlaybackState();
        if (playback != null) {
            long duration = metadata == null ? 0 : Math.max(0, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
            info.put("playback", new JSONObject().put("duration", duration)
                .put("position", playback.getPosition()).put("updated", playback.getLastPositionUpdateTime())
                .put("speed", playback.getPlaybackSpeed()).put("state", playback.getState()).put("actions",playback.getActions()));
        }
        return new Snapshot(info, revision, cachedArt);
    }

    private static byte[] encodeArtwork(Bitmap art) {
        int width = art.getWidth(), height = art.getHeight(), maxDim = Math.max(width, height);
        if (maxDim < 1) return new byte[0];
        if (maxDim <= 320) {
            try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                if (art.compress(Bitmap.CompressFormat.PNG, 100, bytes) && bytes.size() <= 524288)
                    return bytes.toByteArray();
            } catch (Exception ignored) {}
        }
        float scale = Math.min(1f, 1024f / maxDim);
        Bitmap scaled = scale < 1f ? Bitmap.createScaledBitmap(art,
            Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)), true) : art;
        try {
            if (!scaled.hasAlpha()) {
                for (int quality : new int[]{92, 82}) {
                    try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                        if (scaled.compress(Bitmap.CompressFormat.JPEG, quality, bytes) && bytes.size() <= 458752)
                            return bytes.toByteArray();
                    } catch (Exception ignored) {}
                }
            } else {
                try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    if (scaled.compress(Bitmap.CompressFormat.PNG, 100, bytes) && bytes.size() <= 458752)
                        return bytes.toByteArray();
                } catch (Exception ignored) {}
                Bitmap opaque = Bitmap.createBitmap(scaled.getWidth(), scaled.getHeight(), Bitmap.Config.ARGB_8888);
                try {
                    android.graphics.Canvas canvas = new android.graphics.Canvas(opaque);
                    canvas.drawColor(0xff0b0b0f);
                    canvas.drawBitmap(scaled, 0f, 0f, null);
                    try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                        if (opaque.compress(Bitmap.CompressFormat.JPEG, 90, bytes) && bytes.size() <= 458752)
                            return bytes.toByteArray();
                    }
                } catch (Exception ignored) {
                } finally { opaque.recycle(); }
            }
        } finally { if (scaled != art) scaled.recycle(); }
        float fallbackScale = Math.min(1f, 320f / maxDim);
        Bitmap fallback = fallbackScale < 1f ? Bitmap.createScaledBitmap(art,
            Math.max(1, Math.round(width * fallbackScale)), Math.max(1, Math.round(height * fallbackScale)), true) : art;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            if (fallback.compress(Bitmap.CompressFormat.PNG, 100, bytes) && bytes.size() <= 524288)
                return bytes.toByteArray();
        } catch (Exception ignored) {
        } finally { if (fallback != art) fallback.recycle(); }
        return new byte[0];
    }

    static String spotifyId(String mediaId) {
        java.util.regex.Matcher match=java.util.regex.Pattern.compile("^(?:spotify:track:|https://open\\.spotify\\.com/track/)([A-Za-z0-9]{22})(?:\\?[^\\s#]*)?$").matcher(mediaId);
        return match.matches() ? match.group(1) : "";
    }
    private static String text(MediaMetadata metadata, String key) {
        String value = metadata == null ? null : metadata.getString(key);
        return value == null ? "" : value.substring(0, Math.min(512, value.length()));
    }
    private static final class Snapshot {
        final JSONObject info; final String revision; final byte[] art;
        Snapshot(JSONObject info, String revision, byte[] art) { this.info=info; this.revision=revision; this.art=art; }
    }
}
