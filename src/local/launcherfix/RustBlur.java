package local.launcherfix;

import android.graphics.Bitmap;
import android.util.Log;

/** Optional native kernel; all unsupported formats keep the original Java implementation. */
final class RustBlur {
    private static final String TAG = "LiquidRustBlur";
    private static volatile int state; // 0 not loaded, 1 ready, 2 disabled

    private RustBlur() {}
    static synchronized boolean ready() {
        if (state == 0) {
            state = 2;
            try {
                System.loadLibrary("liquid_blur");
                if (probeNative() && bitmapProbe()) state = 1;
                if (state != 1) Log.w(TAG, "Rust parity guard failed; using Java");
            } catch (LinkageError | RuntimeException | OutOfMemoryError unavailable) {
                Log.w(TAG, "Rust blur unavailable; using Java", unavailable);
            }
        }
        return state == 1;
    }
    private static boolean bitmapProbe() {
        Bitmap bitmap = Bitmap.createBitmap(7, 5, Bitmap.Config.ARGB_8888);
        try {
            int[] pixels = new int[35];
            for (int i = 0; i < pixels.length; i++) {
                int r = ((i * 3) * 73 + 19) & 255;
                int g = ((i * 3 + 1) * 73 + 19) & 255;
                int b = ((i * 3 + 2) * 73 + 19) & 255;
                pixels[i] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
            bitmap.setPixels(pixels, 0, 7, 0, 0, 7, 5);
            if (!blurNative(bitmap)) return false;
            bitmap.getPixels(pixels, 0, 7, 0, 0, 7, 5);
            int hash = 0x811c9dc5;
            for (int pixel : pixels) {
                if ((pixel >>> 24) != 255) return false;
                hash = (hash ^ ((pixel >>> 16) & 255)) * 16777619;
                hash = (hash ^ ((pixel >>> 8) & 255)) * 16777619;
                hash = (hash ^ (pixel & 255)) * 16777619;
            }
            return hash == 0xd322b20f;
        } finally { bitmap.recycle(); }
    }
    static boolean tryBlur(Bitmap bitmap) {
        if (bitmap.getConfig() != Bitmap.Config.ARGB_8888 || !bitmap.isMutable()
                || bitmap.getColorSpace() == null || !bitmap.getColorSpace().isSrgb()) return false;
        if (!ready()) return false;
        try {
            return blurNative(bitmap);
        }
        catch (LinkageError | RuntimeException unavailable) {
            state = 2;
            Log.w(TAG, "Rust blur disabled; using Java", unavailable);
            return false;
        }
    }
    static void trim() {
        if (state != 1) return;
        try { trimNative(); }
        catch (LinkageError | RuntimeException unavailable) { state = 2; }
    }
    private static native boolean probeNative();
    private static native boolean blurNative(Bitmap bitmap);
    private static native void trimNative();
}
