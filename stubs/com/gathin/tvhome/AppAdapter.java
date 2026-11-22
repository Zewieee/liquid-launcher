package com.gathin.tvhome;
import android.content.Context;
import android.content.ComponentName;
import java.util.*;
public final class AppAdapter {
    public interface TileListener {}
    public TileListener listener;
    public Context ctx;
    public boolean advancedEffects,reducedMotion,focusOvershoot,animations;
    public float restNameAlpha;
    public HashSet leanbackPackages;
    public int dockSize,savedDocked,docked,emptySlots;
    public Map savedRanks;
    public List<String> favourites;
    public ArrayList<Entry> items;
    public AppAdapter(Context c,TileListener listener,int size,Config config){}
    public String keyAt(int position){return null;}
    public int positionOf(String key){return 0;}
    public int ambientColorAt(int position){return 0;}
    public android.graphics.drawable.Drawable loadBanner(android.content.pm.PackageManager pm,android.content.pm.ResolveInfo info){return null;}
    public void tileInserted(int at){}public void tileRemoved(int at){}public void tileMoved(int from,int to){}public void tileChanged(int at){}
    public static final class Holder extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
        public android.widget.ImageView banner,icon;
        public android.view.View card;
        public android.graphics.drawable.Drawable cardBackground;
        public android.widget.TextView label,name;
        public Holder(android.view.View v){super(v);}
    }
    public static final class Entry {
        public final ComponentName component;public final String pkg,name,label;
        public String material;
        public final int kind,seedAccent;public int iconColor;public boolean centeredIcon;
        public final android.graphics.drawable.Drawable banner,icon;
        public Entry(int kind,String label,ComponentName component,android.graphics.drawable.Drawable banner,
                android.graphics.drawable.Drawable icon,String suffix,int accent){
            this.kind=kind;this.label=label;this.component=component;this.banner=banner;this.icon=icon;
            this.pkg=component.getPackageName();this.name=suffix==null?label:label+" · "+suffix;
            this.seedAccent=accent;this.material="normal";
        }
    }
}
