package local.launcherfix;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Size;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Bounded, read-only index for the in-launcher wallpaper picker. */
final class WallpaperGallery {
    private static final int MAX_ITEMS = 600, MAX_SCAN = 3000;
    private static final long IMAGE_LIMIT = 35L * 1024 * 1024, VIDEO_LIMIT = 256L * 1024 * 1024;
    private static final String IMAGES = "android.permission.READ_MEDIA_IMAGES";
    private static final String VIDEOS = "android.permission.READ_MEDIA_VIDEO";
    private static final String SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED";
    private WallpaperGallery() {}

    static final class Entry {
        final Uri uri;
        final String name, folderKey, folderLabel;
        final boolean video;
        final long size, modified;
        final File file;
        Entry(Uri uri, String name, String folderKey, String folderLabel, boolean video,
              long size, long modified, File file) {
            this.uri=uri; this.name=name; this.folderKey=folderKey; this.folderLabel=folderLabel;
            this.video=video; this.size=size; this.modified=modified; this.file=file;
        }
    }

    static String[] missingPermissions(Context context) {
        if (Build.VERSION.SDK_INT < 33)
            return granted(context, Manifest.permission.READ_EXTERNAL_STORAGE) ? new String[0]
                : new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        if (Build.VERSION.SDK_INT >= 34 && granted(context, SELECTED)) return new String[0];
        ArrayList<String> missing = new ArrayList<>();
        if (!granted(context, IMAGES)) missing.add(IMAGES);
        if (!granted(context, VIDEOS)) missing.add(VIDEOS);
        return missing.toArray(new String[0]);
    }

    private static boolean granted(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean canRead(Context context, boolean video) {
        return Build.VERSION.SDK_INT < 33 ? granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            : granted(context, video ? VIDEOS : IMAGES)
                || Build.VERSION.SDK_INT >= 34 && granted(context, SELECTED);
    }

    static List<Entry> load(Context context) throws IOException {
        workerOnly();
        LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
        if (canRead(context, false)) query(context, false, entries);
        if (canRead(context, true)) query(context, true, entries);
        if (Build.VERSION.SDK_INT <= 29 && granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)) {
            int[] budget = {MAX_SCAN};
            Set<String> visited = new HashSet<>();
            for (String folder : new String[]{Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_PICTURES,
                    Environment.DIRECTORY_DCIM, Environment.DIRECTORY_MOVIES, "Wallpapers"}) {
                File root = new File(Environment.getExternalStorageDirectory(), folder);
                scanRoot(root, budget, visited, entries);
            }
            for (File appFiles : context.getExternalFilesDirs(null)) {
                if (appFiles == null) continue;
                try {
                    File root = appFiles;
                    for (int i=0; i<4 && root!=null; i++) root=root.getParentFile();
                    if (root == null || root.getCanonicalFile().equals(Environment.getExternalStorageDirectory().getCanonicalFile())) continue;
                    File expected = new File(root, "Android/data/"+context.getPackageName()+"/files");
                    if (!expected.getCanonicalFile().equals(appFiles.getCanonicalFile())) continue;
                    scanRoot(root, budget, visited, entries);
                } catch (InterruptedIOException cancelled) { throw cancelled; }
                catch (IOException | SecurityException removed) { }
            }
        }
        checkCancelled();
        return newest(new ArrayList<>(entries.values()));
    }

    private static void query(Context context, boolean video, LinkedHashMap<String, Entry> entries) throws IOException {
        checkCancelled();
        Uri collection = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {"_id", "_display_name", "mime_type", "_size", "date_modified", "bucket_id", "bucket_display_name", "_data"};
        Bundle args = new Bundle();
        args.putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, new String[]{"date_modified", "_id"});
        args.putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);
        args.putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_ITEMS);
        String selection = Build.VERSION.SDK_INT >= 29 ? "is_pending=0" : "";
        if (Build.VERSION.SDK_INT >= 30) selection += " AND is_trashed=0";
        if (!selection.isEmpty()) args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection);
        try (Cursor cursor = context.getContentResolver().query(collection, projection, args, null)) {
            if (cursor == null) return;
            int examined = 0;
            while (examined++ < MAX_ITEMS && cursor.moveToNext()) {
                checkCancelled();
                String name = cursor.getString(1), mime = cursor.getString(2);
                long size = cursor.getLong(3);
                if (!accepts(name, mime, size) || video != isVideo(name, mime)) continue;
                String path = cursor.getString(7);
                File candidate = path == null || path.isEmpty() ? null : new File(path);
                String key = candidate == null ? collection.toString()+"/"+cursor.getLong(0) : candidate.getCanonicalPath();
                String folder = candidate == null ? "bucket:"+cursor.getString(5) : candidate.getCanonicalFile().getParent();
                String label = cursor.getString(6);
                if (label == null || label.isEmpty()) label = candidate == null ? "Media" : candidate.getParentFile().getName();
                File local = Build.VERSION.SDK_INT <= 29 && candidate != null && candidate.canRead() ? candidate : null;
                entries.put(key, new Entry(ContentUris.withAppendedId(collection, cursor.getLong(0)), bounded(name),
                    folder, bounded(label), video, size, Math.max(0, cursor.getLong(4))*1000L, local));
            }
        } catch (InterruptedIOException cancelled) { throw cancelled; }
        catch (IOException | RuntimeException unavailable) { }
        // Revoked permissions, ejected volumes or a missing TV media table do not hide other sources.
    }

    private static void scanRoot(File root, int[] budget, Set<String> visited,
                                 LinkedHashMap<String, Entry> entries) throws InterruptedIOException {
        try { scan(root, root, 2, budget, visited, entries); }
        catch (InterruptedIOException cancelled) { throw cancelled; }
        catch (IOException | SecurityException | java.nio.file.DirectoryIteratorException removed) { }
    }

    private static void scan(File root, File folder, int depth, int[] budget, Set<String> visited,
                             LinkedHashMap<String, Entry> entries) throws IOException {
        checkCancelled();
        if (depth < 0 || budget[0] <= 0 || !folder.isDirectory() || !inside(root, folder)) return;
        String canonical = folder.getCanonicalPath();
        if (!visited.add(canonical)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder.toPath())) {
            for (Path path : stream) {
                checkCancelled();
                if (--budget[0] < 0) break;
                File file = path.toFile();
                if (!inside(root, file)) continue;
                if (file.isDirectory()) {
                    try { scan(root, file, depth-1, budget, visited, entries); }
                    catch (InterruptedIOException cancelled) { throw cancelled; }
                    catch (IOException | SecurityException | java.nio.file.DirectoryIteratorException removed) { }
                    continue;
                }
                if (!file.isFile() || !file.canRead() || !accepts(file.getName(), "", file.length())) continue;
                String key = file.getCanonicalPath();
                if (entries.containsKey(key)) continue;
                File real = file.getCanonicalFile();
                entries.put(key, new Entry(Uri.fromFile(real), bounded(real.getName()), real.getParent(),
                    bounded(real.getParentFile().getName()), isVideo(real.getName(), ""), real.length(), real.lastModified(), real));
            }
        } catch (SecurityException | java.nio.file.AccessDeniedException denied) {
            // USB removal and permission denial must not hide already discovered media.
        } catch (java.nio.file.NoSuchFileException removed) { }
    }

    static boolean inside(File root, File candidate) {
        try {
            String base = root.getCanonicalPath(), path = candidate.getCanonicalPath();
            if (!path.equals(base) && !path.startsWith(base+File.separator)) return false;
            String relative = path.equals(base) ? "" : path.substring(base.length()+1);
            for (String part : relative.split("[/\\\\]"))
                if (part.startsWith(".") || "Android".equalsIgnoreCase(part)) return false;
            return !candidate.getName().startsWith(".");
        } catch (IOException | SecurityException invalid) { return false; }
    }

    static boolean isVideo(String name, String mime) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".mp4") || "video/mp4".equalsIgnoreCase(mime);
    }

    static boolean accepts(String name, String mime, long size) {
        if (name == null || name.isEmpty() || name.startsWith(".") || size <= 0) return false;
        if (isVideo(name, mime)) return size <= VIDEO_LIMIT;
        String lower = name.toLowerCase(Locale.ROOT);
        return size <= IMAGE_LIMIT && (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
            || lower.endsWith(".webp") || "image/jpeg".equalsIgnoreCase(mime)
            || "image/png".equalsIgnoreCase(mime) || "image/webp".equalsIgnoreCase(mime));
    }

    static List<Entry> newest(List<Entry> entries) {
        ArrayList<Entry> sorted = new ArrayList<>(entries);
        Collections.sort(sorted, Comparator.comparingLong((Entry item) -> item.modified).reversed()
            .thenComparing(item -> item.uri.toString()));
        return sorted.size() > MAX_ITEMS ? new ArrayList<>(sorted.subList(0, MAX_ITEMS)) : sorted;
    }

    static List<Entry> filter(List<Entry> entries, int type, String folderKey) {
        ArrayList<Entry> result = new ArrayList<>();
        for (Entry item : entries) if ((type == 0 || type == 1 && !item.video || type == 2 && item.video)
                && (folderKey == null || folderKey.isEmpty() || folderKey.equals(item.folderKey))) result.add(item);
        return result;
    }

    static Bitmap thumbnail(Context context, Entry entry, int width, int height) throws IOException {
        workerOnly(); checkCancelled();
        int w=Math.max(48, Math.min(720,width)), h=Math.max(48, Math.min(720,height));
        Bitmap image = null;
        try {
            if (entry.file != null) {
                WallpaperThumbLoader.ThumbResult result = WallpaperThumbLoader.loadOrDecode(context, entry.file, w, h,
                    new File(context.getCacheDir(), "wall_thumbs"), WallpaperThumbLoader.getCacheKey(entry.file));
                image = result == null ? null : result.bitmap;
            } else if (Build.VERSION.SDK_INT >= 29) {
                try { image = context.getContentResolver().loadThumbnail(entry.uri, new Size(w,h), null); }
                catch (IOException | RuntimeException unavailable) { }
            }
            if (image == null && entry.video) image = videoFrame(context,entry.uri,w,h);
            if (image == null && !entry.video) {
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds=true;
                try (InputStream input=context.getContentResolver().openInputStream(entry.uri)) { BitmapFactory.decodeStream(input,null,bounds); }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inPreferredConfig=Bitmap.Config.RGB_565;
                options.inSampleSize=WallpaperThumbLoader.sampleSize(bounds.outWidth,bounds.outHeight,w,h);
                checkCancelled();
                try (InputStream input=context.getContentResolver().openInputStream(entry.uri)) { image=BitmapFactory.decodeStream(input,null,options); }
            }
            checkCancelled();
            Bitmap result = image == null ? null : WallpaperThumbLoader.centerCropScale(image,w,h);
            image = null;
            return result;
        } catch (RuntimeException | OutOfMemoryError unreadable) { return null; }
        finally { if (image != null && !image.isRecycled()) image.recycle(); }
    }

    private static synchronized Bitmap videoFrame(Context context, Uri uri, int width, int height) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context,uri);
            if (Build.VERSION.SDK_INT >= 27)
                return retriever.getScaledFrameAtTime(-1,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,width,height);
            int w=Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h=Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            return w>0 && h>0 && (long)w*h<=4096L*2160 ? retriever.getFrameAtTime(-1,MediaMetadataRetriever.OPTION_CLOSEST_SYNC) : null;
        } finally { try { retriever.release(); } catch (Exception ignored) { } }
    }

    private static String bounded(String text) { return text.substring(0,Math.min(256,text.length())); }
    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Gallery closed");
    }
    private static void workerOnly() {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Gallery IO requires a worker");
    }
}
