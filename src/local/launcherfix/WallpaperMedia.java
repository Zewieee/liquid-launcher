package local.launcherfix;

import android.content.Context;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** Shared collection filter and bounded, background-only image/MP4 import. */
public final class WallpaperMedia {
    private static final long IMAGE_LIMIT = 35L * 1024 * 1024;
    private static final long VIDEO_LIMIT = 256L * 1024 * 1024;

    private WallpaperMedia() {}

    public static boolean isVideo(File file) {
        return file != null && file.getName().toLowerCase(Locale.ROOT).endsWith(".mp4");
    }

    public static boolean accepts(File file) {
        if (file == null || !file.isFile() || file.isHidden()) return false;
        long size = file.length();
        if (size <= 0 || size > (isVideo(file) ? VIDEO_LIMIT : IMAGE_LIMIT)) return false;
        if (isVideo(file)) return true;
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg") || name.endsWith(".jpeg")
            || name.endsWith(".png") || name.endsWith(".webp");
    }

    public static File legacy(Context context) {
        File file = new File(context.getFilesDir(), "live-wallpaper.mp4");
        return accepts(file) ? file : null;
    }

    /** The legacy settings activity applies this choice when Home resumes. */
    public static void publishImported(Context context, File file) {
        WallpaperCollectionHelper.invalidateCache();
        context.getSharedPreferences("wallpaper_collections", 0).edit()
            .putString("active_collection_id", "default").apply();
        context.getSharedPreferences("wallpaper_rotation", 0).edit()
            .putString("collection", WallpaperCollectionHelper.computeSignature(java.util.Collections.singletonList(file)))
            .putString("image", file.getName()).putLong("changed", System.currentTimeMillis()).apply();
        context.getSharedPreferences("launcher_settings", 0).edit()
            .putString("wallpaper_choice", file.getAbsolutePath()).apply();
    }

    public static File copy(Context context, Uri uri) throws IOException {
        checkCancelled();
        if (uri == null || (!"content".equals(uri.getScheme()) && !"file".equals(uri.getScheme())))
            throw new IOException("Choose a local image or MP4 file");
        File external = context.getExternalFilesDir(null);
        if (external == null) throw new IOException("Wallpaper storage unavailable");
        File folder = new File(external, "wallpapers");
        if (!folder.isDirectory() && !folder.mkdirs() && !folder.isDirectory())
            throw new IOException("Cannot create wallpaper folder");
        File stage = File.createTempFile(".wallpaper-import-", ".tmp", folder);
        File destination = null;
        boolean published = false;
        try {
            try (InputStream source = context.getContentResolver().openInputStream(uri)) {
                if (source == null) throw new IOException("Cannot read wallpaper");
                try (BufferedInputStream input = new BufferedInputStream(source);
                        FileOutputStream output = new FileOutputStream(stage)) {
                    // Providers may omit or mislabel MIME/name; enforce limits from the bytes.
                    long limit = imageHeader(input) ? IMAGE_LIMIT : VIDEO_LIMIT;
                    byte[] buffer = new byte[64 * 1024];
                    long total = 0;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkCancelled();
                        total += count;
                        if (total > limit) throw new IOException("Wallpaper exceeds size limit");
                        output.write(buffer, 0, count);
                    }
                    if (total == 0) throw new IOException("Empty wallpaper");
                    output.getFD().sync();
                }
            }
            checkCancelled();
            String extension = validatedExtension(stage);
            checkCancelled();
            String name = displayName(context, uri);
            checkCancelled();
            destination = File.createTempFile("wp-" + name + "-", extension, folder);
            // Reserve our own empty name first, so an existing wallpaper is never replaced.
            checkCancelled();
            try { Os.rename(stage.getAbsolutePath(), destination.getAbsolutePath()); }
            catch (ErrnoException error) { throw new IOException("Cannot save wallpaper", error); }
            checkCancelled();
            published = true;
            return destination;
        } catch (RuntimeException error) {
            throw new IOException("Invalid or unreadable image/MP4", error);
        } finally {
            if (stage.exists()) stage.delete();
            if (!published && destination != null) destination.delete();
        }
    }

    private static boolean imageHeader(BufferedInputStream input) throws IOException {
        input.mark(16);
        byte[] header = new byte[12];
        int count = 0, read;
        while (count < header.length && (read = input.read(header, count, header.length - count)) > 0) {
            checkCancelled();
            count += read;
        }
        input.reset();
        return count >= 3 && (header[0] & 255) == 255 && (header[1] & 255) == 216 && (header[2] & 255) == 255
            || count >= 8 && (header[0] & 255) == 137 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G'
            || count >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
    }

    private static String validatedExtension(File file) throws IOException {
        checkCancelled();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (options.outWidth > 0 && options.outHeight > 0) {
            if (file.length() > IMAGE_LIMIT) throw new IOException("Image exceeds 35 MB");
            if ("image/jpeg".equals(options.outMimeType)) return ".jpg";
            if ("image/png".equals(options.outMimeType)) return ".png";
            if ("image/webp".equals(options.outMimeType)) return ".webp";
            throw new IOException("Unsupported image format");
        }
        checkCancelled();
        MediaMetadataRetriever metadata = new MediaMetadataRetriever();
        try {
            metadata.setDataSource(file.getAbsolutePath());
            String mime = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            String video = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
            int width = Integer.parseInt(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height = Integer.parseInt(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            if (!"video/mp4".equals(mime) || !"yes".equals(video) || width < 1 || height < 1)
                throw new IOException("Not an MP4 video");
            return ".mp4";
        } finally { metadata.release(); }
    }

    private static String displayName(Context context, Uri uri) {
        String name = uri.getLastPathSegment();
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0 && cursor.getString(index) != null) name = cursor.getString(index);
                }
            } catch (RuntimeException ignored) {}
        }
        if (name == null) name = "wallpaper";
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.replaceAll("[^\\p{L}\\p{N} _-]", "_").trim();
        if (name.isEmpty()) name = "wallpaper";
        return name.substring(0, Math.min(64, name.length()));
    }

    private static void checkCancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Wallpaper import cancelled");
    }
}
