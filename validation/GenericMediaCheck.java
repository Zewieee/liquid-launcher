package local.launcherfix;

import android.media.MediaMetadata;
import android.media.session.PlaybackState;

/** Pure contract check for generic public-player metadata conversion. */
public final class GenericMediaCheck {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        String id = "0123456789abcdef0123456789abcdef";
        MediaMetadata metadata = new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Paranoid Lost")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Halozy")
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "Tonic Triangle")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 180000L)
            .build();
        PlaybackState state = new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT)
            .setState(PlaybackState.STATE_PAUSED, 1200L, 0f, 1000L).build();
        SpotifyMetadata.Response response = MochyMediaBrowser.snapshot(
            metadata, state, id, "", 96, "com.music.bitchord.tv", "BitChord TV");
        check(response != null, "generic metadata produces a response");
        check(response.version == 2 && response.source.equals("com.music.bitchord.tv"), "generic package preserved");
        check(response.sourceLabel().equals("BitChord TV"), "generic label preserved");
        check(response.title.equals("Paranoid Lost") && response.artist.equals("Halozy"), "generic text preserved");
        check(MediaSources.validPackage("com.music.bitchord.tv"), "valid player package accepted");
        check(response.sources.length == 1 && response.sources[0].packageName.equals("com.music.bitchord.tv"), "generic source contract");
        System.out.println("GENERIC_MEDIA_CHECK_OK cases=" + checks);
    }
}
