package local.launcherfix;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import com.gathin.tvhome.MainActivity;
import com.gathin.tvhome.PaperTransition;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** One persistent compositor for still and moving wallpaper sources. All GL is on worker. */
public final class WallpaperRenderer implements TextureView.SurfaceTextureListener {
    public interface Listener {
        void onReady(long token);
        void onComplete(long token);
        void onError(long token, Exception error);
    }

    private static final String TAG = "WallpaperRenderer";
    private static final float[] FULL_CROP = {0f, 0f, 1f, 1f};
    private static final String VERT = "attribute vec2 pos;\nvarying vec2 v_uv;\n"
        + "void main(){v_uv=vec2(pos.x,1.0-pos.y);"
        + "gl_Position=vec4(pos*2.0-1.0,0.0,1.0);}\n";
    // Kept identical to PaperShaders. Its public bridges supply all existing effect bodies.
    private static final String HEAD = "precision highp float;\nvarying vec2 v_uv;\n"
        + "uniform sampler2D u_tex_old;\nuniform sampler2D u_tex_new;\n"
        + "uniform float u_progress;\nuniform sampler2D u_field;\n"
        + "uniform vec4 u_crop_old;\nuniform vec4 u_crop_new;\n"
        + "vec4 sampleOld(vec2 uv){return texture2D(u_tex_old,u_crop_old.xy+uv*u_crop_old.zw);}\n"
        + "vec4 sampleNew(vec2 uv){return texture2D(u_tex_new,u_crop_new.xy+uv*u_crop_new.zw);}\n";
    private static final String COPY = "precision mediump float;\nvarying vec2 v_uv;\n"
        + "uniform sampler2D u_tex_old;\nuniform vec4 u_crop_old;\nuniform float u_flip;\n"
        + "void main(){vec2 uv=u_crop_old.xy+v_uv*u_crop_old.zw;"
        + "uv.y=mix(uv.y,1.0-uv.y,u_flip);gl_FragColor=texture2D(u_tex_old,uv);}\n";
    private static final String FADE = HEAD
        + "void main(){gl_FragColor=mix(sampleOld(v_uv),sampleNew(v_uv),u_progress);}\n";

    private final android.content.Context context;
    private final TextureView output;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("WallpaperGL");
    private final HandlerThread codecThread = new HandlerThread("WallpaperCodec");
    private final Handler worker;
    private final Handler codec;
    private final FloatBuffer quad = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer();

    // UI writes these before queueing work. They invalidate already-posted callbacks immediately.
    private volatile boolean closed;
    private volatile boolean requestedResumed = true;
    private volatile long requestSerial;
    private volatile long requestToken;
    private volatile SurfaceTexture visibleSurface;
    private volatile Presentation presentation;
    private long uiUpdates;

    // The remainder belongs exclusively to worker, except Source.completed and Presentation.
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface pbuffer = EGL14.EGL_NO_SURFACE;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private EGLConfig config;
    private Surface windowSurface;
    private SurfaceTexture windowTexture;
    private int viewWidth = 1280, viewHeight = 720;
    private int effectWidth = 1280, effectHeight = 720;
    private int windowWidth, windowHeight;
    private final int[] surfaceSize = new int[1];
    private boolean active;
    private boolean frameQueued;
    private long inactiveSince;
    private Exception broken;
    private Source current;
    private Source incoming;
    private boolean incomingAnimate;
    private String incomingEffect;
    private Effect transition;
    private long transitionStart;
    private Program copyProgram;

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            frameQueued = false;
            if (closed || broken != null || !active) return;
            try {
                render();
            } catch (Exception error) {
                failRenderer(error);
            }
        }
    };

    public WallpaperRenderer(MainActivity activity, TextureView output, Listener listener) {
        this.context = activity.getApplicationContext();
        this.output = output;
        this.listener = listener;
        quad.put(new float[]{0, 0, 1, 0, 0, 1, 1, 1}).position(0);
        thread.start();
        worker = new Handler(thread.getLooper());
        codecThread.start();
        codec = new Handler(codecThread.getLooper());
        output.setSurfaceTextureListener(this);
        if (output.isAvailable() && output.getSurfaceTexture() != null) {
            onSurfaceTextureAvailable(output.getSurfaceTexture(), output.getWidth(), output.getHeight());
        }
    }

    /** UI entry point. The file is pinned by WallpaperVideo before asynchronous decoding. */
    public void video(final File file, final long token, final boolean animate, final String effect) {
        if (closed) return;
        final long serial = ++requestSerial;
        requestToken = token;
        presentation = null;
        worker.post(new Runnable() {
            @Override public void run() {
                if (closed) return;
                try {
                    ensureEgl();
                    dropIncoming();
                    // A stable video may own a prewarmed loop decoder: free it BEFORE the new one.
                    if (current != null && current.video != null) current.tick(false);
                    Source source = new Source(serial, token);
                    source.video = new WallpaperVideo(context, file, worker, codec, new Runnable() {
                        @Override public void run() { scheduleFrame(0); }
                    });
                    source.video.setResumed(active);
                    incoming = source;
                    incomingAnimate = animate;
                    incomingEffect = effect;
                    if (source.error() != null) rejectIncoming(source.error());
                    scheduleFrame(0);
                } catch (Exception error) {
                    postError(serial, token, error);
                }
            }
        });
    }

    /** Bitmap is borrowed only until upload; this class never recycles caller bitmaps. */
    public void image(final Bitmap bitmap, final long token, final boolean animate, final String effect) {
        if (closed) return;
        final long serial = ++requestSerial;
        requestToken = token;
        presentation = null;
        worker.post(new Runnable() {
            @Override public void run() {
                if (closed) return;
                Source source = null;
                try {
                    ensureEgl();
                    if (bitmap == null || bitmap.isRecycled()) {
                        throw new IllegalArgumentException("Wallpaper bitmap is unavailable");
                    }
                    dropIncoming();
                    source = new Source(serial, token);
                    source.width = bitmap.getWidth();
                    source.height = bitmap.getHeight();
                    source.texture = texture(GLES20.GL_LINEAR);
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source.texture);
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
                    checkGl("upload wallpaper");
                    incoming = source;
                    incomingAnimate = animate;
                    incomingEffect = effect;
                    signalReady(source);
                    // Promote the initial image synchronously on worker. A video queued right
                    // after this command must retain this seed, even before the first draw.
                    if (current == null || !animate) promoteIncoming();
                    scheduleFrame(0);
                } catch (Exception error) {
                    if (source != null && source != incoming && source != current) source.release();
                    postError(serial, token, error);
                }
            }
        });
    }

    public void cancelPending() {
        if (closed) return;
        ++requestSerial;
        presentation = null;
        worker.post(new Runnable() {
            @Override public void run() {
                if (closed) return;
                dropIncoming();
                scheduleFrame(0);
            }
        });
    }

    public void setResumed(final boolean resumed) {
        if (closed) return;
        requestedResumed = resumed;
        worker.post(new Runnable() {
            @Override public void run() {
                if (closed) return;
                try {
                    updateActive();
                    if (active) scheduleFrame(0);
                } catch (Exception error) {
                    failRenderer(error);
                }
            }
        });
    }

    public void close() {
        if (closed) return;
        closed = true;
        ++requestSerial;
        presentation = null;
        worker.post(new Runnable() {
            @Override public void run() {
                worker.removeCallbacks(frame);
                frameQueued = false;
                active = false;
                try {
                    if (display != EGL14.EGL_NO_DISPLAY && pbuffer != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, eglContext);
                    }
                    dropIncoming();
                    if (current != null) current.release();
                    current = null;
                    if (copyProgram != null) copyProgram.release();
                    copyProgram = null;
                } finally {
                    // Keep EGL alive until every producer has stopped and its input texture
                    // cleanup has returned to GL. Never detach a live decoder's consumer first.
                    codec.post(() -> worker.post(() -> {
                        try { destroyEgl(); }
                        finally {
                            ui.post(() -> {
                                if (output.getSurfaceTextureListener() == WallpaperRenderer.this)
                                    output.setSurfaceTextureListener(null);
                            });
                            thread.quitSafely();
                            codecThread.quitSafely();
                        }
                    }));
                }
            }
        });
    }

    @Override public void onSurfaceTextureAvailable(final SurfaceTexture surface, final int width, final int height) {
        if (closed) return;
        visibleSurface = surface;
        worker.post(new Runnable() {
            @Override public void run() {
                if (closed || visibleSurface != surface) return;
                try {
                    ensureEgl();
                    detachWindow();
                    configureSize(width, height);
                    windowSurface = new Surface(surface);
                    windowTexture = surface;
                    window = EGL14.eglCreateWindowSurface(display, config, windowSurface,
                        new int[]{EGL14.EGL_NONE}, 0);
                    if (window == EGL14.EGL_NO_SURFACE) throw eglFailure("create wallpaper window");
                    makeCurrent(window);
                    EGL14.eglSwapInterval(display, 1);
                    updateActive();
                    scheduleFrame(0);
                } catch (Exception error) {
                    failRenderer(error);
                }
            }
        });
    }

    @Override public void onSurfaceTextureSizeChanged(final SurfaceTexture surface, final int width, final int height) {
        worker.post(new Runnable() {
            @Override public void run() {
                if (closed || windowTexture != surface) return;
                configureSize(width, height);
                scheduleFrame(0);
            }
        });
    }

    @Override public boolean onSurfaceTextureDestroyed(final SurfaceTexture surface) {
        if (visibleSurface == surface) visibleSurface = null;
        Presentation old = presentation;
        if (old != null && old.surface == surface) presentation = null;
        // false gives this listener ownership. The GL queue destroys EGL's window first.
        boolean accepted = worker.post(new Runnable() {
            @Override public void run() {
                try {
                    if (windowTexture == surface) detachWindow();
                } catch (Exception error) {
                    failRenderer(error);
                } finally {
                    surface.release();
                }
            }
        });
        return !accepted; // If the worker already quit, EGL has already disconnected.
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        ++uiUpdates;
        checkPresented(surface);
    }

    private void checkPresented(SurfaceTexture surface) {
        Presentation pending = presentation;
        if (closed || !requestedResumed || pending == null || !pending.submitted
            || pending.surface != surface || visibleSurface != surface
            || pending.source.serial != requestSerial || pending.source.completed) return;
        boolean displayed = pending.timestampSupported
            ? surface.getTimestamp() >= pending.timestamp
            : pending.fallbackAfter > 0 && uiUpdates >= pending.fallbackAfter;
        if (!displayed) return;
        pending.source.completed = true;
        presentation = null;
        if (listener != null) listener.onComplete(pending.source.token);
    }

    private void ensureEgl() {
        if (broken != null) throw new IllegalStateException("Wallpaper compositor unavailable", broken);
        if (display != EGL14.EGL_NO_DISPLAY) return;
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) throw eglFailure("get EGL display");
            int[] versions = new int[2];
            if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) throw eglFailure("initialize EGL");
            int[] attrs = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE};
            EGLConfig[] choices = new EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, attrs, 0, choices, 0, 1, count, 0) || count[0] == 0) {
                throw eglFailure("choose EGL config");
            }
            config = choices[0];
            eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            if (eglContext == EGL14.EGL_NO_CONTEXT) throw eglFailure("create EGL context");
            pbuffer = EGL14.eglCreatePbufferSurface(display, config,
                new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            if (pbuffer == EGL14.EGL_NO_SURFACE) throw eglFailure("create retained EGL surface");
            makeCurrent(pbuffer);
            copyProgram = new Program(COPY);
        } catch (RuntimeException error) {
            broken = error;
            destroyEgl();
            throw error;
        }
    }

    private void configureSize(int width, int height) {
        viewWidth = Math.max(1, width);
        viewHeight = Math.max(1, height);
        float scale = Math.min(1f, Math.min(1280f / viewWidth, 720f / viewHeight));
        effectWidth = Math.max(1, Math.round(viewWidth * scale));
        effectHeight = Math.max(1, Math.round(viewHeight * scale));
        // TextureView owns its window buffer size. The quality cap belongs only to our FBOs.
    }

    private void queryWindowSize() {
        if (!EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, surfaceSize, 0)
                || surfaceSize[0] < 1) throw eglFailure("query wallpaper width");
        windowWidth = surfaceSize[0];
        if (!EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, surfaceSize, 0)
                || surfaceSize[0] < 1) throw eglFailure("query wallpaper height");
        windowHeight = surfaceSize[0];
    }

    private void updateActive() {
        boolean next = !closed && broken == null && requestedResumed
            && window != EGL14.EGL_NO_SURFACE && windowTexture == visibleSurface;
        if (next == active) return;
        long now = System.nanoTime();
        active = next;
        if (!next) {
            inactiveSince = now;
            worker.removeCallbacks(frame);
            frameQueued = false;
        } else {
            if (transitionStart != 0 && inactiveSince != 0) transitionStart += now - inactiveSince;
            inactiveSince = 0;
        }
        if (current != null && current.video != null) current.video.setResumed(next);
        if (incoming != null && incoming.video != null) incoming.video.setResumed(next);
    }

    private void detachWindow() {
        if (display != EGL14.EGL_NO_DISPLAY && pbuffer != EGL14.EGL_NO_SURFACE) makeCurrent(pbuffer);
        if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window);
        window = EGL14.EGL_NO_SURFACE;
        if (windowSurface != null) windowSurface.release();
        windowSurface = null;
        windowTexture = null;
        presentation = null;
        updateActive();
    }

    private void makeCurrent(EGLSurface surface) {
        if (!EGL14.eglMakeCurrent(display, surface, surface, eglContext)) throw eglFailure("bind EGL surface");
    }

    private void scheduleFrame(long delay) {
        if (closed || broken != null || !active) return;
        if (frameQueued) {
            if (delay != 0) return;
            worker.removeCallbacks(frame);
        }
        frameQueued = true;
        worker.postDelayed(frame, delay);
    }

    private void render() {
        long frameStarted = System.nanoTime();
        if (!requestedResumed || windowTexture != visibleSurface) { updateActive(); return; }
        makeCurrent(window);
        queryWindowSize();
        // Both sources remain live throughout preparation and the effect, without loop standbys.
        boolean allowLoop = incoming == null;
        if (current != null) current.tick(allowLoop);
        if (incoming != null) incoming.tick(false);
        if (incoming != null && incoming.error() != null) rejectIncoming(incoming.error());
        if (current != null && incoming == null && current.error() != null) {
            Exception error = current.error();
            current.failed = true;
            // A newer request may already be queued on worker. Failure of the old video
            // must not masquerade as failure of that not-yet-processed selection.
            postError(current.serial, current.token, error);
            return;
        }
        if (incoming != null && incoming.ready()) {
            signalReady(incoming);
            if (!incomingAnimate || current == null || !current.ready()) {
                promoteIncoming();
            } else if (transition == null) {
                transition = new Effect(WallpaperTransitionCrop.resolveEffect(incomingEffect));
                transitionStart = System.nanoTime();
            }
        }
        if (current == null || !current.ready()) {
            return;
        }

        float progress = transition == null ? 0f
            : Math.min(1f, (System.nanoTime() - transitionStart) / (float) transition.durationNs);
        boolean finished = transition != null && progress >= 1f;
        Source displayed = finished ? incoming : current;
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, windowWidth, windowHeight);
        if (transition == null || progress <= 0f || finished) {
            float[] bounds = crop(displayed);
            if (displayed.video == null || !displayed.video.drawDirect(bounds))
                drawCopy(displayed.texture(), bounds, false);
        } else {
            transition.draw(current, incoming, progress);
        }

        final Presentation pending;
        boolean completeFrame = transition == null || finished;
        Presentation previous = presentation;
        if (completeFrame && displayed.serial == requestSerial && !displayed.completed
            && (previous == null || previous.source != displayed || previous.surface != windowTexture)) {
            pending = new Presentation(displayed, windowTexture, System.nanoTime());
            presentation = pending;
        } else {
            pending = null;
        }
        // The timestamp is observed on this SAME SurfaceTexture, after the buffer is latched.
        long timestamp = pending != null ? pending.timestamp : System.nanoTime();
        boolean timestampSet = EGLExt.eglPresentationTimeANDROID(display, window, timestamp);
        if (!EGL14.eglSwapBuffers(display, window)) throw eglFailure("present wallpaper");
        if (finished) promoteIncoming();
        if (pending != null) {
            pending.timestampSupported = timestampSet;
            pending.submitted = true;
            ui.post(new Runnable() {
                @Override public void run() {
                    if (presentation != pending || closed) return;
                    if (!pending.timestampSupported) {
                        // A driver without presentation timestamps still displays repeated final
                        // frames. Drain queued earlier buffers before acknowledging the handoff.
                        pending.fallbackAfter = uiUpdates + 3;
                    }
                    checkPresented(pending.surface);
                }
            });
        }
        // Decoder frame callbacks (and its bounded loop wake) drive steady video. Only the
        // effect/presentation fence needs a display-rate clock. eglSwapBuffers may itself
        // wait for vsync, so count that elapsed time rather than adding another 16 ms.
        if (transition != null || presentation != null) {
            long remaining = 16666667L - (System.nanoTime() - frameStarted);
            scheduleFrame(Math.max(0L, remaining / 1000000L));
        }
    }

    private float[] crop(Source source) {
        int width = source.width(), height = source.height();
        if (source.crop == null || source.cropWidth != width || source.cropHeight != height
                || source.cropViewWidth != viewWidth || source.cropViewHeight != viewHeight) {
            source.crop = WallpaperTransitionCrop.bounds(width, height, viewWidth, viewHeight);
            source.cropWidth = width;
            source.cropHeight = height;
            source.cropViewWidth = viewWidth;
            source.cropViewHeight = viewHeight;
        }
        return source.crop;
    }

    private void drawCopy(int texture, float[] crop, boolean flip) {
        copyProgram.use();
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(copyProgram.oldSampler, 0);
        GLES20.glUniform4fv(copyProgram.oldCrop, 1, crop, 0);
        GLES20.glUniform1f(copyProgram.flip, flip ? 1f : 0f);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void promoteIncoming() {
        if (incoming == null) return;
        Source previous = current;
        current = incoming;
        incoming = null;
        releaseEffect();
        if (previous != null) previous.release();
    }

    private void dropIncoming() {
        presentation = null;
        releaseEffect();
        if (incoming != null) incoming.release();
        incoming = null;
    }

    private void releaseEffect() {
        if (transition != null) transition.release();
        transition = null;
        transitionStart = 0;
    }

    private void rejectIncoming(Exception error) {
        if (incoming == null) return;
        long serial = incoming.serial, token = incoming.token;
        dropIncoming();
        postError(serial, token, error);
    }

    private void signalReady(final Source source) {
        if (source.readySignalled) return;
        source.readySignalled = true;
        ui.post(new Runnable() {
            @Override public void run() {
                if (!closed && source.serial == requestSerial && listener != null) listener.onReady(source.token);
            }
        });
    }

    private void postError(final long serial, final long token, final Exception error) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (!closed && serial == requestSerial && listener != null) listener.onError(token, error);
            }
        });
    }

    private void failRenderer(Exception error) {
        broken = error;
        presentation = null;
        try { updateActive(); } catch (RuntimeException ignored) {}
        postError(requestSerial, requestToken, error);
    }

    private void destroyEgl() {
        if (display == EGL14.EGL_NO_DISPLAY) return;
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window);
        if (windowSurface != null) windowSurface.release();
        if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer);
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext);
        EGL14.eglReleaseThread();
        EGL14.eglTerminate(display);
        display = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        window = pbuffer = EGL14.EGL_NO_SURFACE;
        windowSurface = null;
        windowTexture = null;
    }

    private static IllegalStateException eglFailure(String operation) {
        return new IllegalStateException(operation + ": EGL 0x" + Integer.toHexString(EGL14.eglGetError()));
    }

    private static void checkGl(String operation) {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) throw new IllegalStateException(operation + ": GL 0x" + Integer.toHexString(error));
    }

    private static int texture(int filter) {
        int[] names = new int[1];
        GLES20.glGenTextures(1, names, 0);
        if (names[0] == 0) throw new IllegalStateException("Cannot allocate wallpaper texture");
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, names[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return names[0];
    }

    private static void deleteTexture(int texture) {
        if (texture != 0) GLES20.glDeleteTextures(1, new int[]{texture}, 0);
    }

    private static final class Source {
        final long serial, token;
        WallpaperVideo video;
        int texture, width, height;
        float[] crop;
        int cropWidth, cropHeight, cropViewWidth, cropViewHeight;
        boolean readySignalled, failed;
        Exception tickError;
        volatile boolean completed;
        Source(long serial, long token) { this.serial = serial; this.token = token; }
        boolean ready() { return video != null ? video.ready() : texture != 0; }
        int texture() { return video != null ? video.texture() : texture; }
        int width() { return video != null ? video.width() : width; }
        int height() { return video != null ? video.height() : height; }
        Exception error() { return tickError != null ? tickError : video != null ? video.error() : null; }
        void tick(boolean allowLoop) {
            if (video == null || failed || tickError != null) return;
            try { video.tick(allowLoop); } catch (Exception error) { tickError = error; }
        }
        void release() {
            if (video != null) { video.release(); video = null; }
            deleteTexture(texture);
            texture = 0;
        }
    }

    private static final class Presentation {
        final Source source;
        final SurfaceTexture surface;
        final long timestamp;
        volatile boolean submitted, timestampSupported;
        volatile long fallbackAfter;
        Presentation(Source source, SurfaceTexture surface, long timestamp) {
            this.source = source; this.surface = surface; this.timestamp = timestamp;
        }
    }

    private final class Program {
        int id;
        final int position, oldSampler, newSampler, fieldSampler, progress, oldCrop, newCrop, flip;
        Program(String fragment) {
            int vertex = 0, pixel = 0;
            try {
                vertex = shader(GLES20.GL_VERTEX_SHADER, VERT);
                pixel = shader(GLES20.GL_FRAGMENT_SHADER, fragment);
                id = GLES20.glCreateProgram();
                GLES20.glAttachShader(id, vertex);
                GLES20.glAttachShader(id, pixel);
                GLES20.glLinkProgram(id);
                int[] linked = new int[1];
                GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, linked, 0);
                if (linked[0] == 0) throw new IllegalStateException("Wallpaper shader link: " + GLES20.glGetProgramInfoLog(id));
            } catch (RuntimeException error) {
                release();
                throw error;
            } finally {
                if (vertex != 0) GLES20.glDeleteShader(vertex);
                if (pixel != 0) GLES20.glDeleteShader(pixel);
            }
            position = GLES20.glGetAttribLocation(id, "pos");
            oldSampler = GLES20.glGetUniformLocation(id, "u_tex_old");
            newSampler = GLES20.glGetUniformLocation(id, "u_tex_new");
            fieldSampler = GLES20.glGetUniformLocation(id, "u_field");
            progress = GLES20.glGetUniformLocation(id, "u_progress");
            oldCrop = GLES20.glGetUniformLocation(id, "u_crop_old");
            newCrop = GLES20.glGetUniformLocation(id, "u_crop_new");
            flip = GLES20.glGetUniformLocation(id, "u_flip");
        }
        void use() {
            GLES20.glUseProgram(id);
            quad.position(0);
            GLES20.glEnableVertexAttribArray(position);
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad);
        }
        void release() { if (id != 0) GLES20.glDeleteProgram(id); id = 0; }
    }

    private static int shader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String error = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("Wallpaper shader compile: " + error);
        }
        return shader;
    }

    private final class Target {
        final int width, height;
        int texture, framebuffer;
        Target(int width, int height, int filter) {
            this.width = width; this.height = height;
            try {
                texture = texture(filter);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height,
                    0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
                int[] names = new int[1];
                GLES20.glGenFramebuffers(1, names, 0);
                framebuffer = names[0];
                bind();
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, texture, 0);
                if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("Incomplete wallpaper framebuffer");
                }
            } catch (RuntimeException error) {
                release();
                throw error;
            } finally {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            }
        }
        void bind() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            GLES20.glViewport(0, 0, width, height);
        }
        void release() {
            deleteTexture(texture);
            texture = 0;
            if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, new int[]{framebuffer}, 0);
            framebuffer = 0;
        }
    }

    private final class Effect {
        final long durationNs;
        Program program;
        Target field, lowResolution;
        Effect(String key) {
            durationNs = WallpaperMotion.durationNanos(key);
            try {
                program = new Program(HEAD + PaperTransition.shaderBody(key));
                String prep = PaperTransition.shaderPrep(key);
                // Every effect renders offscreen at its existing budget; only the cheap copy
                // uses the actual EGL window dimensions, including 1080p/4K TextureViews.
                int width = effectWidth, height = effectHeight;
                if ("smoke".equals(key)) {
                    float scale = Math.min(1f, Math.min(480f / width, 270f / height));
                    width = Math.max(1, Math.round(width * scale));
                    height = Math.max(1, Math.round(height * scale));
                }
                lowResolution = new Target(width, height, GLES20.GL_LINEAR);
                if (prep != null) {
                    boolean nearest = "heat-melt".equals(key) || "voronoi-shatter".equals(key);
                    field = new Target(width, "heat-melt".equals(key) ? 1 : height,
                        nearest ? GLES20.GL_NEAREST : GLES20.GL_LINEAR);
                    Program prepare = new Program("precision highp float;\nvarying vec2 v_uv;\n" + prep);
                    try {
                        field.bind();
                        prepare.use();
                        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                    } finally {
                        prepare.release();
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                    }
                }
                program.use();
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program.id, "u_aspect"), (float) viewWidth / viewHeight);
                // Video has no CPU bitmap palette. These are the same native shader defaults.
                GLES20.glUniform3f(GLES20.glGetUniformLocation(program.id, "u_ember"), 1f, .52f, .18f);
                GLES20.glUniform3f(GLES20.glGetUniformLocation(program.id, "u_ink"), .96f, .95f, .92f);
            } catch (RuntimeException error) {
                Log.w(TAG, "Effect " + key + " uses live crossfade fallback", error);
                release();
                program = new Program(FADE);
            }
        }
        void draw(Source from, Source to, float amount) {
            // Lazy video conversion changes GL state; finish it before binding the effect.
            int oldTexture = from.texture(), newTexture = to.texture();
            if (lowResolution != null) lowResolution.bind();
            program.use();
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, oldTexture);
            GLES20.glUniform1i(program.oldSampler, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, newTexture);
            GLES20.glUniform1i(program.newSampler, 1);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, field == null ? 0 : field.texture);
            GLES20.glUniform1i(program.fieldSampler, 2);
            GLES20.glUniform4fv(program.oldCrop, 1, crop(from), 0);
            GLES20.glUniform4fv(program.newCrop, 1, crop(to), 0);
            GLES20.glUniform1f(program.progress, amount);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            if (lowResolution != null) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                GLES20.glViewport(0, 0, windowWidth, windowHeight);
                drawCopy(lowResolution.texture, FULL_CROP, true);
            }
        }
        void release() {
            if (program != null) program.release();
            if (field != null) field.release();
            if (lowResolution != null) lowResolution.release();
            program = null; field = lowResolution = null;
        }
    }
}
