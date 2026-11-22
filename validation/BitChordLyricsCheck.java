package local.launcherfix;

import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Looper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Public BitChord sample -> snapshot/bridge packet -> existing lyric lookup. No playback commands. */
public final class BitChordLyricsCheck {
    private static final String PKG = "com.music.bitchord.tv";
    private static final String ID = "kAdNwmQvIbY", OTHER = "UEBliYVwP4U";
    private static final String SESSION = "0123456789abcdef0123456789abcdef";
    private static int checks;
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }
    private static MediaMetadata track(String id, String uri) {
        return new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "MORE&MORE")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Momo Belia Deviluke starring Toyosaki Aki")
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, id)
            .putString(MediaMetadata.METADATA_KEY_MEDIA_URI, uri)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 232141).build();
    }
    private static SpotifyMetadata.Response snapshot(String pkg, String id, String uri, String revision) throws Exception {
        PlaybackState state = new PlaybackState.Builder().setState(2, 95088, 0, 10000).setActions(7340029).build();
        return MochyMediaBrowser.snapshot(track(id, uri), state, SESSION, revision, 96, pkg, "BitChord TV");
    }
    private static JSONObject packet(String pkg, String video) throws Exception {
        return new JSONObject().put("version", 2).put("source", pkg).put("sessionId", SESSION)
            .put("sources", new JSONArray().put(new JSONObject().put("id", SESSION).put("package", pkg)
                .put("label", "BitChord TV").put("state", 2)))
            .put("title", "MORE&MORE").put("artist", "Artist").put("album", "")
            .put("revision", SESSION + SESSION).put("spotifyId", "").put("youtubeId", video)
            .put("playback", new JSONObject().put("duration", 232141).put("position", 95088)
                .put("updated", 10000).put("speed", 0).put("state", 2).put("actions", 7340029));
    }
    private static SpotifyMetadata.Response parse(JSONObject info) throws Exception {
        byte[] json = info.toString().getBytes("UTF-8");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0x4c4d4231); out.writeInt(json.length); out.write(json); out.writeInt(0);
        return SpotifyMetadata.parse(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), "");
    }
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        SpotifyMetadata.Response direct = snapshot(PKG, ID, "", "");
        check(ID.equals(direct.youtubeId), "BitChord public media ID must reach direct lyric input");
        check(direct.title.equals("MORE&MORE") && direct.playback.positionAt(20000) == 95088,
            "track text and paused clock preserved");
        check(YouTubeTrack.extract(PKG, ID, "").equals(ID), "shared bridge extractor accepts BitChord ID");
        check(snapshot(PKG, "", "https://music.youtube.com/watch?v=" + ID, "").youtubeId.equals(ID),
            "trusted media URI fallback");
        check(snapshot(PKG, ID, "https://youtu.be/" + OTHER, "").youtubeId.isEmpty(), "conflicting video IDs rejected");
        check(snapshot(PKG, "content://music/local", "file:///sdcard/song.mp3", "").youtubeId.isEmpty(), "local item has no invented video");
        check(snapshot(PKG, "bad", "https://youtube.com.evil/watch?v=" + ID, "").youtubeId.isEmpty(), "untrusted URI rejected");
        check(snapshot("org.videolan.vlc", ID, "", "").youtubeId.isEmpty(), "unknown player ID not reinterpreted");
        check(snapshot(MochyMediaBrowser.PACKAGE, ID, "", "").youtubeId.isEmpty(), "Mochy behavior unchanged");
        SpotifyMetadata.Response uriFirst = snapshot(PKG, "", "https://youtu.be/" + ID, "");
        check(!snapshot(PKG, "", "https://youtu.be/" + OTHER, uriFirst.revision).unchanged,
            "URI-only video change invalidates snapshot revision");
        check(snapshot(PKG, ID, "", direct.revision).unchanged, "same video retains snapshot revision");
        check(parse(packet(PKG, ID)).youtubeId.equals(ID), "BitChord video crosses bridge packet parser");
        JSONObject old = packet(PKG, ""); old.remove("youtubeId");
        check(parse(old).youtubeId.isEmpty(), "old bridge without video ID remains compatible");
        try { parse(packet(PKG, "../bad")); throw new AssertionError("malformed video accepted"); }
        catch (java.io.IOException expected) { checks++; }
        try { parse(packet("org.videolan.vlc", ID)); throw new AssertionError("cross-source video accepted"); }
        catch (java.io.IOException expected) { checks++; }
        LiveLyrics live = new LiveLyrics((data, status) -> {});
        try {
            live.setTrack(direct.title, direct.artist, "", "", direct.youtubeId, direct.playback.duration);
            String first = live.trackKey();
            check(first.contains("\n" + ID + "\n"), "video ID reaches lyric cache identity");
            live.setTrack(direct.title, direct.artist, "", "", OTHER, direct.playback.duration);
            check(!first.equals(live.trackKey()), "different recording cannot reuse old lyric cache key");
        } finally { live.close(); }
        boolean[] sawVideo = {false}, sawFallback = {false};
        LyricSources.find(direct.title, direct.artist, "", "", direct.youtubeId, direct.playback.duration, url -> {
            if (url.contains("unison.betterlyrics.org")) sawVideo[0] |= url.contains("&v=" + ID);
            if (url.contains("lrclib.net")) sawFallback[0] = true;
            return null;
        }, result -> {});
        check(sawVideo[0], "exact BitChord video reaches existing lyric provider request");
        check(sawFallback[0], "metadata lookup remains fallback when exact source misses");
        System.out.println("BITCHORD_LYRICS_CHECK_OK cases=" + checks);
    }
}
