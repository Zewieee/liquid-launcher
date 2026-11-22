package local.launcherfix;

import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;

/** Snapshot checks run without opening a player or sending transport commands. */
public final class DirectMediaCheck {
    private static final String A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static int count;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        count++;
    }
    private static PlaybackState state(int value, long actions) {
        return new PlaybackState.Builder().setState(value, 12000, value == 3 ? 1f : 0f, 10000)
            .setActions(actions).build();
    }
    private static MediaMetadata metadata(Bitmap art) {
        MediaMetadata.Builder builder = new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "曲名")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Nghệ sĩ")
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "アルバム")
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "mochy-local-item")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 200000);
        if (art != null) builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        return builder.build();
    }
    public static void main(String[] args) throws Exception {
        PlaybackState playing = state(3, 566), paused = state(2, 566);
        Bitmap art = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888);
        art.eraseColor(0xff305080);
        MediaMetadata track = metadata(art);
        SpotifyMetadata.Response dock = MochyMediaBrowser.snapshot(track, playing, A, "", 96);
        SpotifyMetadata.Response popup = MochyMediaBrowser.snapshot(track, playing, B, "", 800);
        check(dock != null && dock.version == 2 && dock.source.equals(MochyMediaBrowser.PACKAGE), "direct source contract");
        check(dock.sessionId.equals(A) && dock.sources.length == 1 && dock.sources[0].id.equals(A), "token identity listed");
        check(dock.title.equals("曲名") && dock.artist.equals("Nghệ sĩ") && dock.album.equals("アルバム"), "Unicode metadata");
        check(dock.spotifyId.isEmpty() && dock.youtubeId.isEmpty(), "do not invent provider IDs");
        check(dock.playback.positionAt(11000) == 13000 && dock.actions == 566, "elapsed clock and actions");
        check(dock.art.getWidth() == 96 && dock.art.getHeight() == 72, "dock artwork cap");
        check(popup.art.getWidth() == 800 && popup.art.getHeight() == 600, "popup retains source artwork at 800px cap");
        check(dock.art != art && popup.art != art && !art.isRecycled(), "borrowed artwork stays owned by provider");
        check(dock.revision.equals(popup.revision), "same track revision across clients and caps");
        SpotifyMetadata.Response unchanged = MochyMediaBrowser.snapshot(track, paused, A, dock.revision, 96);
        check(unchanged.unchanged && unchanged.art == null, "known artwork does not allocate again");
        check(unchanged.playback.positionAt(90000) == 12000, "paused playback does not advance");
        MediaMetadata changed = new MediaMetadata.Builder(track).putString(MediaMetadata.METADATA_KEY_TITLE, "New song").build();
        check(!MochyMediaBrowser.snapshot(changed, playing, A, dock.revision, 96).revision.equals(dock.revision), "track change invalidates art");
        art.eraseColor(0xff805030);
        check(!MochyMediaBrowser.snapshot(track, playing, A, dock.revision, 96).revision.equals(dock.revision), "cover-only update invalidates art");
        SpotifyMetadata.Response noArt = MochyMediaBrowser.snapshot(metadata(null), playing, A, dock.revision, 96);
        check(!noArt.unchanged && noArt.art == null, "missing cover clears previous artwork");
        MediaMetadata aliases = new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, "Fallback title")
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, "Fallback artist").build();
        SpotifyMetadata.Response alias = MochyMediaBrowser.snapshot(aliases, playing, A, "", 96);
        check(alias.title.equals("Fallback title") && alias.artist.equals("Fallback artist"), "standard metadata fallback fields");
        check(MochyMediaBrowser.snapshot(null, playing, A, "", 96) == null, "missing metadata is not a track");
        check(MochyMediaBrowser.snapshot(track, null, A, "", 96) == null, "missing playback is not a live session");
        check(MochyMediaBrowser.snapshot(track, playing, "invalid", "", 96) == null, "invalid session rejected");
        PlaybackState invalid = new PlaybackState.Builder().setState(3, 0, Float.NaN, 10000).build();
        check(MochyMediaBrowser.snapshot(track, invalid, A, "", 96) == null, "invalid playback speed rejected");
        check(MochyMediaBrowser.canControl(A, A, playing, 85), "playing session permits pause");
        check(MochyMediaBrowser.canControl(A, A, paused, 85), "paused session permits play");
        check(!MochyMediaBrowser.canControl(B, A, playing, 85), "stale ID cannot control replacement session");
        check(!MochyMediaBrowser.canControl("", A, playing, 85), "dead session cannot receive command");
        check(!MochyMediaBrowser.canControl(A, A, playing, 26), "arbitrary key rejected");
        check(!MochyMediaBrowser.canControl(A, A, state(3, 2), 87), "unsupported skip rejected");
        check(!MochyMediaBrowser.canControl(A, A, null, 85), "no playback state means no control");
        Bitmap small = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888);
        SpotifyMetadata.Response copied = MochyMediaBrowser.snapshot(metadata(small), playing, A, "", 800);
        check(copied.art != small && copied.art.getWidth() == 32, "small artwork copied without upscaling");
        copied.art.recycle();
        check(!small.isRecycled(), "consumer cleanup cannot recycle borrowed artwork");
        art.recycle(); small.recycle(); dock.art.recycle(); popup.art.recycle();
        System.out.println("DIRECT_MEDIA_CHECK_OK cases=" + count);
    }
}
