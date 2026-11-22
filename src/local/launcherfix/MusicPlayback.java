package local.launcherfix;

/** Playback timestamps use Android's elapsed-realtime clock, shared by both local processes. */
final class MusicPlayback {
    static final MusicPlayback UNKNOWN = new MusicPlayback(false, 0, -1, 0, 0f, 0);
    final boolean known;
    final long duration, position, updated;
    final float speed;
    final int state;
    MusicPlayback(boolean known, long duration, long position, long updated, float speed, int state) {
        this.known=known; this.duration=duration; this.position=position;
        this.updated=updated; this.speed=speed; this.state=state;
    }
    boolean playing() { return known && state == 3; }
    long positionAt(long now) {
        if (!known || position < 0) return -1;
        double value = position;
        if (playing() && updated > 0 && now > updated) value += (now-updated)*(double)speed;
        return (long)Math.max(0, Math.min(duration > 0 ? duration : Long.MAX_VALUE, value));
    }
    static String time(long milliseconds) {
        if (milliseconds < 0) return "--:--";
        long seconds=milliseconds/1000, minutes=seconds/60;
        return minutes+":"+(seconds%60 < 10 ? "0" : "")+seconds%60;
    }
}
