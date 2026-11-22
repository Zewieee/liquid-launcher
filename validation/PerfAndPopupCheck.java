package local.launcherfix;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

public final class PerfAndPopupCheck {
    static int checks;
    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) throw new AssertionError(msg);
    }

    static float lumaOf(int argb) {
        int r = (argb >> 16) & 255;
        int g = (argb >> 8) & 255;
        int b = argb & 255;
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    static byte[] buildV2Packet(int artW, int artH) throws Exception {
        String id = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        JSONObject info = new JSONObject()
            .put("version", 2)
            .put("source", "org.videolan.vlc")
            .put("sessionId", id)
            .put("sources", new JSONArray().put(new JSONObject()
                .put("id", id).put("package", "org.videolan.vlc").put("label", "VLC").put("state", 3)))
            .put("title", "夜に駆ける")
            .put("artist", "YOASOBI")
            .put("album", "THE BOOK")
            .put("spotifyId", "")
            .put("revision", id + id)
            .put("playback", new JSONObject()
                .put("duration", 260000).put("position", 15000).put("updated", 10000)
                .put("speed", 1).put("state", 3).put("actions", 566));
        Bitmap bmp = Bitmap.createBitmap(artW, artH, Bitmap.Config.ARGB_8888);
        bmp.eraseColor(0xff2864b8);
        ByteArrayOutputStream artOut = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, artOut);
        bmp.recycle();
        byte[] artBytes = artOut.toByteArray();
        byte[] jsonBytes = info.toString().getBytes("UTF-8");
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(frame);
        dos.writeInt(0x4c4d4231);
        dos.writeInt(jsonBytes.length);
        dos.write(jsonBytes);
        dos.writeInt(artBytes.length);
        dos.write(artBytes);
        return frame.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        if (android.os.Looper.getMainLooper() == null) {
            android.os.Looper.prepareMainLooper();
        }
        // 1. Bounded artwork decoding for DockMusicPill (96px) & MiniPlayer (800px)
        byte[] hdPacket = buildV2Packet(800, 600);
        SpotifyMetadata.Response pillRes = SpotifyMetadata.parse(
            new DataInputStream(new ByteArrayInputStream(hdPacket)), "", 96);
        check(pillRes.art != null && pillRes.art.getWidth() <= 96 && pillRes.art.getHeight() <= 96,
            "DockMusicPill 96px cap: got " + (pillRes.art == null ? "null" : pillRes.art.getWidth() + "x" + pillRes.art.getHeight()));
        check(pillRes.art.getAllocationByteCount() <= 96 * 96 * 4, "DockMusicPill <=36KB bitmap");
        pillRes.art.recycle();

        SpotifyMetadata.Response miniRes = SpotifyMetadata.parse(
            new DataInputStream(new ByteArrayInputStream(hdPacket)), "", 800);
        check(miniRes.art != null && miniRes.art.getWidth() == 800 && miniRes.art.getHeight() == 600,
            "MiniPlayer retains 800px source: got " + (miniRes.art == null ? "null" : miniRes.art.getWidth() + "x" + miniRes.art.getHeight()));
        check(miniRes.art.getAllocationByteCount() <= 800 * 800 * 4, "MiniPlayer artwork bounded by requested cap");

        // Oversized v2 (>800) must still be rejected even with small targetMaxSide
        byte[] overPacket = buildV2Packet(1025, 64);
        try {
            SpotifyMetadata.parse(new DataInputStream(new ByteArrayInputStream(overPacket)), "", 96);
            throw new AssertionError("Oversized v2 art must be rejected before downsampling");
        } catch (IOException expected) {
            checks++;
        }

        // 2. Fast inline HSV/RGB parity
        float[] tmpHsv = new float[3];
        for (int h = 0; h < 360; h += 17) {
            for (int si = 0; si <= 4; si++) {
                for (int vi = 1; vi <= 4; vi++) {
                    float s = si * 0.25f, v = vi * 0.22f;
                    tmpHsv[0] = h; tmpHsv[1] = s; tmpHsv[2] = v;
                    int ref = Color.HSVToColor(255, tmpHsv);
                    int fast = MusicBackdropView.hsvToRgbInt(h, s, v);
                    int dr = Math.abs(((ref >> 16) & 255) - ((fast >> 16) & 255));
                    int dg = Math.abs(((ref >> 8) & 255) - ((fast >> 8) & 255));
                    int db = Math.abs((ref & 255) - (fast & 255));
                    if (dr > 1 || dg > 1 || db > 1) {
                        throw new AssertionError("HSV->RGB mismatch at h=" + h + " s=" + s + " v=" + v);
                    }
                }
            }
        }
        checks++;

        // 3. 21-stop C2 quintic smootherstep dissolve ramp (zero Mach-band endpoint slope kinks)
        int[] dissolve = MusicBackdropView.dissolveColors();
        check(dissolve.length == 21, "21-stop C2 dissolve ramp length");
        int prevAlpha = 255;
        for (int i = 0; i < dissolve.length; i++) {
            int a = (dissolve[i] >>> 24) & 255;
            if (a > prevAlpha) throw new AssertionError("Dissolve alpha non-monotonic at stop " + i);
            prevAlpha = a;
        }
        int a0 = (dissolve[0] >>> 24) & 255;
        int a1 = (dissolve[1] >>> 24) & 255;
        int a19 = (dissolve[19] >>> 24) & 255;
        int a20 = (dissolve[20] >>> 24) & 255;
        check(a0 == 255 && a20 == 0 && (a0 - a1) <= 4 && a19 <= 4,
            "Quintic smootherstep gentle zero-derivative endpoints: a1=" + a1 + " a19=" + a19);

        // 4. Calm, smoked-glass atmosphere brightness & seamless horizontal dissolve continuity
        AtomicInteger seq = new AtomicInteger(7);
        Bitmap composed = MusicBackdropView.buildComposedOffThread(miniRes.art, 1280, 720, 7, seq);
        check(composed != null && composed.getWidth() == 1280 && composed.getHeight() == 720 && !composed.isRecycled(),
            "Off-thread 1280x720 backdrop composition");
        check(composed.getAllocationByteCount() <= 1280 * 720 * 4, "Popup composed frame <=3.52MiB");
        float rightStageLuma = lumaOf(composed.getPixel(1013, 293));
        check(rightStageLuma >= 58f && rightStageLuma <= 108f,
            "Balanced calm smoked-glass right-stage atmosphere luma=" + rightStageLuma);

        float maxStepDelta = 0f;
        float prevLuma = lumaOf(composed.getPixel(187, 293));
        for (int x = 192; x <= 853; x += 5) {
            float curLuma = lumaOf(composed.getPixel(x, 293));
            maxStepDelta = Math.max(maxStepDelta, Math.abs(curLuma - prevLuma));
            prevLuma = curLuma;
        }
        check(maxStepDelta < 5.0f, "Seamless cover-to-atmosphere dissolve (no vertical seam): maxStepDelta=" + maxStepDelta);

        // 1-track revision cache & trimMemory(20) deterministic recycle
        String testRev = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        Method putCache = MusicBackdropView.class.getDeclaredMethod(
            "putCacheMain", String.class, Bitmap.class, WallpaperTheme.Tone.class);
        putCache.setAccessible(true);
        putCache.invoke(null, testRev, composed, WallpaperTheme.sample(miniRes.art));
        check(MusicBackdropView.hasCachedBackdrop(testRev), "1-track revision backdrop cache hit");
        MusicBackdropView.trimMemory(20);
        check(!MusicBackdropView.hasCachedBackdrop(testRev) && composed.isRecycled(),
            "MusicBackdropView.trimMemory(20) evicts and recycles cached backdrop");

        // Pastel/bright cover check (e.g. 9Lana "propose" silver-pink): must stay smoked <= 112 luma so white lyrics pop!
        Bitmap pastelArt = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        pastelArt.eraseColor(0xffdcd0d4);
        Bitmap pastelComposed = MusicBackdropView.buildComposedOffThread(pastelArt, 1280, 720, 7, seq);
        float pastelStageLuma = lumaOf(pastelComposed.getPixel(1013, 293));
        check(pastelStageLuma >= 65f && pastelStageLuma <= 112f,
            "Pastel bright cover calmed to smoked glass for crisp white lyrics: luma=" + pastelStageLuma);
        pastelComposed.recycle();
        pastelArt.recycle();

        // Stale sequence cancels cleanly without leaking
        AtomicInteger movedSeq = new AtomicInteger(9);
        Bitmap cancelled = MusicBackdropView.buildComposedOffThread(miniRes.art, 1280, 720, 8, movedSeq);
        check(cancelled == null, "Stale sequence cancels backdrop composition");
        miniRes.art.recycle();

        // 5. PopupOptimizer capture dimensions, calm 0x48000000 veil & universal popup motion curves
        check(PopupOptimizer.captureWidth() == 240 && PopupOptimizer.captureHeight(1920, 1080) == 135,
            "PopupOptimizer 240x135 capture bounds");
        Bitmap shot = Bitmap.createBitmap(240, 135, Bitmap.Config.ARGB_8888);
        shot.eraseColor(0xffffffff);
        PopupOptimizer.blurAndDimInPlace(shot);
        int centerPx = shot.getPixel(120, 67);
        check(!shot.isRecycled() && ((centerPx >> 16) & 255) < 205 && ((centerPx >> 16) & 255) > 160,
            "PopupOptimizer pre-baked 0x48000000 calm veil applied: r=" + ((centerPx >> 16) & 255));
        shot.recycle();

        float prevIn = 0f, prevOut = 0f;
        for (int i = 0; i <= 20; i++) {
            float t = i / 20f;
            float eIn = PopupOptimizer.ENTRANCE_EASE.getInterpolation(t);
            float eOut = PopupOptimizer.EXIT_EASE.getInterpolation(t);
            check(eIn >= prevIn - 1e-5f && eIn <= 1.0001f, "ENTRANCE_EASE monotonic without overshoot at t=" + t);
            check(eOut >= prevOut - 1e-5f && eOut <= 1.0001f, "EXIT_EASE monotonic without overshoot at t=" + t);
            prevIn = eIn;
            prevOut = eOut;
        }
        check(!PopupOptimizer.shouldInterceptBack(null) && !PopupOptimizer.dismissAnimated(null, true),
            "PopupOptimizer null dialog safety");
        PopupOptimizer.SmoothPopupWindow spw = new PopupOptimizer.SmoothPopupWindow(null, 100, 100, true);
        PopupOptimizer.dismissPopupImmediate(spw);
        check(!spw.isShowing(), "SmoothPopupWindow safe immediate dismiss");

        // 6. JapaneseRomaji cached Tokenizer reuse & trimMemory recovery
        Lyrics jp = Lyrics.parse("[00:01]夜に駆ける\n[00:04]沈むように溶けてゆくように");
        Lyrics r1 = JapaneseRomaji.convert(jp);
        long t0 = System.nanoTime();
        Lyrics r2 = JapaneseRomaji.convert(jp);
        long cachedMs = (System.nanoTime() - t0) / 1_000_000L;
        check(r1.romajiAt(0).equals(r2.romajiAt(0)) && !r2.romajiAt(0).isEmpty(), "Cached tokenizer parity");
        check(cachedMs < 250L, "Cached tokenizer fast reuse elapsed=" + cachedMs + "ms");
        JapaneseRomaji.trimMemory(20);
        Lyrics r3 = JapaneseRomaji.convert(jp);
        check(r1.romajiAt(0).equals(r3.romajiAt(0)), "Tokenizer recovers cleanly after trimMemory(20)");

        System.out.println("PERF_AND_POPUP_CHECK_OK cases=" + checks
            + " right_luma=" + Math.round(rightStageLuma)
            + " pastel_luma=" + Math.round(pastelStageLuma)
            + " max_seam_step=" + String.format(java.util.Locale.US, "%.2f", maxStepDelta)
            + " cached_romaji_ms=" + cachedMs);
    }
}
