package com.gathin.tvhome;
import android.app.Activity;
import java.util.*;
import androidx.recyclerview.widget.RecyclerView;
import android.widget.PopupWindow;
public final class MainActivity extends Activity {
    public static String lastFocusedKey;
    public static int lastFocused;
    public static int COLUMNS;
    public boolean singleRow;
    public AppAdapter adapter;
    public Object tileLoadState;
    public Config tileConfig;
    public boolean advancedEffects,reducedMotion,focusOvershoot,animations,cinematic;
    public float density;
    public int glassAccent;
    public HashMap heroArtCache;
    public String heroArtKey;
    public android.widget.ImageView heroArt;
    public float heroArtAlpha;
    public void fadeHeroArt(int duration){}
    public List cards;
    public int packageSequence;
    public long settingsRevision;
    public boolean reordering,dockEditing,started,tilesReady,moduleCards,libraryFocusPending;
    public android.app.Dialog cardPrompt;
    public TileRefresh tileRefresh;
    public RecyclerView grid; public android.widget.FrameLayout root;
    public GlassView glass;
    public boolean glassShown;
    public WallpaperPlayer wallpaperPlayer;
    public PopupWindow openPopup;
    public AppLibraryDialog appLibrary;
    public Object[] buildTilesOffThread(AppAdapter snapshot){return null;}
    public void applyLoadedTiles(AppAdapter snapshot,List entries,List cards,int sequence){}
    public void reloadApps(boolean refocus,boolean reload,boolean animate){}
    public void reloadTiles(){}
    public void endReorder(){}
    public boolean beginReorder(){return false;}
    public void focusTile(int position){}
    public void focusLibrary(){}
    public void onTileFocused(android.view.View v,int pos){}
}
