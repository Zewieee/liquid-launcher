package local.launcherfix;

import android.util.Log;
import com.gathin.tvhome.IconMaterialRenderer$Plate;
import com.gathin.tvhome.LiquidLensKernel$Geometry;
import java.util.Arrays;

/** Independent parity guards keep an unsupported kernel on its Java implementation. */
final class RustPixels {
    private static int iconState, lensState;
    private static boolean iconLogged, lensLogged;
    private static final int MAX_PIXELS = 4194304;
    private RustPixels() {}

    static synchronized boolean recolour(int[] pixels, int w, int h, int color,
            boolean transparent, boolean glass, IconMaterialRenderer$Plate plate) {
        if (pixels == null || plate == null || !size(w,h,pixels.length)
                || plate.red < 0 || plate.red > 255 || plate.green < 0 || plate.green > 255
                || plate.blue < 0 || plate.blue > 255) return false;
        try {
            if (iconState == 0) {
                iconState = 2;
                if (RustBlur.ready() && probeIcon()) iconState = 1;
                if (iconState != 1) Log.w("LiquidRustPixels", "Icon parity unavailable; using Java");
            }
            boolean success = iconState == 1 && recolourNative(pixels,w,h,color,transparent,glass,
                plate.found,plate.red,plate.green,plate.blue);
            if (success && !iconLogged) {
                iconLogged = true;
                Log.i("LiquidRustPixels", "Icon backend=Rust; first image processed");
            }
            return success;
        } catch (LinkageError | RuntimeException | OutOfMemoryError error) {
            iconState = 2;
            Log.w("LiquidRustPixels", "Icon using Java", error);
            return false;
        }
    }

    static synchronized boolean lens(int[] src, int[] dst, LiquidLensKernel$Geometry g,
            int sw, int sh, float x, float y, float lw, float lh, float strength, float glass) {
        if (g == null || src == null || dst == null || !size(sw,sh,src.length)
                || !size(g.width,g.height,dst.length) || sw < 2 || sh < 2) return false;
        try {
            if (lensState == 0) {
                lensState = 2;
                if (RustBlur.ready() && probeLens()) lensState = 1;
                if (lensState != 1) Log.w("LiquidRustPixels", "Lens parity unavailable; using Java");
            }
            boolean success = lensState == 1 && lensNative(src,dst,g.alpha,g.offsetX,g.offsetY,g.dispersion,
                g.specular,sw,sh,g.width,g.height,x,y,lw,lh,strength,glass);
            if (success && !lensLogged) {
                lensLogged = true;
                Log.i("LiquidRustPixels", "Lens backend=Rust; first image processed");
            }
            return success;
        } catch (LinkageError | RuntimeException | OutOfMemoryError error) {
            lensState = 2;
            Log.w("LiquidRustPixels", "Lens using Java", error);
            return false;
        }
    }

    private static boolean size(int w, int h, int length) {
        return w > 0 && h > 0 && (long)w*h <= MAX_PIXELS && (long)w*h <= length;
    }
    private static int[] pattern(int count) {
        int[] data = new int[count];
        for (int i=0;i<count;i++) data[i] = (i * 1103515245 + 12345);
        return data;
    }
    private static boolean probeIcon() {
        for (int mode=0;mode<32;mode++) {
            int w=13,h=9;
            int[] java=pattern(w*h);
            if ((mode&16)!=0) for (int i=0;i<java.length;i++) java[i] |= 0xff000000;
            java[0]=0;
            int[] rust=java.clone();
            IconMaterialRenderer$Plate p=new IconMaterialRenderer$Plate((mode&4)!=0,70,120,180);
            int color=(mode&8)!=0 ? 0xff589be1 : 0;
            FastPixelOps.recolourJava(java,w,h,color,(mode&1)!=0,(mode&2)!=0,p);
            if (!recolourNative(rust,w,h,color,(mode&1)!=0,(mode&2)!=0,p.found,p.red,p.green,p.blue)
                    || !Arrays.equals(java,rust)) return false;
        }
        return true;
    }
    private static boolean probeLens() {
        int[] src=pattern(23*17);
        for (int mode=0;mode<8;mode++) {
            LiquidLensKernel$Geometry g=new LiquidLensKernel$Geometry(13,9,20,14,
                (mode&1)==0 ? 0 : 5,3,0,0);
            // Also fill here: the compile stub constructor does not populate geometry.
            FastPixelOps.fillGeometry(g);
            int[] java=new int[117], rust=new int[117];
            float x=(mode&2)==0 ? -4 : 16, y=(mode&2)==0 ? -3 : 10;
            float strength=(mode&4)==0 ? 0 : 7, glass=(mode&1)==0 ? 0 : 1;
            FastPixelOps.lensJava(src,java,g,23,17,x,y,20,14,strength,glass);
            if (!lensNative(src,rust,g.alpha,g.offsetX,g.offsetY,g.dispersion,g.specular,
                    23,17,13,9,x,y,20,14,strength,glass) || !Arrays.equals(java,rust)) return false;
        }
        return true;
    }
    private static native boolean recolourNative(int[] pixels,int w,int h,int color,
        boolean transparent,boolean glass,boolean found,int red,int green,int blue);
    private static native boolean lensNative(int[] src,int[] dst,int[] alpha,float[] ox,float[] oy,
        float[] dispersion,float[] specular,int sw,int sh,int w,int h,
        float x,float y,float lw,float lh,float strength,float glass);
}
