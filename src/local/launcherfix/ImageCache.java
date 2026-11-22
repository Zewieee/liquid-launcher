package local.launcherfix;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.util.LruCache;
import com.gathin.tvhome.AppAdapter;
import com.gathin.tvhome.Shamsi;
import com.gathin.tvhome.IconPacks;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** ConstantState only: never retains View callbacks. Returned Drawables are independent. */
public final class ImageCache {
    private static final int LIMIT=3*1024*1024;
    private static final class Stored {final Drawable.ConstantState state;final int bytes;Stored(Drawable.ConstantState s,int b){state=s;bytes=b;}}
    private static final LruCache<String,Stored> IMAGES=new LruCache<String,Stored>(LIMIT){@Override protected int sizeOf(String key,Stored value){return value.bytes;}};
    private static final LruCache<String,String> LABELS=new LruCache<>(96);
    private static final Map<Drawable,String> TOKENS=new WeakHashMap<>();
    private static final Map<String,Long> VERSIONS=new HashMap<>();
    private static long serial,hits;
    private ImageCache(){}
    public static String configuration(Context c){return c.getResources().getConfiguration().toString();}
    private static synchronized long version(String pkg){
        Long value=VERSIONS.get(pkg);
        if(value==null){if(VERSIONS.size()>512){IMAGES.evictAll();LABELS.evictAll();VERSIONS.clear();}value=++serial;VERSIONS.put(pkg,value);}
        return value;
    }
    private static String key(Context c,ResolveInfo info){
        String pkg=info.activityInfo.packageName;
        return pkg+"|"+version(pkg)+"|"+info.activityInfo.name+"|"+info.activityInfo.applicationInfo.sourceDir+"|"+configuration(c);
    }
    private static int weight(Drawable d){
        if(d instanceof BitmapDrawable)return Math.max(16384,((BitmapDrawable)d).getBitmap().getAllocationByteCount());
        if(d instanceof LayerDrawable){int total=0;LayerDrawable l=(LayerDrawable)d;for(int n=0;n<l.getNumberOfLayers();n++)total+=weight(l.getDrawable(n));return Math.max(16384,total);}
        return 65536;
    }
    private static Drawable hit(Context c,String key){
        return hit(c.getResources(),key);
    }
    private static Drawable hit(android.content.res.Resources resources,String key){
        Stored stored=IMAGES.get(key);if(stored==null)return null;
        Drawable d=stored.state.newDrawable(resources).mutate();synchronized(TOKENS){TOKENS.put(d,key);}hits++;return d;
    }
    private static Drawable keep(String key,Drawable d){
        if(d==null)return null;
        Drawable.ConstantState state=d.getConstantState();int bytes=weight(d);
        if(state!=null && bytes<=1024*1024)IMAGES.put(key,new Stored(state,bytes));
        synchronized(TOKENS){TOKENS.put(d,key);}return d;
    }
    public static Drawable icon(Context c,ResolveInfo info,PackageManager pm){
        if(info==null)return null;
        String key=key(c,info)+"|icon";Drawable d=hit(c,key);return d!=null?d:keep(key,info.loadIcon(pm));
    }
    public static Drawable resource(Context c,int id){
        String key="@resource|"+c.getPackageName()+"|"+id+"|"+configuration(c);Drawable d=hit(c,key);return d!=null?d:keep(key,c.getDrawable(id));
    }
    public static Drawable packForApp(Context c,String pack,android.content.ComponentName component){
        if(pack==null||component==null)return null;
        String key=pack+"|"+version(pack)+"|packApp|"+component.flattenToString()+"|"+configuration(c);
        Drawable d=hit(c,key);return d!=null?d:keep(key,IconPacks.forApp(c,pack,component));
    }
    public static Drawable packDrawable(IconPacks.Data data,String name){
        if(data==null)return null;
        String key=data.packageName+"|"+version(data.packageName)+"|packDrawable|"+data.updated+"|"+name+"|"+data.resources.getConfiguration();
        Drawable d=hit(data.resources,key);return d!=null?d:keep(key,IconPacks.drawable(data,name));
    }
    public static Drawable banner(AppAdapter adapter,PackageManager pm,ResolveInfo info){
        String key=key(adapter.ctx,info)+"|banner";Drawable d=hit(adapter.ctx,key);return d!=null?d:keep(key,adapter.loadBanner(pm,info));
    }
    public static CharSequence label(Context c,ResolveInfo info,PackageManager pm){
        String key=key(c,info)+"|label";String value=LABELS.get(key);
        if(value==null){value=String.valueOf(info.loadLabel(pm));LABELS.put(key,value);}return value;
    }
    public static String token(Drawable d){
        if(d==null)return "null";
        synchronized(TOKENS){String token=TOKENS.get(d);if(token!=null)return token;}
        if(d instanceof BitmapDrawable){android.graphics.Bitmap b=((BitmapDrawable)d).getBitmap();return "bitmap:"+System.identityHashCode(b)+":"+b.getGenerationId();}
        if(d instanceof ColorDrawable)return "color:"+((ColorDrawable)d).getColor();
        Drawable.ConstantState state=d.getConstantState();return "state:"+System.identityHashCode(state==null?d:state);
    }
    public static Drawable transform(Context c,Drawable source,int color,String material){
        if(source==null||color==0)return source;
        String key="transform|"+token(source)+"|"+color+"|"+material+"|"+configuration(c);
        Drawable d=hit(c,key);return d!=null?d:keep(key,Shamsi.transform(c,source,color,material));
    }
    public static synchronized void invalidate(String pkg){
        VERSIONS.put(pkg,++serial);
        for(String key:IMAGES.snapshot().keySet())if(key.startsWith(pkg+"|")||key.contains("|"+pkg+"|"))IMAGES.remove(key);
        for(String key:LABELS.snapshot().keySet())if(key.startsWith(pkg+"|"))LABELS.remove(key);
    }
    public static void trim(int level){if(level>=20||level==10||level==15){IMAGES.evictAll();LABELS.evictAll();}else if(level>=5)IMAGES.trimToSize(LIMIT/2);}
}
