package local.launcherfix;

import android.net.Uri;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Gallery policy checks; no storage scan, permission request, import or UI action. */
public final class GalleryCheck {
    private static int count;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        count++;
    }
    private static WallpaperGallery.Entry item(String id, String folder, boolean video, long modified) {
        return new WallpaperGallery.Entry(Uri.parse("content://media/external/" + id),
            id + (video ? ".mp4" : ".jpg"), folder, folder, video, 1024L, modified, null);
    }
    public static void main(String[] args) throws Exception {
        long imageLimit = 35L * 1024 * 1024, videoLimit = 256L * 1024 * 1024;
        check(WallpaperGallery.accepts("photo.JPG", "image/jpeg", 1), "case insensitive JPEG file");
        check(WallpaperGallery.accepts("photo.jpeg", "", imageLimit), "JPEG exact image limit");
        check(WallpaperGallery.accepts("photo.png", "image/png", imageLimit), "PNG exact image limit");
        check(WallpaperGallery.accepts("photo.webp", "image/webp", imageLimit), "WebP exact image limit");
        check(!WallpaperGallery.accepts("photo.jpg", "image/jpeg", imageLimit + 1), "oversized image excluded");
        check(!WallpaperGallery.accepts("photo.png", "image/png", 0), "empty image excluded");
        check(!WallpaperGallery.accepts("photo.webp", "image/webp", -1), "unknown negative size excluded");
        check(WallpaperGallery.accepts("wall.MP4", "video/mp4", videoLimit), "MP4 exact video limit");
        check(!WallpaperGallery.accepts("wall.mp4", "video/mp4", videoLimit + 1), "oversized MP4 excluded");
        check(!WallpaperGallery.accepts("wall.gif", "image/gif", 1024), "unsupported GIF excluded");
        check(!WallpaperGallery.accepts("wall.svg", "image/svg+xml", 1024), "unsupported SVG excluded");
        check(!WallpaperGallery.accepts("wall.mkv", "video/x-matroska", 1024), "unsupported container excluded");
        check(!WallpaperGallery.accepts("song.mp3", "audio/mpeg", 1024), "audio excluded");
        check(WallpaperGallery.isVideo("movie.MP4", ""), "MP4 name selects video thumbnail");
        check(WallpaperGallery.isVideo("download", "video/mp4"), "MP4 MIME selects video thumbnail");
        check(!WallpaperGallery.isVideo("photo.webp", "image/webp"), "image never selects video decoder");

        WallpaperGallery.Entry imageA = item("3", "Pictures", false, 10);
        WallpaperGallery.Entry videoA = item("2", "Pictures", true, 20);
        WallpaperGallery.Entry imageB = item("1", "Download", false, 30);
        List<WallpaperGallery.Entry> input = new ArrayList<>(Arrays.asList(imageA, videoA, imageB));
        check(WallpaperGallery.filter(input, 0, "").size() == 3, "all media remains visible");
        List<WallpaperGallery.Entry> images = WallpaperGallery.filter(input, 1, "");
        check(images.size() == 2 && images.contains(imageA) && images.contains(imageB), "image filter excludes MP4");
        List<WallpaperGallery.Entry> videos = WallpaperGallery.filter(input, 2, "");
        check(videos.size() == 1 && videos.get(0) == videoA, "video filter excludes images");
        List<WallpaperGallery.Entry> folder = WallpaperGallery.filter(input, 0, "Pictures");
        check(folder.size() == 2 && folder.contains(imageA) && folder.contains(videoA), "folder keeps both supported types");
        List<WallpaperGallery.Entry> combined = WallpaperGallery.filter(input, 1, "Pictures");
        check(combined.size() == 1 && combined.get(0) == imageA, "type and folder filters intersect");
        check(WallpaperGallery.filter(input, 2, "Download").isEmpty(), "empty folder type combination");
        check(WallpaperGallery.filter(input, 0, "Missing").isEmpty(), "missing folder has no stale rows");
        check(input.size() == 3 && input.get(0) == imageA, "filter leaves source unchanged");

        List<WallpaperGallery.Entry> sorted = WallpaperGallery.newest(input);
        check(sorted.get(0) == imageB && sorted.get(2) == imageA, "newest first ordering");
        check(input.get(0) == imageA, "sort leaves source unchanged");
        WallpaperGallery.Entry tieB = item("b", "Pictures", false, 40);
        WallpaperGallery.Entry tieA = item("a", "Pictures", false, 40);
        List<WallpaperGallery.Entry> ties = WallpaperGallery.newest(Arrays.asList(tieB, tieA));
        check(ties.get(0) == tieA && ties.get(1) == tieB, "equal dates have deterministic URI order");
        WallpaperGallery.Entry future = item("future", "Pictures", false, 5000000000L);
        check(WallpaperGallery.newest(Arrays.asList(imageB, future)).get(0) == future, "date comparison does not truncate long");
        List<WallpaperGallery.Entry> large = new ArrayList<>();
        for (int i = 0; i < 700; i++) large.add(item(Integer.toString(i), "Pictures", false, i));
        List<WallpaperGallery.Entry> limited = WallpaperGallery.newest(large);
        check(limited.size() == 600, "gallery bounds retained entries");
        check(limited.get(0).modified == 699 && limited.get(599).modified == 100, "cap retains newest items");
        check(large.size() == 700 && large.get(0).modified == 0, "capping preserves original list");

        File root = new File("/data/local/tmp/gallery-root");
        check(WallpaperGallery.inside(root, new File(root, "Pictures/photo.jpg")), "ordinary nested public path accepted");
        check(!WallpaperGallery.inside(root, new File(root, "../outside/photo.jpg")), "parent traversal cannot leave root");
        check(!WallpaperGallery.inside(root, new File("/data/local/tmp/gallery-root-other/photo.jpg")), "sibling prefix is not containment");
        check(!WallpaperGallery.inside(root, new File(root, "Pictures/.private/photo.jpg")), "hidden directory excluded");
        check(!WallpaperGallery.inside(root, new File(root, "Pictures/.photo.jpg")), "hidden file excluded");
        check(!WallpaperGallery.inside(root, new File(root, "Android/data/other.package/files/photo.jpg")), "Android private tree excluded");
        check(!WallpaperGallery.inside(root, new File(root, "Pictures/Android/data/photo.jpg")), "nested Android tree excluded");
        check(WallpaperGallery.inside(root, new File(root, "Pictures/../Download/photo.jpg")), "normalized path within root accepted");
        System.out.println("GALLERY_CHECK_OK cases=" + count);
    }
}
