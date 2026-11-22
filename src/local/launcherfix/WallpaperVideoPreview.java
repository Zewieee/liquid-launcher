package local.launcherfix;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.util.Log;
import java.io.File;

/** One still frame per preview request; callers cache it exactly like an image thumbnail. */
public final class WallpaperVideoPreview {
    private static final long MAX_LEGACY_PIXELS = 4096L * 2160;
    private static final long MAX_SCALED_PIXELS = 1920L * 1080;

    private WallpaperVideoPreview() {}

    public static Bitmap videoFrame(File file, int width, int height) {
        return videoFrame(file, width, height, null);
    }

    // Serialize retrievers so a picker cannot allocate several full legacy frames together.
    public static synchronized Bitmap videoFrame(File file, int width, int height, int[] dimensions) {
        if (dimensions != null && dimensions.length >= 2) dimensions[0] = dimensions[1] = 0;
        if (file == null || !file.isFile() || width < 1 || height < 1
                || (long) width * height > MAX_LEGACY_PIXELS) return null;
        MediaMetadataRetriever retriever = null;
        Bitmap frame = null, result = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(file.getAbsolutePath());
            int sourceWidth = metadataInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            int sourceHeight = metadataInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (sourceWidth < 1 || sourceHeight < 1) return null;
            int rotation = metadataInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (rotation == 90 || rotation == 270) {
                int swap = sourceWidth; sourceWidth = sourceHeight; sourceHeight = swap;
            }
            if (dimensions != null && dimensions.length >= 2) {
                dimensions[0] = sourceWidth; dimensions[1] = sourceHeight;
            }
            if (Build.VERSION.SDK_INT >= 27) {
                double scale = Math.min(1d, Math.max((double) width / sourceWidth,
                    (double) height / sourceHeight));
                scale = Math.min(scale, Math.sqrt((double) MAX_SCALED_PIXELS
                    / ((long) sourceWidth * sourceHeight)));
                int decodeWidth = Math.max(1, (int) Math.round(sourceWidth * scale));
                int decodeHeight = Math.max(1, (int) Math.round(sourceHeight * scale));
                frame = retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    decodeWidth, decodeHeight);
            } else {
                // API 26 has no scaled retriever API; bound the allocation before decoding.
                if ((long) sourceWidth * sourceHeight > MAX_LEGACY_PIXELS) return null;
                frame = retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (frame == null || frame.isRecycled()) return null;
            if (frame.getWidth() == width && frame.getHeight() == height) {
                result = frame;
                frame = null;
            } else {
                result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                float scale = Math.max((float) width / frame.getWidth(), (float) height / frame.getHeight());
                int cropWidth = Math.min(frame.getWidth(), Math.max(1, Math.round(width / scale)));
                int cropHeight = Math.min(frame.getHeight(), Math.max(1, Math.round(height / scale)));
                int left = (frame.getWidth() - cropWidth) / 2, top = (frame.getHeight() - cropHeight) / 2;
                new Canvas(result).drawBitmap(frame, new Rect(left, top, left + cropWidth, top + cropHeight),
                    new Rect(0, 0, width, height), new Paint(Paint.FILTER_BITMAP_FLAG));
            }
            Bitmap output = result;
            result = null;
            return output;
        } catch (RuntimeException | OutOfMemoryError error) {
            Log.w("TvHome.VideoPreview", "Cannot read video preview", error);
            return null;
        } finally {
            if (frame != null && !frame.isRecycled()) frame.recycle();
            if (result != null && !result.isRecycled()) result.recycle();
            if (retriever != null) {
                try { retriever.release(); } catch (Exception ignored) {}
            }
        }
    }

    private static int metadataInt(MediaMetadataRetriever retriever, int key) {
        try { return Integer.parseInt(retriever.extractMetadata(key)); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
