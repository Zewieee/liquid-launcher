package local.launcherfix;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ChangedPackages;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.Log;
import android.view.View;
import com.gathin.tvhome.AppAdapter;
import com.gathin.tvhome.Cards;
import com.gathin.tvhome.IconAppearanceActivity;
import com.gathin.tvhome.LiquidTune;
import com.gathin.tvhome.MainActivity;
import com.gathin.tvhome.Shamsi;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Publishes adapter data only on main; workers own a separate adapter and immutable inputs. */
public final class TileLoader {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,
        new LinkedBlockingQueue<Runnable>(), task -> new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            task.run();
        },"launcher-app-loader"));
    private static final ThreadLocal<Request> CURRENT = new ThreadLocal<>();
    private static final AtomicLong SKY_SERIAL = new AtomicLong();
    private static volatile WeakReference<MainActivity> ACTIVE;
    static {WORKER.allowCoreThreadTimeOut(true);}
    private TileLoader() {}

    private static final class State {
        volatile long generation;
        long focusSerial, requestFocusSerial, skySerial = SKY_SERIAL.get();
        boolean running, pending, refocus, animate, alive=true, readyBeforeLoad;
        int completed, discarded;
        String lastWorkerName;
        Future<?> future;
    }
    private static final class Request {
        final MainActivity activity;
        final AppAdapter adapter;
        final long generation, focusSerial;
        final boolean refocus, animate;
        final int sequence;
        final Set shortcuts, removed;
        final TreeMap records;
        final FolderStore.Snapshot folders;
        Request(MainActivity a,State s) {
            activity=a; generation=s.generation; focusSerial=s.requestFocusSerial;
            refocus=s.refocus; animate=s.animate; sequence=a.packageSequence;
            AppAdapter live=a.adapter;
            adapter=new AppAdapter(a,live.listener,live.dockSize,a.tileConfig);
            adapter.savedRanks=new HashMap(live.savedRanks);
            adapter.savedDocked=live.savedDocked;
            // These tiny state files remain serialized with UI writes (AtomicFile is not a lock).
            shortcuts=readState(a,false); removed=readState(a,true);
            records=a.moduleCards ? Cards.load(new File(a.getFilesDir(),"cards.json")) : new TreeMap();
            folders=FolderTiles.read(a);
        }
    }
    private static void mainOnly() {
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Tile publication must run on main");
    }
    private static Set readState(MainActivity a,boolean removed) {
        try {return new HashSet(Shamsi.readStrict(Shamsi.store(a,removed)));}
        catch(IOException|RuntimeException error) {Log.w("TvHome.AsyncApps","Cannot snapshot shortcuts",error);return Collections.emptySet();}
    }
    public static List<ResolveInfo> queryApps(PackageManager pm,Intent intent,int flags){
        Request request=CURRENT.get();return request==null?pm.queryIntentActivities(intent,flags):Catalog.query(request.activity,pm,intent,flags);
    }
    public static Set readShortcuts(AtomicFile file) throws IOException {
        Request request=CURRENT.get();
        if(request==null)return Shamsi.readStrict(file);
        String name=file.getBaseFile().getName();
        if(name.equals("library_apps.txt"))return request.shortcuts;
        if(name.equals("library_home_removed.txt"))return request.removed;
        throw new IllegalArgumentException("Unexpected shortcut store");
    }
    public static TreeMap readCards(File file) {
        Request request=CURRENT.get();
        return request==null ? Cards.load(file) : new TreeMap(request.records);
    }
    public static FolderStore.Snapshot folderSnapshot(Context context) {
        Request request=CURRENT.get();
        return request==null?FolderTiles.read(context):request.folders;
    }
    private static State state(MainActivity a) {
        ACTIVE=new WeakReference<>(a);
        State s=(State)a.tileLoadState;
        if(s==null) {
            s=new State(); a.tileLoadState=s;
        }
        return s;
    }
    public static MainActivity currentActivity() {
        WeakReference<MainActivity> reference=ACTIVE;
        return reference==null?null:reference.get();
    }
    public static long interactionSerial(MainActivity a) {
        State s=(State)a.tileLoadState;return s==null?0:s.focusSerial;
    }
    public static boolean canReorder(MainActivity a) {
        if(a==null || !a.started || !a.tilesReady || a.tileRefresh==null || a.tileRefresh.stale)return false;
        State s=(State)a.tileLoadState;
        // cancel() can restore tilesReady while an obsolete worker is still running.
        return s==null || (s.alive && !s.running && !s.pending);
    }
    public static void interacted(MainActivity a) {
        mainOnly();State s=(State)a.tileLoadState;
        if(s!=null)s.focusSerial++;
    }
    public static void onResume(MainActivity a) {
        mainOnly();
        MemoryTrimmer.onResume(a);
        ControlPanelHelper.ensureClockPillVisible(a);
        DockStats.onResume(a);
        FontChoice.apply(a.getWindow().getDecorView());
        a.libraryFocusPending = false;
        if (a.appLibrary != null) {
            try { a.appLibrary.close(); } catch (Throwable ignored) {}
            a.appLibrary = null;
            a.openPopup = null;
        }
        if (AppLibraryBridge.launchedFromLibrary) {
            AppLibraryBridge.launchedFromLibrary = false;
            MainActivity.lastFocused = 0;
            MainActivity.lastFocusedKey = null;
            if (a.grid != null) {
                a.grid.scrollToPosition(0);
            }
            a.focusTile(0);
        } else if (MainActivity.lastFocusedKey != null && MainActivity.lastFocusedKey.contains("btnLibrary")) {
            MainActivity.lastFocused = 0;
            MainActivity.lastFocusedKey = null;
            if (a.grid != null) {
                a.grid.scrollToPosition(0);
            }
            a.focusTile(0);
        } else if (MainActivity.lastFocused < 0) {
            MainActivity.lastFocused = 0;
            MainActivity.lastFocusedKey = null;
        }
        if (!a.tilesReady && a.adapter.items.isEmpty()) {
            MainActivity.lastFocused = 0;
            MainActivity.lastFocusedKey = null;
        }
        State s=state(a);
        boolean stale=a.tileRefresh.stale;
        boolean refocus=a.tileRefresh.refocus;
        a.tileRefresh.stale=false;
        a.tileRefresh.refocus=false;
        long sky=SKY_SERIAL.get();
        boolean skyChanged=s.skySerial!=sky;
        s.skySerial=sky;
        if(s.running||s.pending) {
            if(refocus) {
                s.refocus=true;
                s.requestFocusSerial=s.focusSerial;
            }
            if(stale||skyChanged)request(a,s.refocus,s.animate);
            return;
        }
        if(stale) {
            boolean initial=!a.tilesReady && a.adapter.items.isEmpty();
            a.reloadApps(initial||refocus,true,initial);
            return;
        }
        boolean pkgChanged=false;
        if(a.packageSequence>0) {
            ChangedPackages changed=a.getPackageManager().getChangedPackages(a.packageSequence);
            if(changed!=null) {
                a.packageSequence=changed.getSequenceNumber();
                List<String> names=changed.getPackageNames();
                if(names!=null && !names.isEmpty()) {
                    for(String pkg:names)Catalog.touch(pkg);
                    pkgChanged=true;
                }
            }
        }
        if(pkgChanged||skyChanged)a.reloadApps(refocus,true,false);
        else if(refocus)a.reloadApps(true,false,false);
    }
    public static void onSkyChanged(Context c) {
        if(c instanceof IconAppearanceActivity) {
            String pkg=((IconAppearanceActivity)c).appPackage;
            if(pkg!=null && !pkg.isEmpty())ImageCache.invalidate(pkg);
        }
        long serial=SKY_SERIAL.incrementAndGet();
        WeakReference<MainActivity> ref=ACTIVE;
        MainActivity a=c instanceof MainActivity ? (MainActivity)c : (ref==null ? null : ref.get());
        if(a!=null && !(c instanceof IconAppearanceActivity)) {
            Runnable r=()->{
                if(a.isDestroyed()||a.isFinishing())return;
                State s=state(a);
                s.skySerial=serial;
                if(a.started)a.reloadApps(false,true,false);
                else a.tileRefresh.stale=true;
            };
            if(Looper.myLooper()==Looper.getMainLooper())r.run();
            else MAIN.post(r);
        }
    }
    public static void onIconPopupClosed(Activity activity) {
        if(activity==null||activity.isFinishing()||activity.isDestroyed())return;
        if(activity instanceof MainActivity) {
            MainActivity a=(MainActivity)activity;
            LiquidTune.showFocusGlass(a.glass);
            onSkyChanged(a);
            if(a.grid!=null) {
                for(int i=0,n=a.grid.getChildCount();i<n;i++)a.grid.getChildAt(i).invalidate();
                a.grid.invalidate();
            }
        }
    }
    public static void request(MainActivity a,boolean refocus,boolean animate) {
        mainOnly();
        if(a.isDestroyed()||a.isFinishing())return;
        if(a.reordering)a.endReorder();
        State s=state(a);
        if(!s.alive)return;
        s.generation++; s.pending=true; s.refocus=refocus; s.animate=animate;s.requestFocusSerial=s.focusSerial;
        if(!s.running)start(a,s);
    }
    private static void start(MainActivity a,State s) {
        if(!s.alive||!s.pending||!a.started)return;
        final Request request;
        try {request=new Request(a,s);}
        catch(RuntimeException|LinkageError|OutOfMemoryError error) {
            s.pending=false; a.tileRefresh.stale=true;
            Log.e("TvHome.AsyncApps","Cannot snapshot app load",error);return;
        }
        s.pending=false; s.running=true; s.readyBeforeLoad=a.tilesReady; a.tilesReady=false;
        s.future=WORKER.submit(() -> {
            Object[] result=null; Throwable failure=null; int sequence=request.sequence;
            s.lastWorkerName=Thread.currentThread().getName();
            try {
                if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
                if(request.generation!=s.generation)throw new java.util.concurrent.CancellationException();
                // Sample before enumeration, so changes during the build remain visible to the next refresh.
                ChangedPackages changed=a.getPackageManager().getChangedPackages(sequence);
                if(changed!=null)sequence=changed.getSequenceNumber();
                CURRENT.set(request);
                result=a.buildTilesOffThread(request.adapter);
            } catch(RuntimeException|LinkageError|OutOfMemoryError error) {failure=error;}
            finally {CURRENT.remove();}
            final Object[] built=result; final Throwable error=failure; final int observed=sequence;
            MAIN.post(() -> complete(a,s,request,built,observed,error));
        });
    }
    private static void complete(MainActivity a,State s,Request request,Object[] data,int sequence,Throwable error) {
        mainOnly(); s.running=false; s.future=null;
        if(!s.alive||a.isDestroyed()||a.isFinishing()) {s.discarded++;return;}
        if(request.generation!=s.generation) {s.discarded++;if(s.pending)start(a,s);return;}
        if(!a.started) {s.discarded++;a.tileRefresh.stale=true;a.tilesReady=s.readyBeforeLoad;return;}
        if(error!=null) {
            a.tilesReady=s.readyBeforeLoad; a.tileRefresh.stale=true;
            Log.e("TvHome.AsyncApps","App load failed; retaining previous list",error);return;
        }
        String key=MainActivity.lastFocusedKey; int position=MainActivity.lastFocused;
        a.libraryFocusPending=false;
        if (AppLibraryBridge.launchedFromLibrary || (key != null && key.contains("btnLibrary"))) {
            AppLibraryBridge.launchedFromLibrary = false;
            key = null;
            position = 0;
        }
        if (position < 0 || (request.adapter != null && request.adapter.items != null && position >= request.adapter.items.size())) {
            position = 0;
            key = null;
        }
        boolean refocus=request.refocus && request.focusSerial==s.focusSerial;
        a.applyLoadedTiles(request.adapter,(List)data[0],(List)data[1],sequence);
        MainActivity.lastFocusedKey=key; MainActivity.lastFocused=position;
        a.libraryFocusPending=false;
        s.completed++;
        // Reuse the existing main-thread focus/animation/prompt path with loading disabled.
        a.reloadApps(refocus,false,request.animate);
    }
    public static void cancel(MainActivity a) {
        mainOnly(); State s=(State)a.tileLoadState;
        if(s==null)return;
        s.generation++;s.pending=false;
        if(s.running)a.tilesReady=s.readyBeforeLoad;
        // Do not cancel a queued Future: its completion releases the running slot.
        // Its generation is invalid, so it can finish without touching live adapter data.
    }
    public static void stopped(MainActivity a) {
        WallpaperCollectionHelper.cancelActions(a);
        mainOnly();State s=(State)a.tileLoadState;
        if(s!=null && (s.running||s.pending)) {cancel(a);a.tileRefresh.stale=true;}
    }
    public static void close(MainActivity a) {
        mainOnly();State s=(State)a.tileLoadState;
        WeakReference<MainActivity> ref=ACTIVE;
        if(ref!=null && ref.get()==a)ACTIVE=null;
        ControlPanelHelper.onDestroy(a);
        // Shared handler: each completion rejects its own destroyed Activity.
        if(s==null)return;
        cancel(a);s.alive=false;
        if(s.future!=null) {
            s.future.cancel(true);
            if(s.future instanceof Runnable)WORKER.remove((Runnable)s.future);
        }
        a.tileLoadState=null;
    }
}
