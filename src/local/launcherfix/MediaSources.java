package local.launcherfix;

/** Shared selection policy; IDs belong to a single live platform session. */
public final class MediaSources {
    private MediaSources() {}
    public static boolean validId(String id) { return id != null && id.matches("[a-f0-9]{32}"); }
    public static boolean validPackage(String value) {
        return value != null && value.length() <= 200 && value.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+");
    }
    public static int choose(String[] ids, int[] states, String preferred) {
        for (int i=0;i<ids.length;i++) if (ids[i].equals(preferred)) return i;
        for (int i=0;i<ids.length;i++) if (states[i]==3) return i;
        for (int i=0;i<ids.length;i++) if (states[i]==6 || states[i]==8) return i;
        for (int i=0;i<ids.length;i++) if (states[i]==2) return i;
        return ids.length==0 ? -1 : 0;
    }
    public static boolean supports(long actions, int key, boolean playing) {
        if (key==87) return (actions & 32)!=0;
        if (key==88) return (actions & 16)!=0;
        if (key==85) return (actions & (512 | (playing ? 2 : 4)))!=0;
        if (key==89 || key==90) return (actions & 256L)!=0;
        return false;
    }
    /** Compute clamped +/-10s seek destination for KEYCODE_MEDIA_REWIND (89) / FAST_FORWARD (90). */
    public static long seekTarget(long position, long updatedAt, float speed, int state, long duration, long nowElapsed, int key) {
        if ((key != 89 && key != 90) || position < 0) return -1L;
        long current = position;
        if (state == 3 && updatedAt > 0 && nowElapsed > updatedAt) {
            float rate = (!Float.isNaN(speed) && !Float.isInfinite(speed) && speed > 0f && speed <= 16f) ? speed : 1f;
            current = position + Math.round((nowElapsed - updatedAt) * rate);
        }
        if (duration > 0) current = Math.max(0L, Math.min(duration, current));
        else current = Math.max(0L, current);
        long next = current + (key == 90 ? 10_000L : -10_000L);
        if (next < 0L) next = 0L;
        if (duration > 0 && next > duration) next = duration;
        return next;
    }
    /**
     * Extract a live lyric line published in public MediaMetadata DISPLAY_SUBTITLE
     * (e.g. BitChord TV emits "♪ <current line>"). Non-lyric subtitles return "".
     */
    public static String extractLiveLyric(String subtitle) {
        if (subtitle == null || subtitle.isEmpty()) return "";
        String trimmed = subtitle.trim();
        String body;
        if (trimmed.startsWith("\u266A") || trimmed.startsWith("\u266B")) {
            body = trimmed.substring(1);
        } else if (trimmed.startsWith("\uD83C\uDFB5")) {
            body = trimmed.substring(2);
        } else {
            return "";
        }
        body = body.trim();
        while (body.startsWith("\u266A") || body.startsWith("\u266B")) body = body.substring(1).trim();
        while (body.endsWith("\u266A") || body.endsWith("\u266B")) body = body.substring(0, body.length() - 1).trim();
        return sanitizeLiveLyric(body);
    }
    public static String sanitizeLiveLyric(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder out = new StringBuilder(Math.min(512, value.length()));
        boolean prevSpace = true;
        for (int i = 0; i < value.length() && out.length() < 512; i++) {
            char c = value.charAt(i);
            if ((c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069)) return "";
            if (c == '\r' || c == '\n' || c == '\t' || c == ' ') {
                if (!prevSpace) { out.append(' '); prevSpace = true; }
                continue;
            }
            if (Character.isISOControl(c)) continue;
            out.append(c);
            prevSpace = false;
        }
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == ' ') end--;
        return end == 0 ? "" : out.substring(0, end);
    }
}
