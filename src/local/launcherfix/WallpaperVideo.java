package local.launcherfix;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.Surface;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** GL owns textures and selection; a separate serial queue owns every MediaPlayer call. */
public final class WallpaperVideo {
    private static final int PREWARM_MS = 1500, BLEND_MS = 220, TAIL_MS = 250;
    private static final String VERTEX = "attribute vec2 pos; varying vec2 uv;"
        + "void main(){uv=pos;gl_Position=vec4(pos*2.0-1.0,0.0,1.0);}";
    private static final String FRAGMENT = "#extension GL_OES_EGL_image_external : require\n"
        + "precision mediump float;varying vec2 uv;uniform samplerExternalOES source;"
        + "uniform samplerExternalOES nextSource;uniform mat4 sourceMatrix;"
        + "uniform mat4 nextMatrix;uniform float blend;"
        + "uniform vec4 crop;uniform float flip;"
        + "void main(){vec2 q=crop.xy+vec2(uv.x,mix(uv.y,1.0-uv.y,flip))*crop.zw;"
        + "vec4 p=vec4(q.x,1.0-q.y,0.0,1.0);"
        + "vec4 a=texture2D(source,(sourceMatrix*p).xy);"
        + "if(blend<=0.0){gl_FragColor=vec4(a.rgb,1.0);return;}"
        + "vec4 b=texture2D(nextSource,(nextMatrix*p).xy);gl_FragColor=vec4(mix(a.rgb,b.rgb,blend),1.0);}";

    private final Context context;
    private final Handler handler;
    private final Handler codec;
    private final Runnable invalidate;
    private final FloatBuffer vertices = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final int[] previousFramebuffer = new int[1], previousViewport = new int[4];
    private final Runnable wake;
    private ParcelFileDescriptor fileDescriptor;
    private Decoder primary, standby;
    private Exception failure;
    private int program, output, framebuffer, outputWidth, outputHeight;
    private int positionAttribute, sourceUniform, nextUniform, sourceMatrixUniform, nextMatrixUniform, blendUniform;
    private int cropUniform, flipUniform;
    private static final float[] FULL_CROP = {0f, 0f, 1f, 1f};
    private int savedPosition, blendDuration = BLEND_MS;
    private long blendStarted;
    private float blendAmount;
    private boolean textureDirty = true;
    private boolean resumed = true, released, outputValid, standbyDisabled;

    public WallpaperVideo(Context context, File file, Handler handler, Handler codec, Runnable invalidate) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.codec = codec;
        this.invalidate = invalidate;
        android.app.ActivityManager memory = (android.app.ActivityManager)
            this.context.getSystemService(Context.ACTIVITY_SERVICE);
        // Low-RAM TV codecs can retain paused standby sessions; use the player's native loop.
        standbyDisabled = memory != null && memory.isLowRamDevice();
        if (standbyDisabled) android.util.Log.i("WallpaperVideo", "Single-decoder native loop on low-RAM TV");
        wake = () -> { if (!released && resumed) invalidate.run(); };
        vertices.put(new float[]{0, 0, 1, 0, 0, 1, 1, 1}).position(0);
        try {
            // Keep the inode, not just its filename: an import atomically replaces that name.
            fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            program = createProgram();
            positionAttribute = GLES20.glGetAttribLocation(program, "pos");
            sourceUniform = GLES20.glGetUniformLocation(program, "source");
            nextUniform = GLES20.glGetUniformLocation(program, "nextSource");
            sourceMatrixUniform = GLES20.glGetUniformLocation(program, "sourceMatrix");
            nextMatrixUniform = GLES20.glGetUniformLocation(program, "nextMatrix");
            blendUniform = GLES20.glGetUniformLocation(program, "blend");
            cropUniform = GLES20.glGetUniformLocation(program, "crop");
            flipUniform = GLES20.glGetUniformLocation(program, "flip");
            primary = new Decoder(false, 0);
        } catch (IOException | RuntimeException error) { fail(error); }
    }

    private boolean liveFrame() {
        return primary != null && primary.hasFrame && primary.width > 0 && primary.height > 0;
    }
    public boolean ready() { return !released && failure == null && (liveFrame() || outputValid); }
    public int texture() {
        if (liveFrame() && (textureDirty || !outputValid)) draw(blendAmount);
        return output;
    }
    public int width() { return liveFrame() ? primary.width : outputWidth; }
    public int height() { return liveFrame() ? primary.height : outputHeight; }
    public Exception error() { return failure; }

    /** Bypass the intermediate 2D buffer during ordinary playback. */
    public boolean drawDirect(float[] crop) {
        if (!liveFrame() || blendStarted != 0) return false;
        drawInput(0f, crop, 1f);
        return true;
    }

    public void setResumed(boolean value) {
        if (released || resumed == value) return;
        resumed = value;
        if (!value) {
            handler.removeCallbacks(wake);
            // Preserve the latest image before detaching decoder textures on pause.
            try { texture(); }
            finally {
                if (primary != null) savedPosition = primary.position();
                releaseInputs();
            }
        } else if (failure == null) {
            try { primary = new Decoder(false, savedPosition); }
            catch (IOException | RuntimeException error) { fail(error); }
            invalidate.run();
        }
    }

    public void tick(boolean allowLoop) {
        if (released || !resumed || failure != null || primary == null) return;
        try {
            long now = SystemClock.uptimeMillis();
            boolean changed = false;
            if (!allowLoop && standby != null) {
                if (blendStarted != 0) {
                    if (now - blendStarted >= blendDuration / 2) promote();
                    else releaseStandby();
                    changed = true;
                } else releaseStandby();
            }
            changed |= primary.latch();
            if (standby != null) changed |= standby.latch();
            if (failure != null || primary == null) return;
            if (!primary.hasFrame || primary.width < 1 || primary.height < 1) return;
            if (allowLoop && !standbyDisabled && primary.duration > 1000) {
                int previousPosition = primary.observedPosition;
                int position = primary.position();
                primary.observedPosition = position;
                int remaining = primary.duration - position;
                boolean wrapped = previousPosition > position + primary.duration / 2;
                if (wrapped && standby != null) {
                    if (blendStarted != 0) { promote(); changed = true; }
                    else {
                        if (!standby.hasFrame) standbyDisabled = true;
                        releaseStandby();
                    }
                }
                if (standby == null && !standbyDisabled && remaining > TAIL_MS
                        && remaining <= Math.min(PREWARM_MS, primary.duration / 2)) {
                    try { standby = new Decoder(true, 0); }
                    catch (IOException | RuntimeException error) {
                        standbyDisabled = true;
                        releaseStandby();
                    }
                }
                if (standby != null && standby.hasFrame && blendStarted == 0
                        && remaining > 0 && remaining <= TAIL_MS) {
                    standby.playForLoop();
                }
            }
            float blend = blendStarted == 0 ? 0f
                : Math.min(1f, (now - blendStarted) / (float) blendDuration);
            if (blend >= 1f && standby != null) {
                promote();
                blend = 0f;
                changed = true;
            }
            blendAmount = blend * blend * (3f - 2f * blend);
            textureDirty |= changed || blendStarted != 0;
            handler.removeCallbacks(wake);
            handler.postDelayed(wake, blendStarted != 0 ? 16 : 100);
        } catch (RuntimeException error) { fail(error); }
    }

    public void release() {
        if (released) return;
        released = true;
        handler.removeCallbacks(wake);
        releaseInputs();
        if (fileDescriptor != null) {
            ParcelFileDescriptor descriptor = fileDescriptor;
            codec.post(() -> { try { descriptor.close(); } catch (IOException ignored) {} });
            fileDescriptor = null;
        }
        if (output != 0) GLES20.glDeleteTextures(1, new int[]{output}, 0);
        if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, new int[]{framebuffer}, 0);
        if (program != 0) GLES20.glDeleteProgram(program);
        output = framebuffer = program = 0;
        outputValid = false;
    }

    private void fail(Exception error) {
        if (released || failure != null) return;
        failure = error;
        handler.removeCallbacks(wake);
        releaseInputs();
        invalidate.run();
    }

    private void releaseInputs() {
        releaseStandby();
        Decoder previous = primary;
        primary = null;
        if (previous != null) previous.close();
    }

    private void releaseStandby() {
        Decoder previous = standby;
        standby = null;
        blendStarted = 0;
        blendAmount = 0f;
        textureDirty = true;
        if (previous != null) previous.close();
    }

    private void promote() {
        if (standby == null || !standby.hasFrame) return;
        Decoder previous = primary;
        primary = standby;
        standby = null;
        primary.warming = false;
        blendStarted = 0;
        blendAmount = 0f;
        textureDirty = true;
        if (previous != null) previous.close();
    }

    private void ensureOutput() {
        float scale = Math.min(1f, Math.min(1280f / primary.width, 720f / primary.height));
        int width = Math.max(1, Math.round(primary.width * scale));
        int height = Math.max(1, Math.round(primary.height * scale));
        if (output != 0 && width == outputWidth && height == outputHeight) return;
        int[] names = new int[1];
        if (output == 0) { GLES20.glGenTextures(1, names, 0); output = names[0]; }
        if (framebuffer == 0) { GLES20.glGenFramebuffers(1, names, 0); framebuffer = names[0]; }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, output);
        textureParameters(GLES20.GL_TEXTURE_2D);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, output, 0);
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Video framebuffer unavailable");
        outputWidth = width;
        outputHeight = height;
        outputValid = false;
    }

    private void draw(float blend) {
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, previousFramebuffer, 0);
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, previousViewport, 0);
        try {
            ensureOutput();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            GLES20.glViewport(0, 0, outputWidth, outputHeight);
            drawInput(blend, FULL_CROP, 0f);
            outputValid = true;
            textureDirty = false;
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previousFramebuffer[0]);
            GLES20.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
        }
    }

    private void drawInput(float blend, float[] crop, float flip) {
        Decoder second = standby != null && standby.hasFrame && blend > 0 ? standby : primary;
        float amount = second == primary ? 0f : blend;
        if (RustVideo.draw(program,positionAttribute,sourceUniform,nextUniform,sourceMatrixUniform,
                nextMatrixUniform,blendUniform,cropUniform,flipUniform,primary.oes,second.oes,
                primary.matrix,second.matrix,crop,amount,flip)) return;
        GLES20.glUseProgram(program);
        GLES20.glDisable(GLES20.GL_BLEND);
        vertices.position(0);
        GLES20.glEnableVertexAttribArray(positionAttribute);
        GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT, false, 0, vertices);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, primary.oes);
        GLES20.glUniform1i(sourceUniform, 0);
        GLES20.glUniformMatrix4fv(sourceMatrixUniform, 1, false, primary.matrix, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, second.oes);
        GLES20.glUniform1i(nextUniform, 1);
        GLES20.glUniformMatrix4fv(nextMatrixUniform, 1, false, second.matrix, 0);
        GLES20.glUniform1f(blendUniform, amount);
        GLES20.glUniform4fv(cropUniform, 1, crop, 0);
        GLES20.glUniform1f(flipUniform, flip);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(positionAttribute);
    }

    private static void textureParameters(int target) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private static int shader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] success = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, success, 0);
        if (success[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("Video shader: " + log);
        }
        return shader;
    }

    private static int createProgram() {
        int vertex = 0, fragment = 0, result = 0;
        try {
            vertex = shader(GLES20.GL_VERTEX_SHADER, VERTEX);
            fragment = shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT);
            result = GLES20.glCreateProgram();
            GLES20.glAttachShader(result, vertex);
            GLES20.glAttachShader(result, fragment);
            GLES20.glLinkProgram(result);
            int[] success = new int[1];
            GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, success, 0);
            if (success[0] == 0) throw new IllegalStateException("Video shader link: " + GLES20.glGetProgramInfoLog(result));
            return result;
        } catch (RuntimeException error) {
            if (result != 0) GLES20.glDeleteProgram(result);
            throw error;
        } finally {
            if (vertex != 0) GLES20.glDeleteShader(vertex);
            if (fragment != 0) GLES20.glDeleteShader(fragment);
        }
    }

    private final class Decoder {
        final float[] matrix = new float[16];
        final Runnable timeout, sample;
        // Only closed crosses queues directly. All other state is published to GL by messages.
        volatile boolean closed;
        MediaPlayer player; // codec queue only
        boolean codecPlaying, codecReported; // codec queue only
        SurfaceTexture input;
        Surface surface;
        int oes, width, height, duration, lastPosition, observedPosition;
        long positionSampleAt;
        boolean warming, prepared, playing, dirty, hasFrame, warmingPaused, loopStartPending;

        Decoder(boolean warming, int position) throws IOException {
            this.warming = warming;
            lastPosition = observedPosition = position;
            timeout = () -> {
                if (!closed && (!hasFrame || width < 1 || height < 1))
                    failed(new IOException("Video frame timeout: prepared=" + prepared
                        + " dirty=" + dirty + " latched=" + hasFrame + " size=" + width + "x" + height
                        + " resumed=" + resumed + " playing=" + playing));
            };
            sample = new Runnable() {
                @Override public void run() {
                    if (closed || !codecPlaying || player == null) return;
                    samplePosition();
                    codec.postDelayed(this, 100);
                }
            };
            try {
                int[] names = new int[1];
                GLES20.glGenTextures(1, names, 0);
                oes = names[0];
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes);
                textureParameters(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
                input = new SurfaceTexture(oes);
                input.setOnFrameAvailableListener(value -> {
                    if (closed || released || !resumed) return;
                    dirty = true;
                    invalidate.run();
                }, handler);
                surface = new Surface(input);
                Surface target = surface;
                ParcelFileDescriptor descriptor = fileDescriptor;
                codec.post(() -> openPlayer(target, descriptor, position));
                handler.postDelayed(timeout, warming ? PREWARM_MS : 15000);
            } catch (RuntimeException error) { close(); throw error; }
        }

        // MediaPlayer is constructed here so its event callbacks use the codec Looper too.
        void openPlayer(Surface target, ParcelFileDescriptor descriptor, int position) {
            if (closed) return;
            try {
                MediaPlayer next = new MediaPlayer();
                player = next;
                next.setSurface(target);
                next.setDataSource(descriptor.getFileDescriptor());
                next.setVolume(0f, 0f);
                next.setScreenOnWhilePlaying(false);
                next.setOnVideoSizeChangedListener((mp, w, h) -> {
                    if (closed || player != mp || w < 1 || h < 1) return;
                    handler.post(() -> {
                        if (closed) return;
                        width = w; height = h;
                        if (hasFrame) handler.removeCallbacks(timeout);
                        invalidate.run();
                    });
                });
                next.setOnPreparedListener(mp -> {
                    if (closed || player != mp) return;
                    try {
                        int w = mp.getVideoWidth(), h = mp.getVideoHeight(), length = mp.getDuration();
                        handler.post(() -> {
                            if (closed) return;
                            prepared = true;
                            duration = length;
                            if (w > 0 && h > 0) { width = w; height = h; }
                            invalidate.run();
                        });
                        mp.setVolume(0f, 0f);
                        mp.setLooping(true);
                        if (position > 0 && length > position) {
                            mp.setOnSeekCompleteListener(seeked -> {
                                if (!closed && player == seeked) startPlayback(false);
                            });
                            mp.seekTo(position);
                        } else startPlayback(false);
                    } catch (RuntimeException error) { reportFailure(error); }
                });
                next.setOnErrorListener((mp, what, extra) -> {
                    if (!closed && player == mp)
                        reportFailure(new IOException("Video decoder error " + what + "/" + extra));
                    return true;
                });
                next.prepareAsync();
            } catch (IOException | RuntimeException error) { reportFailure(error); }
        }

        void startPlayback(boolean loop) {
            if (closed || player == null) return;
            try {
                player.start();
                codecPlaying = true;
                samplePosition();
                codec.removeCallbacks(sample);
                codec.postDelayed(sample, 100);
                handler.post(() -> {
                    if (closed) return;
                    if (loop) {
                        loopStartPending = false;
                        if (this != standby || !resumed || primary == null) return;
                        int remaining = primary.duration - primary.position();
                        // A delayed start must never blend across an already completed native loop.
                        if (remaining <= 0 || remaining > TAIL_MS + 100) {
                            releaseStandby();
                            invalidate.run();
                            return;
                        }
                        blendDuration = Math.max(1, Math.min(BLEND_MS, remaining - 20));
                        blendStarted = SystemClock.uptimeMillis();
                    }
                    invalidate.run();
                });
            } catch (RuntimeException error) { reportFailure(error); }
        }

        void playForLoop() {
            if (closed || !prepared || !warmingPaused || loopStartPending || !resumed) return;
            loopStartPending = true;
            codec.post(() -> startPlayback(true));
        }

        void pauseWarmup() {
            codec.post(() -> {
                if (closed || player == null) return;
                try {
                    player.pause();
                    codecPlaying = false;
                    codec.removeCallbacks(sample);
                    samplePosition();
                } catch (RuntimeException error) { reportFailure(error); }
            });
        }

        void samplePosition() {
            if (closed || player == null) return;
            try {
                inspectCodec();
                int value = Math.max(0, player.getCurrentPosition());
                long sampled = SystemClock.uptimeMillis();
                boolean moving = codecPlaying;
                handler.post(() -> {
                    if (closed) return;
                    lastPosition = value;
                    positionSampleAt = sampled;
                    playing = moving;
                });
            } catch (RuntimeException ignored) {}
        }

        void inspectCodec() {
            if (closed || player == null || codecReported) return;
            android.os.PersistableBundle metrics;
            try { metrics = player.getMetrics(); }
            catch (RuntimeException unsupported) { codecReported = true; return; }
            String name = metrics == null ? null : metrics.getString(MediaPlayer.MetricsConstants.CODEC_VIDEO);
            if (name == null || name.isEmpty()) return;
            codecReported = true;
            android.util.Log.i("WallpaperVideo", "Video decoder: " + name);
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            boolean software = lower.startsWith("omx.google.") || lower.startsWith("c2.android.");
            handler.post(() -> {
                if (closed || !warming || !software) return;
                android.util.Log.w("WallpaperVideo", "Software loop standby skipped; retaining native loop");
                failed(new IOException("Loop standby would require software decoding"));
            });
        }

        boolean latch() {
            if (closed || !dirty) return false;
            dirty = false;
            try {
                input.updateTexImage();
                input.getTransformMatrix(matrix);
                if (!hasFrame) {
                    android.util.Log.i("WallpaperVideo", "First video frame latched "
                        + width + "x" + height + (warming ? " (loop standby)" : ""));
                    if (!warming) LiveWallpaperPicker.confirmDecoderRecovery(context);
                }
                hasFrame = true;
                if (width > 0 && height > 0) handler.removeCallbacks(timeout);
                if (warming && !warmingPaused && blendStarted == 0) {
                    warmingPaused = true;
                    playing = false;
                    pauseWarmup();
                }
                return true;
            } catch (RuntimeException error) { failed(error); return false; }
        }

        int position() {
            long now = SystemClock.uptimeMillis();
            long estimate = lastPosition + (playing && positionSampleAt != 0 ? now - positionSampleAt : 0);
            return (int) Math.min(duration > 0 ? duration : Integer.MAX_VALUE, estimate);
        }

        void reportFailure(Exception error) {
            handler.post(() -> { if (!closed) failed(error); });
        }

        void failed(Exception error) {
            if (closed || released) return;
            if (this == standby || warming) {
                standbyDisabled = true;
                if (this == standby) releaseStandby();
                else close();
                invalidate.run();
            } else fail(error);
        }

        void close() {
            if (closed) return;
            closed = true;
            handler.removeCallbacks(timeout);
            Surface oldSurface = surface;
            SurfaceTexture oldInput = input;
            surface = null;
            input = null;
            int oldTexture = oes;
            oes = 0;
            if (oldInput != null) oldInput.setOnFrameAvailableListener(null);
            codec.post(() -> {
                codec.removeCallbacks(sample);
                MediaPlayer previous = player;
                player = null;
                codecPlaying = false;
                try {
                    if (previous != null) previous.release();
                } catch (RuntimeException error) {
                    android.util.Log.w("WallpaperVideo", "Could not release decoder", error);
                } finally {
                    handler.post(() -> {
                        try {
                            // MediaPlayer.release has returned. The consumer may now detach
                            // and release its GL buffer without racing the hardware producer.
                            if (oldInput != null) oldInput.detachFromGLContext();
                        } catch (RuntimeException error) {
                            android.util.Log.w("WallpaperVideo", "Could not detach stopped decoder", error);
                        } finally {
                            if (oldTexture != 0) GLES20.glDeleteTextures(1, new int[]{oldTexture}, 0);
                            if (oldSurface != null) oldSurface.release();
                            if (oldInput != null) oldInput.release();
                        }
                    });
                }
            });
        }
    }
}
