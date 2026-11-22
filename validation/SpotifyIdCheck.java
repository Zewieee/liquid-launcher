package local.launcherbridge;
public final class SpotifyIdCheck {
    public static void main(String[] args) {
        String id="0123456789ABCDEFGHIJKL";
        String[] input={"spotify:track:"+id,"https://open.spotify.com/track/"+id+"?si=abc","spotify:album:"+id,
            "https://open.spotify.com.evil/track/"+id,"spotify:track:../bad","",id};
        int count=0;
        for(int i=0;i<input.length;i++) {
            String value=SpotifyMediaBridge.spotifyId(input[i]);
            if(!value.equals(i<2 ? id : ""))throw new AssertionError(input[i]);count++;
        }
        System.out.println("SPOTIFY_ID_CHECK_OK cases="+count);
    }
}
