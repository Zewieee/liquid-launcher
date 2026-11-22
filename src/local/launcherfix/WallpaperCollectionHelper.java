package local.launcherfix;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.gathin.tvhome.ControlCenter;
import com.gathin.tvhome.ClearGlass;
import com.gathin.tvhome.MainActivity;
import com.gathin.tvhome.WallpaperPopup;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;
import java.lang.ref.WeakReference;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import android.os.Handler;
import android.os.Looper;

public final class WallpaperCollectionHelper {

    public static final String PREFS_COLLECTIONS = "wallpaper_collections";
    public static final String KEY_ACTIVE_COLLECTION_ID = "active_collection_id";
    public static final String KEY_CUSTOM_FOLDERS = "custom_folders";
    public static final String PREFS_SETTINGS = "launcher_settings";
    public static final String PREFS_ROTATION = "wallpaper_rotation";

    public static final FileFilter IMAGE_FILTER = new FileFilter() {
        @Override
        public boolean accept(File file) {
            return WallpaperMedia.accepts(file);
        }
    };

    public static final class Collection {
        public final String id;
        public final String name;
        public final File folder;
        public final List<File> files;
        public final boolean isDefault;

        public Collection(String id, String name, File folder, List<File> files, boolean isDefault) {
            this.id = id;
            this.name = name;
            this.folder = folder;
            this.files = files != null ? files : new ArrayList<File>();
            this.isDefault = isDefault;
        }

        public int count() {
            return files.size();
        }
    }

    private static volatile List<Collection> sCachedCollections = null;
    private static volatile long sLastScanTime = 0L;
    private static final long CACHE_TTL_MS = 60000L; // 60s in-memory cache
    private static final java.util.concurrent.atomic.AtomicBoolean scanQueued = new java.util.concurrent.atomic.AtomicBoolean();

    public static void invalidateCache() {
        sCachedCollections = null;
        sLastScanTime = 0L;
    }

    public static List<Collection> scanCollections(Context context) {
        return scanCollections(context, false);
    }

    public static List<Collection> scanCollections(Context context, boolean forceRefresh) {
        long now = SystemClock.elapsedRealtime();
        List<Collection> cached = sCachedCollections;
        if (!forceRefresh && cached != null && (now - sLastScanTime < CACHE_TTL_MS)) {
            return cached;
        }
        if(Looper.myLooper()==Looper.getMainLooper()){
            prewarmCollections(context);
            return cached==null?Collections.emptyList():cached;
        }
        List<Collection> list = doScanCollections(context);
        sCachedCollections = list;
        sLastScanTime = now;
        return list;
    }

    public static void prewarmCollections(final Context context) {
        if (context == null || !scanQueued.compareAndSet(false, true)) return;
        final Context app = context.getApplicationContext();
        final java.lang.ref.WeakReference<Context> owner = new java.lang.ref.WeakReference<>(context);
        final SharedPreferences settings = context.getSharedPreferences(PREFS_SETTINGS, 0);
        final String choice = settings.getString("wallpaper_choice", null);
        final String collection = context.getSharedPreferences(PREFS_COLLECTIONS, 0).getString(KEY_ACTIVE_COLLECTION_ID, null);
        CollectionDialog.WORKER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    scanCollections(app, false);
                    Context target = owner.get();
                    if (target instanceof MainActivity) {
                        MainActivity main = (MainActivity) target;
                        main.runOnUiThread(() -> {
                            if (main.isFinishing() || main.isDestroyed() || main.wallpaperPlayer == null) return;
                            if (!java.util.Objects.equals(choice, settings.getString("wallpaper_choice", null))
                                || !java.util.Objects.equals(collection, main.getSharedPreferences(PREFS_COLLECTIONS, 0).getString(KEY_ACTIVE_COLLECTION_ID, null))) return;
                            if ("none".equals(choice)) return;
                            ArrayList<File> files = imagesForUiConfig(main, false);
                            if (!files.equals(main.wallpaperPlayer.files)) {
                                applyScannedFiles(main.wallpaperPlayer, files, main.started);
                            }
                        });
                    }
                } catch (RuntimeException error) { android.util.Log.w("TvHome.Collections", "Collection refresh", error); }
                finally {scanQueued.set(false);}
            }
        });
    }

    private WallpaperCollectionHelper() {}

    /** UI-thread publication also handles a cold start with no saved file index. */
    public static void applyScannedFiles(com.gathin.tvhome.WallpaperPlayer player, List<File> files, boolean active) {
        if (player == null || player.closed) return;
        WallpaperLoads.invalidate(player);
        player.files.clear();player.files.addAll(files);
        player.enabled = !files.isEmpty();
        player.image.setVisibility(player.enabled?View.VISIBLE:View.GONE);
        if(!player.enabled){player.displayed="";player.show(null);player.showCaption(false);}
        if (player.enabled && player.image.getParent() == null) {
            player.root.addView(player.image, 0, new FrameLayout.LayoutParams(-1, -1));
        }
        LiveWallpaper.imageFilesChanged(player);
        if (active) {player.active = true;player.update();}
    }

    /**
     * Scans for all available wallpaper folders/collections across internal and external storage.
     */
    private static List<Collection> doScanCollections(Context context) {
        List<Collection> list = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();
        boolean vi = "vi".equals(Locale.getDefault().getLanguage());

        // 1. App-specific external files wallpapers directory
        File appWallpapers = new File(context.getExternalFilesDir(null), "wallpapers");
        List<File> defaults = new ArrayList<>();
        if (appWallpapers.isDirectory()) defaults.addAll(getImagesInFolder(appWallpapers));
        // The old standalone picker kept its MP4 privately. Expose that same file in the
        // normal default collection without copying or moving a possibly playing source.
        File legacyVideo = WallpaperMedia.legacy(context);
        String legacyPath = legacyVideo == null ? null : getCanonical(legacyVideo);
        if (legacyVideo != null) {
            boolean present = false;
            for (File file : defaults) if (legacyPath.equals(getCanonical(file))) {present = true;break;}
            if (!present) defaults.add(legacyVideo);
        }
        if (!defaults.isEmpty()) {
            Collections.sort(defaults, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            seenPaths.add(getCanonical(appWallpapers));
            String name = vi ? "Mặc định (Hình nền cá nhân)" : "Default (My Wallpapers)";
            list.add(new Collection("default", name, appWallpapers, defaults, true));
        }
        if (appWallpapers.exists() && appWallpapers.isDirectory()) {
            File[] subs = appWallpapers.listFiles();
            if (subs != null) {
                for (File sub : subs) {
                    if (sub.isDirectory() && !sub.isHidden()) {
                        addFolderIfImages(list, seenPaths, sub, sub.getName(), false);
                    }
                }
            }
        }

        // 2. /sdcard/Download subdirectories (e.g. "Awesome Wallpapers")
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloadDir != null && downloadDir.exists() && downloadDir.isDirectory()) {
            File[] subs = downloadDir.listFiles();
            if (subs != null) {
                for (File sub : subs) {
                    if (sub.isDirectory() && !sub.isHidden()) {
                        addFolderIfImages(list, seenPaths, sub, sub.getName(), false);
                    }
                }
            }
        }

        // 3. /sdcard/Pictures directory and subdirectories
        File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        if (picturesDir != null && picturesDir.exists() && picturesDir.isDirectory()) {
            addFolderIfImages(list, seenPaths, picturesDir, "Pictures", false);
            File[] subs = picturesDir.listFiles();
            if (subs != null) {
                for (File sub : subs) {
                    if (sub.isDirectory() && !sub.isHidden()) {
                        addFolderIfImages(list, seenPaths, sub, sub.getName(), false);
                    }
                }
            }
        }

        // 4. /sdcard/Wallpapers and subdirectories
        File rootSd = Environment.getExternalStorageDirectory();
        if (rootSd != null) {
            File wallDir = new File(rootSd, "Wallpapers");
            if (wallDir.exists() && wallDir.isDirectory()) {
                addFolderIfImages(list, seenPaths, wallDir, "Wallpapers", false);
                File[] subs = wallDir.listFiles();
                if (subs != null) {
                    for (File sub : subs) {
                        if (sub.isDirectory() && !sub.isHidden()) {
                            addFolderIfImages(list, seenPaths, sub, sub.getName(), false);
                        }
                    }
                }
            }
        }

        // 5. Custom saved folders in SharedPreferences
        SharedPreferences sp = context.getSharedPreferences(PREFS_COLLECTIONS, Context.MODE_PRIVATE);
        Set<String> custom = sp.getStringSet(KEY_CUSTOM_FOLDERS, null);
        if (custom != null) {
            for (String path : custom) {
                File dir = new File(path);
                if (dir.exists() && dir.isDirectory()) {
                    addFolderIfImages(list, seenPaths, dir, dir.getName(), false);
                }
            }
        }

        // A formerly registered private folder must not expose the legacy entry twice.
        if (legacyPath != null) {
            for (java.util.Iterator<Collection> it = list.iterator(); it.hasNext();) {
                Collection item = it.next();
                if (item.isDefault) continue;
                item.files.removeIf(file -> legacyPath.equals(getCanonical(file)));
                if (item.files.isEmpty()) it.remove();
            }
        }

        // Sort: default first, then alphabetically
        Collections.sort(list, new Comparator<Collection>() {
            @Override
            public int compare(Collection a, Collection b) {
                if (a.isDefault) return -1;
                if (b.isDefault) return 1;
                return a.name.compareToIgnoreCase(b.name);
            }
        });

        return list;
    }

    private static void addFolderIfImages(List<Collection> list, Set<String> seen, File dir, String name, boolean isDef) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        String cPath = getCanonical(dir);
        if (seen.contains(cPath)) return;

        List<File> images = getImagesInFolder(dir);
        if (!images.isEmpty()) {
            seen.add(cPath);
            list.add(new Collection(cPath, name, dir, images, isDef));
        }
    }

    private static List<File> getImagesInFolder(File folder) {
        File[] arr = folder.listFiles(IMAGE_FILTER);
        if (arr == null || arr.length == 0) {
            return Collections.emptyList();
        }
        List<File> list = new ArrayList<>(arr.length);
        Collections.addAll(list, arr);
        Collections.sort(list, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return list;
    }

    private static String getCanonical(File file) {
        try {
            return file.getCanonicalPath();
        } catch (Throwable t) {
            return file.getAbsolutePath();
        }
    }

    /**
     * Gets the currently active wallpaper collection.
     */
    public static Collection getActiveCollection(Context context) {
        List<Collection> all = scanCollections(context);
        if (all.isEmpty()) {
            File emptyDir = new File(context.getExternalFilesDir(null), "wallpapers");
            return new Collection("default", "Mặc định", emptyDir, new ArrayList<File>(), true);
        }
        SharedPreferences sp = context.getSharedPreferences(PREFS_COLLECTIONS, Context.MODE_PRIVATE);
        String activeId = sp.getString(KEY_ACTIVE_COLLECTION_ID, null);
        if (activeId != null) {
            for (Collection c : all) {
                if (activeId.equals(c.id)) {
                    return c;
                }
            }
        }
        return all.get(0);
    }

    /**
     * Computes the signature string expected by WallpaperPlayer.<init>
     * format: name:length;name:length;...
     */
    public static String computeSignature(List<File> files) {
        if (files == null || files.isEmpty()) return "";
        ArrayList<File> sorted = new ArrayList<>(files);
        Collections.sort(sorted, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getAbsolutePath().compareTo(b.getAbsolutePath());
            }
        });
        StringBuilder sb = new StringBuilder();
        for (File f : sorted) {
            if (f != null && f.isFile()) {
                sb.append(f.getName()).append(':').append(f.length()).append(';');
            }
        }
        return sb.toString();
    }

    /**
     * Adds a custom folder path to persistent collections.
     */
    public static void addCustomFolder(Context context, String path) {
        if (context == null || path == null) return;
        SharedPreferences sp = context.getSharedPreferences(PREFS_COLLECTIONS, Context.MODE_PRIVATE);
        Set<String> custom = sp.getStringSet(KEY_CUSTOM_FOLDERS, null);
        Set<String> updated = custom != null ? new HashSet<>(custom) : new HashSet<String>();
        if(!updated.add(path))return;
        sp.edit().putStringSet(KEY_CUSTOM_FOLDERS, updated).apply();
        invalidateCache();
    }

    /**
     * Finds the Collection that contains the specified file.
     * If the folder is not yet known, it registers it as a custom folder.
     */
    public static Collection findCollectionForFile(Context context, File file) {
        if (context == null || file == null) return null;
        File parent = file.getParentFile();
        if (parent == null) return null;
        String parentPath = getCanonical(parent);
        List<Collection> all = scanCollections(context);
        String filePath = getCanonical(file);
        // Default can also contain the legacy private MP4 outside its normal folder.
        for (Collection c : all) for (File member : c.files) {
            if (filePath.equals(getCanonical(member))) return c;
        }
        for (Collection c : all) {
            if (c.folder != null && parentPath.equals(getCanonical(c.folder))) {
                return c;
            }
        }
        if (parent.exists() && parent.isDirectory()) {
            return new Collection(parentPath, parent.getName(), parent, getImagesInFolder(parent), false);
        }
        return null;
    }

    /**
     * Synchronizes a chosen wallpaper entry to persistent preferences.
     * Ensures wallpaper_settings, wallpaper_collections, and wallpaper_rotation
     * are completely consistent across process death and cold restart.
     */
    public static void syncWallpaperChoice(Activity act, WallpaperPopup.Entry entry) {
        if(act==null||entry==null)return;
        if(entry.kind==1){
            LiveWallpaperPicker.selectImage(act);
            cancelActions(act);
            SharedPreferences settings=act.getSharedPreferences(PREFS_SETTINGS,0);
            settings.edit().putString("wallpaper_choice","none").putLong("settings_revision",settings.getLong("settings_revision",0)+1).apply();
            act.getSharedPreferences(PREFS_ROTATION,0).edit().remove("image").remove("changed").remove("collection").apply();
            if(act instanceof MainActivity)applyScannedFiles(((MainActivity)act).wallpaperPlayer,Collections.emptyList(),((MainActivity)act).started);
        }else if(entry.file!=null){
            Context app=act.getApplicationContext();File file=entry.file;
            prepare(act,()->choice(app,file),WallpaperCollectionHelper::publishChoice);
        }
    }

    private static final Handler UI=new Handler(Looper.getMainLooper());
    private static final WeakHashMap<Activity,Action> ACTIONS=new WeakHashMap<>();
    private static final WeakHashMap<Activity,WeakReference<WallpaperPopup>> POPUPS=new WeakHashMap<>();
    public static void onPopupCreated(Activity owner,WallpaperPopup popup){WeakReference<WallpaperPopup> previous=POPUPS.put(owner,new WeakReference<>(popup));WallpaperPopup old=previous==null?null:previous.get();if(old!=null&&old!=popup)old.cancelPending();}
    public static void onPopupClosed(Activity owner,WallpaperPopup popup,boolean continuation){WeakReference<WallpaperPopup> ref=POPUPS.get(owner);if(ref!=null&&ref.get()==popup){POPUPS.remove(owner);cancelActions(owner);if(!continuation&&owner instanceof MainActivity)WallpaperLoads.cancelTransition(((MainActivity)owner).wallpaperPlayer);}}
    public static boolean cancelPopup(Activity owner){if(owner instanceof MainActivity)WallpaperLoads.cancelTransition(((MainActivity)owner).wallpaperPlayer);WeakReference<WallpaperPopup> ref=POPUPS.remove(owner);WallpaperPopup popup=ref==null?null:ref.get();if(popup==null)return false;popup.cancelPending();return true;}
    private static final class Action {
        volatile boolean cancelled;Future<?> future;
        void cancel(){cancelled=true;if(future!=null){future.cancel(true);if(future instanceof Runnable)CollectionDialog.WORKER.remove((Runnable)future);}}
    }
    public static boolean cancelActions(Activity activity){Action action=ACTIONS.remove(activity);if(action==null)return false;action.cancel();return true;}
    private static <T> void prepare(Activity activity,Callable<T> work,BiConsumer<Activity,T> publish){
        if(activity.isFinishing()||activity.isDestroyed())return;
        cancelActions(activity);Action action=new Action();ACTIONS.put(activity,action);WeakReference<Activity> owner=new WeakReference<>(activity);
        action.future=CollectionDialog.WORKER.submit(()->{
            if(action.cancelled)return;T result=null;Exception failure=null;
            try{result=work.call();}catch(Exception error){failure=error;}
            final T output=result;final Exception problem=failure;
            UI.post(()->{Activity a=owner.get();if(a==null||action.cancelled||ACTIONS.get(a)!=action)return;ACTIONS.remove(a);
                if(a.isFinishing()||a.isDestroyed()||(a instanceof MainActivity&&!((MainActivity)a).started))return;
                if(problem!=null){android.util.Log.w("TvHome.Collections","Wallpaper action failed",problem);Toast.makeText(a,"Could not load wallpaper. Try again.",Toast.LENGTH_SHORT).show();return;}
                try{publish.accept(a,output);}catch(RuntimeException error){android.util.Log.w("TvHome.Collections","Wallpaper publication failed",error);Toast.makeText(a,"Could not apply wallpaper.",Toast.LENGTH_SHORT).show();}
            });
        });
    }
    private static final class Choice {
        final Collection collection;final File file;final String signature;
        Choice(Collection c,File f,String s){collection=c;file=f;signature=s;}
    }
    private static Choice choice(Context app,File file){
        if(!IMAGE_FILTER.accept(file))throw new IllegalArgumentException("Wallpaper unavailable");
        return new Choice(findCollectionForFile(app,file),file,computeSignature(Collections.singletonList(file)));
    }
    private static void publishChoice(Activity act,Choice choice){publishChoice(act,choice,false);}
    private static void publishChoice(Activity act,Choice choice,boolean transition){
        if(choice==null)return;
        Collection col=choice.collection;File file=choice.file;
        boolean targetVideo=WallpaperMedia.isVideo(file);
        if(targetVideo)LiveWallpaperPicker.selectVideo(act,file);else LiveWallpaperPicker.selectImage(act);
        if(col!=null){
            boolean known=false;List<Collection> cached=sCachedCollections;
            if(cached!=null)for(Collection item:cached)if(java.util.Objects.equals(item.id,col.id)){known=true;break;}
            if(!col.isDefault&&!known)addCustomFolder(act,col.folder.getAbsolutePath());
            act.getSharedPreferences(PREFS_COLLECTIONS,0).edit().putString(KEY_ACTIVE_COLLECTION_ID,col.id).apply();
        }
        act.getSharedPreferences(PREFS_ROTATION,0).edit().putString("collection",choice.signature).putString("image",file.getName()).putLong("changed",System.currentTimeMillis()).apply();
        SharedPreferences settings=act.getSharedPreferences(PREFS_SETTINGS,0);long revision=settings.getLong("settings_revision",0)+1;
        settings.edit().putString("wallpaper_choice",file.getAbsolutePath()).putLong("settings_revision",revision).apply();
        if(act instanceof MainActivity){MainActivity main=(MainActivity)act;applyScannedFiles(main.wallpaperPlayer,Collections.singletonList(file),main.started&&!transition&&!targetVideo);
            if(transition&&main.wallpaperPlayer!=null)main.wallpaperPlayer.transitioning=true;
            main.settingsRevision=revision;
            if(targetVideo){if(main.wallpaperPlayer!=null)main.wallpaperPlayer.active=main.started;LiveWallpaper.refresh(main);}}
    }
    public static void choosePopup(WallpaperPopup popup,Activity activity,WallpaperPopup.Entry entry){
        if(popup==null||activity==null||entry==null)return;
        if(entry.kind==0){cancelActions(activity);ControlPanelHelper.startWallpaperImport(popup,activity);return;}
        if(entry.kind==1){
            if(LiveWallpaper.hasVideoVisual(activity)){
                cancelActions(activity);
                popup.closeFromHelper(()->{
                    if(!activity.isFinishing()&&!activity.isDestroyed()
                            &&(!(activity instanceof MainActivity)||((MainActivity)activity).started))
                        syncWallpaperChoice(activity,entry);
                });
            }else{syncWallpaperChoice(activity,entry);popup.choosePrepared(entry);}
            return;
        }
        if(entry.file==null)return;
        Context app=activity.getApplicationContext();File file=entry.file;WeakReference<WallpaperPopup> target=new WeakReference<>(popup);
        prepare(activity,()->choice(app,file),(owner,selected)->{
            WallpaperPopup current=target.get();if(current==null||!current.readyForChoice())return;
            if(WallpaperMedia.isVideo(selected.file)||LiveWallpaper.hasVideoVisual(owner)){
                current.closeFromHelper(()->{
                    if(!owner.isFinishing()&&!owner.isDestroyed()
                            &&(!(owner instanceof MainActivity)||((MainActivity)owner).started))
                        publishChoice(owner,selected,false);
                });
            }else{publishChoice(owner,selected,true);current.choosePrepared(entry);}
        });
    }
    public static void importWallpaper(MainActivity activity,android.net.Uri uri){
        Context app=activity.getApplicationContext();
        prepare(activity,()->{File file=WallpaperMedia.copy(app,uri);if(file==null)throw new IllegalArgumentException("Unreadable wallpaper");invalidateCache();return choice(app,file);},
            (owner,selected)->{publishChoice(owner,selected);Toast.makeText(owner,"Wallpaper applied",Toast.LENGTH_SHORT).show();});
    }
    public static void showPopup(Activity activity){
        if(activity==null||activity.isFinishing()||activity.isDestroyed())return;
        cancelActions(activity);
        if(activity instanceof MainActivity)WallpaperLoads.cancelTransition(((MainActivity)activity).wallpaperPlayer);
        if(cachedCollections()!=null){WallpaperPopup.showPrepared(activity);return;}
        Context app=activity.getApplicationContext();prepare(activity,()->scanCollections(app,false),(owner,ignored)->WallpaperPopup.showPrepared(owner));
    }
    public static void rotateWallpaper(Activity activity){
        if(activity==null)return;
        if(activity instanceof MainActivity){com.gathin.tvhome.WallpaperPlayer p=((MainActivity)activity).wallpaperPlayer;if(p!=null&&p.transitioning)return;}
        Context app=activity.getApplicationContext();
        prepare(activity,()->{Collection col=getActiveCollection(app);if(col.files.size()<2)return null;
            String current=app.getSharedPreferences(PREFS_ROTATION,0).getString("image","");
            File next=com.gathin.tvhome.Shamsi.pick(col.files,current,"sequential",new java.util.Random());
            return next==null?null:choice(app,next);
        },(owner,selected)->{if(selected==null){showPopup(owner);return;}
            if(owner instanceof MainActivity && ((MainActivity)owner).wallpaperPlayer!=null && ((MainActivity)owner).wallpaperPlayer.transitioning)return;
            boolean animate=owner instanceof MainActivity && !WallpaperMedia.isVideo(selected.file) && !LiveWallpaper.hasVideoVisual(owner)
                && DockWallpaperTransition.canAnimate((MainActivity)owner,selected.file);
            publishChoice(owner,selected,animate);
            if(animate)DockWallpaperTransition.begin((MainActivity)owner,selected.file);
            boolean vi="vi".equals(Locale.getDefault().getLanguage());Toast.makeText(owner,(vi?"Đã đổi hình nền: ":"Wallpaper: ")+selected.file.getName(),Toast.LENGTH_SHORT).show();});
    }

    public static void rotateWallpaperSilent(Activity activity){
        if(activity==null)return;
        if(activity instanceof MainActivity){com.gathin.tvhome.WallpaperPlayer p=((MainActivity)activity).wallpaperPlayer;if(p!=null&&p.transitioning)return;}
        Context app=activity.getApplicationContext();
        prepare(activity,()->{Collection col=getActiveCollection(app);if(col.files.size()<2)return null;
            String current=app.getSharedPreferences(PREFS_ROTATION,0).getString("image","");
            File next=com.gathin.tvhome.Shamsi.pick(col.files,current,"sequential",new java.util.Random());
            return next==null?null:choice(app,next);
        },(owner,selected)->{if(selected==null)return;
            if(owner instanceof MainActivity && ((MainActivity)owner).wallpaperPlayer!=null && ((MainActivity)owner).wallpaperPlayer.transitioning)return;
            boolean animate=owner instanceof MainActivity && !WallpaperMedia.isVideo(selected.file) && !LiveWallpaper.hasVideoVisual(owner)
                && DockWallpaperTransition.canAnimate((MainActivity)owner,selected.file);
            publishChoice(owner,selected,animate);
            if(animate)DockWallpaperTransition.begin((MainActivity)owner,selected.file);});
    }

    /**
     * Sets the active collection by ID.
     */
    public static void setActiveCollection(Context context, String collectionId, boolean autoRotate) {
        if (context == null || collectionId == null) return;
        for (Collection item : scanCollections(context)) if (collectionId.equals(item.id)) {
            ArrayList<File> files = new ArrayList<>(item.files);
            Collections.sort(files, Comparator.comparing(File::getAbsolutePath));
            if (!files.isEmpty()) applyCollectionSnapshot(context, item, files,
                computeSignature(autoRotate ? files : Collections.singletonList(files.get(0))), autoRotate);
            return;
        }
    }

    /** Metadata has already been prepared on a worker. This publication performs no scan. */
    public static void applyCollectionSnapshot(Context context, Collection collection, List<File> files, String signature, boolean autoRotate) {
        if (context == null || collection == null || files == null || files.isEmpty()) return;
        if(context instanceof Activity)cancelActions((Activity)context);
        SharedPreferences rotation = context.getSharedPreferences(PREFS_ROTATION, 0);
        String current = rotation.getString("image", "");
        File target = files.get(0);
        if (autoRotate) for (File file : files) if (file.getName().equals(current)) {target = file;break;}
        boolean targetVideo = WallpaperMedia.isVideo(target);
        if (targetVideo) LiveWallpaperPicker.selectVideo(context, target); else LiveWallpaperPicker.selectImage(context);
        context.getSharedPreferences(PREFS_COLLECTIONS, 0).edit().putString(KEY_ACTIVE_COLLECTION_ID, collection.id).apply();
        SharedPreferences choices = context.getSharedPreferences(PREFS_SETTINGS, 0);
        long revision = choices.getLong("settings_revision", 0) + 1;
        SharedPreferences.Editor editor = choices.edit().putLong("settings_revision", revision);
        editor.putString("wallpaper_choice", autoRotate ? "auto" : target.getAbsolutePath());
        editor.apply();
        rotation.edit().putString("collection", signature).putString("image", target.getName()).putLong("changed", System.currentTimeMillis()).apply();
        WallpaperThumbLoader.prewarm(context.getApplicationContext(), files, 25);
        if (context instanceof MainActivity) {
            MainActivity main = (MainActivity) context;
            applyScannedFiles(main.wallpaperPlayer, autoRotate ? files : Collections.singletonList(target), main.started && !targetVideo);
            main.settingsRevision = revision;
            if (targetVideo) {
                if (main.wallpaperPlayer != null) main.wallpaperPlayer.active = main.started;
                LiveWallpaper.refresh(main);
            }
        }
    }

    static List<Collection> cachedCollections() {
        List<Collection> cached = sCachedCollections;
        return cached != null && SystemClock.elapsedRealtime() - sLastScanTime < CACHE_TTL_MS ? new ArrayList<>(cached) : null;
    }

    /**
     * Called by Config.load() to get the list of files to populate Config.wallpapers.
     */
    public static ArrayList<File> getImagesForConfig(Context context) {
        if (context == null) return new ArrayList<>();
        LiveWallpaperPicker.migrateSelection(context);
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return imagesForUiConfig(context, true);
        SharedPreferences choices = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE);
        String choice = choices.getString("wallpaper_choice", null);

        // 1. None (wallpaper disabled)
        if ("none".equals(choice)) {
            return new ArrayList<>();
        }

        // 2. Specific single image choice
        if (choice != null && !choice.isEmpty() && !"auto".equals(choice)) {
            File f = new File(choice);
            if (!f.exists() || !f.isFile()) {
                Collection activeCol = getActiveCollection(context);
                if (activeCol.folder != null) {
                    File inFolder = new File(activeCol.folder, choice);
                    if (inFolder.exists() && inFolder.isFile()) {
                        f = inFolder;
                    }
                }
                if (!f.exists() || !f.isFile()) {
                    File appWall = new File(context.getExternalFilesDir(null), "wallpapers");
                    File inApp = new File(appWall, choice);
                    if (inApp.exists() && inApp.isFile()) {
                        f = inApp;
                    }
                }
            }

            if (f.exists() && f.isFile()) {
                Collection col = findCollectionForFile(context, f);
                if (col != null) {
                    if(!col.isDefault)addCustomFolder(context,col.folder.getAbsolutePath());
                    context.getSharedPreferences(PREFS_COLLECTIONS, Context.MODE_PRIVATE)
                           .edit().putString(KEY_ACTIVE_COLLECTION_ID, col.id).apply();
                }
                ArrayList<File> single = new ArrayList<>(1);
                single.add(f);
                return single;
            }
        }

        // 3. Auto-rotation of entire active collection
        Collection activeCol = getActiveCollection(context);
        if (activeCol.files != null && !activeCol.files.isEmpty()) {
            ArrayList<File> sorted = new ArrayList<>(activeCol.files);
            Collections.sort(sorted, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return a.getAbsolutePath().compareTo(b.getAbsolutePath());
                }
            });
            return sorted;
        }

        return new ArrayList<>();
    }

    /** No directory enumeration during UI configuration loading. Rotation metadata
     * preserves the saved collection on a cold start until the worker refreshes it. */
    private static ArrayList<File> imagesForUiConfig(Context context, boolean refresh) {
        LiveWallpaperPicker.migrateSelection(context);
        ArrayList<File> result = new ArrayList<>();
        String choice = context.getSharedPreferences(PREFS_SETTINGS, 0).getString("wallpaper_choice", null);
        if ("none".equals(choice)) return result;
        String active = context.getSharedPreferences(PREFS_COLLECTIONS, 0).getString(KEY_ACTIVE_COLLECTION_ID, "default");
        File folder = "default".equals(active) || active == null
            ? new File(context.getExternalFilesDir(null), "wallpapers") : new File(active);
        List<Collection> cached = sCachedCollections;
        if (refresh && (cached == null || SystemClock.elapsedRealtime() - sLastScanTime >= CACHE_TTL_MS)) prewarmCollections(context);
        if (choice != null && !choice.isEmpty() && !"auto".equals(choice)) {
            File file = new File(choice);
            if (!file.isAbsolute()) file = new File(folder, choice);
            if (file.isFile()) {result.add(file);return result;}
        }
        if (cached != null) {
            Collection selected = null;
            for (Collection item : cached) if (java.util.Objects.equals(active, item.id)) {selected = item;break;}
            if (selected == null && !cached.isEmpty()) selected = cached.get(0);
            if (selected != null) {result.addAll(selected.files);Collections.sort(result, Comparator.comparing(File::getAbsolutePath));return result;}
        }
        String signature = context.getSharedPreferences(PREFS_ROTATION, 0).getString("collection", "");
        File legacy = "default".equals(active) ? WallpaperMedia.legacy(context) : null;
        for (String token : signature.split(";")) {
            int colon = token.lastIndexOf(':');if (colon <= 0) continue;
            String name = token.substring(0, colon);
            if (!name.contains("/") && !name.contains("\\") && !name.equals("..")) {
                File file = new File(folder, name);
                if (legacy != null && name.equals(legacy.getName()) && !file.isFile()) file = legacy;
                if (!result.contains(file)) result.add(file);
            }
        }
        Collections.sort(result, Comparator.comparing(File::getAbsolutePath));
        return result;
    }

    /**
     * Populates WallpaperPopup.pictures with entries from the active collection.
     */
    public static List<WallpaperPopup.Entry> getPopupEntries(Object popupObj, Activity act) {
        List<WallpaperPopup.Entry> result = new ArrayList<>();
        if (act == null) return result;

        Collection col = getActiveCollection(act);
        for (File f : col.files) {
            String lbl = formatLabel(f, col);
            result.add(new WallpaperPopup.Entry(3, f, lbl));
        }
        return result;
    }

    private static String formatLabel(File file, Collection col) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        if (name.startsWith("personal-")) {
            name = name.substring("personal-".length());
        }
        return name;
    }

    /**
     * Determines whether a WallpaperPopup.Entry matches the current wallpaper choice.
     */
    public static boolean isEntryInUse(WallpaperPopup.Entry entry, String inUse) {
        if (entry == null || inUse == null) return false;
        if (entry.kind == 1 && "none".equals(inUse)) return true;
        if (entry.file != null) {
            return entry.file.getAbsolutePath().equals(inUse) || entry.file.getName().equals(inUse);
        }
        return false;
    }

    /**
     * Finds the File matching the inUse string among popup entries.
     */
    public static File getFileInUse(List<WallpaperPopup.Entry> entries, String inUse) {
        if (entries == null || inUse == null) return null;
        for (WallpaperPopup.Entry e : entries) {
            if (e != null && e.kind == 3 && e.file != null) {
                if (e.file.getAbsolutePath().equals(inUse) || e.file.getName().equals(inUse)) {
                    return e.file;
                }
            }
        }
        return null;
    }

    /**
     * Handles wallpaper selection in WallpaperPopup.
     */
    public static void onWallpaperChosen(final WallpaperPopup popup, final Activity act, final WallpaperPopup.Entry entry) {
        if (entry == null || act == null) return;
        if (entry.kind == 0) {
            ControlPanelHelper.startWallpaperImport(popup, act);
            return;
        }

        syncWallpaperChoice(act, entry);

        SharedPreferences sp = act.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE);
        long rev = sp.getLong("settings_revision", 0L) + 1L;
        sp.edit().putLong("settings_revision", rev).apply();

        if (act instanceof MainActivity) {
            MainActivity main = (MainActivity) act;
            main.settingsRevision = rev;
            if (main.wallpaperPlayer != null) {
                main.wallpaperPlayer.active = true;
                main.wallpaperPlayer.update();
            }
        }

        if (popup != null) {
            try {
                popup.closeFromHelper(null);
            } catch (Throwable ignored) {}
        }

        ControlPanelHelper.ensureClockPillVisible(act);

        String name = entry.file != null ? entry.file.getName() : "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        boolean vi = "vi".equals(Locale.getDefault().getLanguage());
        Toast.makeText(act, vi ? ("Đã áp dụng hình nền: " + name) : ("Wallpaper applied: " + name), Toast.LENGTH_SHORT).show();
    }

    /**
     * Attaches the Collection button to the bottom action bar of WallpaperPopup.
     */
    public static void attachCollectionButton(final Object popupObj, final LinearLayout buttonBar) {
        if (buttonBar == null) return;
        final Context ctx = buttonBar.getContext();
        if (!(ctx instanceof Activity)) return;
        final Activity act = (Activity) ctx;

        final float dp = ctx.getResources().getDisplayMetrics().density;
        final int size = Math.round(48.0f * dp);
        final int margin = Math.round(20.0f * dp);

        final boolean vi = "vi".equals(Locale.getDefault().getLanguage());
        final ImageView btn = new ImageView(ctx);
        btn.setContentDescription(vi ? "Bộ sưu tập" : "Collections");
        btn.setFocusable(true);
        btn.setClickable(true);
        // Match WallpaperPopup.glassButton, including its stateful focus/press surface.
        btn.setScaleType(ImageView.ScaleType.CENTER);
        btn.setBackground(new ClearGlass.Plate(size / 2.0f, dp, -1));
        btn.setImageDrawable(new CollectionDialog.FolderIcon(dp,18));

        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CollectionDialog.show(act, popupObj, v);
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginStart(margin);
        buttonBar.addView(btn, lp);
    }

    /**
     * Displays a liquid glass collection picker dialog optimized for TV remote D-pad navigation.
     */
    public static void showCollectionDialog(final Activity act, final Object popupObj) {
        CollectionDialog.show(act, popupObj, act == null ? null : act.getCurrentFocus());
    }
}
