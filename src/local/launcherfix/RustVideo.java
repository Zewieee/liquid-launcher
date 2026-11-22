package local.launcherfix;

import android.util.Log;

/** Batch the existing video GL draw on its current EGL thread; Java retains ownership. */
final class RustVideo {
    private static volatile int state;
    private static volatile boolean logged;
    private RustVideo() {}

    private static synchronized boolean ready() {
        if (state == 0) state = RustBlur.ready() ? 1 : 2;
        return state == 1;
    }
    static boolean draw(int program,int position,int source,int next,int sourceMatrix,int nextMatrix,
            int blendUniform,int cropUniform,int flipUniform,int texture,int nextTexture,
            float[] matrix,float[] secondMatrix,float[] crop,float blend,float flip) {
        if (state == 2 || (state == 0 && !ready())) return false;
        try {
            boolean success=drawNative(program,position,source,next,sourceMatrix,nextMatrix,
                blendUniform,cropUniform,flipUniform,texture,nextTexture,
                matrix,secondMatrix,crop,blend,flip,!logged);
            if (!success) {
                state=2;
                Log.w("LiquidRustVideo","Native video draw unavailable; using Java");
            } else if (!logged) {
                logged=true;
                Log.i("LiquidRustVideo","Rust video draw active");
            }
            return success;
        } catch (LinkageError | RuntimeException error) {
            state=2;
            Log.w("LiquidRustVideo","Video draw using Java",error);
            return false;
        }
    }
    private static native boolean drawNative(int program,int position,int source,int next,
        int sourceMatrix,int nextMatrix,int blendUniform,int cropUniform,int flipUniform,
        int texture,int nextTexture,float[] matrix,float[] secondMatrix,float[] crop,
        float blend,float flip,boolean checkError);
}
