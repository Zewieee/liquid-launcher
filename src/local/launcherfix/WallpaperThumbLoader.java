package local.launcherfix;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Process;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.LruCache;
import com.gathin.tvhome.WallpaperPopup;
import java.io.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Popup-owned thumbnail jobs; trim never cancels a visible picker. */
public final class WallpaperThumbLoader {
    private static final String TAG="TvHome.WallThumb",DISK_CACHE_DIR="wall_thumbs";
    private static final int WORKERS=Math.max(2,Math.min(4,Runtime.getRuntime().availableProcessors()));
    private static final long DISK_LIMIT=32L*1024*1024;
    private static final Object LOCK=new Object();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static long cacheEpoch;
    private static boolean cacheWrites=true;
    private static final AtomicLong nextDiskTrim=new AtomicLong();
    private static final AtomicInteger diskWrites=new AtomicInteger();
    private static final ConcurrentHashMap<String,String> sFastKeyCache=new ConcurrentHashMap<>();
    private static final ThreadPoolExecutor sExecutor=new ThreadPoolExecutor(WORKERS,WORKERS,30,TimeUnit.SECONDS,
        new LinkedBlockingQueue<Runnable>(),new ThreadFactory(){private final AtomicInteger count=new AtomicInteger();
            public Thread newThread(Runnable task){return new Thread(()->{Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);task.run();},"WallThumb-"+count.incrementAndGet());}});
    static {sExecutor.allowCoreThreadTimeOut(true);}
    public static final class ThumbResult {
        public final Bitmap bitmap;public final int accentColor;
        public ThumbResult(Bitmap bitmap,int accentColor){this.bitmap=bitmap;this.accentColor=accentColor;}
    }
    private static final LruCache<String,ThumbResult> sMemCache=new LruCache<String,ThumbResult>(6*1024*1024){
        protected int sizeOf(String key,ThumbResult value){return value.bitmap.getAllocationByteCount();}
    };
    private static final Map<Object,Session> sessions=new WeakHashMap<>();
    private static final class Session {
        final WeakReference<Activity> activity;final WeakReference<Object> picker;
        final ArrayList<WallpaperPopup.Entry> entries;final int width,height;
        final Set<Integer> loaded=new HashSet<>();
        final String[] variants;
        int center=-1,generation,cursor,expectedCount;int[] order=new int[0];boolean running;
        Runnable task;volatile boolean cancelled;
        Session(Activity a,Object p,List<WallpaperPopup.Entry> e){
            activity=new WeakReference<>(a);picker=new WeakReference<>(p);entries=new ArrayList<>(e);
            width=Math.max(1,invokeInt(p,"thumbW",480));height=Math.max(1,invokeInt(p,"thumbH",270));
            variants=new String[entries.size()];
            int valid=0;
            for(int i=0;i<entries.size();i++){
                WallpaperPopup.Entry entry=entries.get(i);
                if(entry!=null&&entry.kind==3&&entry.file!=null){
                    valid++;
                    String cachedKey=sFastKeyCache.get(entry.file.getPath());
                    if(cachedKey!=null)variants[i]=variant(cachedKey,width,height);
                }
            }
            expectedCount=valid;
        }
        boolean alive(){Activity a=activity.get();return !cancelled&&a!=null&&!a.isFinishing()&&!a.isDestroyed()&&picker.get()!=null;}
        void cancel(){cancelled=true;if(task!=null)sExecutor.remove(task);for(int index:loaded)clear(picker.get(),index);loaded.clear();}
    }
    static int[] window(int center,int count){
        if(count<=0)return new int[0];
        int size=Math.min(13,count),n=0;int[] order=new int[size];order[n++]=Math.floorMod(center,count);
        for(int distance=1;n<size;distance++){
            order[n++]=Math.floorMod(center+distance,count);
            if(n<size)order[n++]=Math.floorMod(center-distance,count);
        }
        return order;
    }
    static int[] fullOrder(int center,int count){
        if(count<=0)return new int[0];
        int[] order=new int[count];int n=0;order[n++]=Math.floorMod(center,count);
        for(int distance=1;n<count;distance++){
            order[n++]=Math.floorMod(center+distance,count);
            if(n<count)order[n++]=Math.floorMod(center-distance,count);
        }
        return order;
    }
    private static final Map<Class<?>,Map<String,Method>> bindings=new ConcurrentHashMap<>();
    private static Map<String,Method> binding(Object picker){
        return bindings.computeIfAbsent(picker.getClass(),type->{Map<String,Method> found=new HashMap<>();
            for(Method m:type.getMethods()){String name=m.getName();int n=m.getParameterTypes().length;
                if((name.equals("clearThumb")&&n==1)||(name.equals("setThumb")&&n==3)||((name.equals("thumbW")||name.equals("thumbH")||name.equals("current"))&&n==0)){
                    m.setAccessible(true);found.put(name,m);}}
            return found;});
    }
    private static int invokeInt(Object picker,String name,int fallback){try{Method m=binding(picker).get(name);return m==null?fallback:(Integer)m.invoke(picker);}catch(Exception e){Log.w(TAG,"Picker "+name,e);return fallback;}}
    private static void deliver(Object picker,int index,ThumbResult result){
        if(picker==null||result==null||result.bitmap.isRecycled())return;
        try{Method m=binding(picker).get("setThumb");if(m!=null)m.invoke(picker,index,result.bitmap,result.accentColor);}
        catch(Exception e){Log.w(TAG,"Picker setThumb "+index,e);}
    }
    private static void clear(Object picker,int index){
        if(picker==null)return;
        try{Method method=binding(picker).get("clearThumb");if(method!=null)method.invoke(picker,index);}
        catch(Exception error){Log.w(TAG,"Picker clearThumb",error);}
    }
    public static void loadThumbnails(Object popup,Activity activity,List<WallpaperPopup.Entry> entries,Object picker){
        if(popup==null||activity==null||entries==null||picker==null)return;
        Session session=new Session(activity,picker,entries);
        synchronized(LOCK){Session previous=sessions.put(popup,session);if(previous!=null)previous.cancel();cacheWrites=true;}
        // Deliver any already-known memory cache hits without touching disk on UI thread
        Object pickerObj=session.picker.get();
        int initialCenter=invokeInt(picker,"current",0);
        int[] initialOrder=fullOrder(initialCenter,session.entries.size());
        if(pickerObj!=null){
            for(int index:initialOrder){
                String var=session.variants[index];
                if(var==null)continue;
                ThumbResult cached;
                synchronized(LOCK){cached=sMemCache.get(var);}
                if(cached!=null&&!cached.bitmap.isRecycled()){
                    deliver(pickerObj,index,cached);
                    session.loaded.add(index);
                }
            }
        }
        updateWindow(session,initialCenter);
    }
    public static void onPickerMoved(Object picker){
        Session found=null;
        synchronized(LOCK){for(Session session:sessions.values())if(session.picker.get()==picker){found=session;break;}}
        if(found!=null){
            if(found.loaded.size()>=found.expectedCount)return;
            updateWindow(found,invokeInt(picker,"current",0));
        }
    }
    private static void updateWindow(Session session,int center){
        if(!session.alive()||session.entries.isEmpty()||session.center==center)return;
        if(session.loaded.size()>=session.expectedCount)return;
        session.center=center;session.generation++;session.cursor=0;session.order=fullOrder(center,session.entries.size());
        loadNext(session);
    }
    private static void loadNext(Session session){
        if(!session.alive()||session.running)return;
        while(session.cursor<session.order.length){
            final int index=session.order[session.cursor++];WallpaperPopup.Entry entry=session.entries.get(index);
            if(session.loaded.contains(index)||entry==null||entry.kind!=3||entry.file==null)continue;
            final Context app=session.activity.get().getApplicationContext();
            session.running=true;
            session.task=()->{
                ThumbResult result=null;
                try{
                    if(session.alive()){
                        String key=getCacheKey(entry.file),variant=variant(key,session.width,session.height);
                        sFastKeyCache.put(entry.file.getPath(),key);
                        session.variants[index]=variant;
                        long epoch;
                        synchronized(LOCK){epoch=cacheEpoch;result=sMemCache.get(variant);}
                        if(result==null||result.bitmap.isRecycled())result=loadOrDecode(app,entry.file,session.width,session.height,new File(app.getCacheDir(),DISK_CACHE_DIR),key);
                        synchronized(LOCK){if(result!=null&&cacheWrites&&epoch==cacheEpoch&&!session.cancelled)sMemCache.put(variant,result);}
                    }
                }catch(RuntimeException|OutOfMemoryError error){Log.w(TAG,"Thumbnail job",error);}
                final ThumbResult output=result;
                MAIN.post(()->{session.running=false;session.task=null;if(!session.alive())return;
                    if(output!=null&&!session.loaded.contains(index)){deliver(session.picker.get(),index,output);session.loaded.add(index);}
                    loadNext(session);
                });
            };
            sExecutor.execute(session.task);return;
        }
    }
    public static void onPopupClosed(Object popup){
        synchronized(LOCK){
            if(popup==null){for(Session s:sessions.values())s.cancel();sessions.clear();}
            else{Session s=sessions.remove(popup);if(s!=null)s.cancel();}
            if(sessions.isEmpty())sMemCache.evictAll();
        }
    }
    public static String getCacheKey(File file){
        if(file==null)return "unknown";
        try{String value=file.getAbsolutePath()+"\n"+file.lastModified()+"\n"+file.length();
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
            char[] out=new char[digest.length*2];String hex="0123456789abcdef";for(int i=0;i<digest.length;i++){int octet=digest[i]&255;out[i*2]=hex.charAt(octet>>>4);out[i*2+1]=hex.charAt(octet&15);}return new String(out);
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    private static String variant(String key,int width,int height){return "v3_"+key+"_"+width+"x"+height+"_crop16x9";}
    static int sampleSize(int sourceWidth,int sourceHeight,int width,int height){
        int sample=1;
        while(sample<32768&&sourceWidth/(sample*2)>=width&&sourceHeight/(sample*2)>=height)sample*=2;
        // Panoramas can keep one dimension huge after the normal size-based sampling.
        long pixels=Math.max(1024L*1024,(long)width*height);
        while(sample<(1<<29)&&((sourceWidth+(long)sample-1)/sample)*((sourceHeight+(long)sample-1)/sample)>pixels)sample*=2;
        return sample;
    }
    /** Center-crops the decoded bitmap to the exact target aspect ratio (16:9 TV crop) before scaling. */
    static Bitmap centerCropScale(Bitmap rough,int width,int height){
        if(rough==null)return null;
        int rw=rough.getWidth(),rh=rough.getHeight();
        if(rw<=0||rh<=0||width<=0||height<=0)return rough;
        float scale=Math.max((float)width/rw,(float)height/rh);
        int cropW=Math.min(rw,Math.max(1,Math.round(width/scale)));
        int cropH=Math.min(rh,Math.max(1,Math.round(height/scale)));
        int cropX=Math.max(0,(rw-cropW)/2);
        int cropY=Math.max(0,(rh-cropH)/2);
        Bitmap cropped=(cropX==0&&cropY==0&&cropW==rw&&cropH==rh)?rough:Bitmap.createBitmap(rough,cropX,cropY,cropW,cropH);
        if(cropped!=rough)rough.recycle();
        Bitmap image=(cropped.getWidth()==width&&cropped.getHeight()==height)?cropped:Bitmap.createScaledBitmap(cropped,width,height,true);
        if(image!=cropped)cropped.recycle();
        return image;
    }
    public static ThumbResult loadOrDecode(Context context,File file,int width,int height,File cacheDir,String key){
        if(file==null||width<=0||height<=0||!file.isFile())return null;
        File disk=new File(cacheDir,variant(key,width,height)+".jpg");
        try{
            if(disk.isFile()){
                BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.RGB_565;
                Bitmap image=BitmapFactory.decodeFile(disk.getAbsolutePath(),options);
                if(image!=null){if(image.getWidth()==width&&image.getHeight()==height){disk.setLastModified(System.currentTimeMillis());return new ThumbResult(image,calculateAccent(image));}image.recycle();}
            }
            Bitmap image;
            if(WallpaperMedia.isVideo(file)){
                image=WallpaperVideoPreview.videoFrame(file,width,height);
                if(image==null)return null;
            }else{
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);
                if(bounds.outWidth<=0||bounds.outHeight<=0)return null;
                BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sampleSize(bounds.outWidth,bounds.outHeight,width,height);options.inPreferredConfig=Bitmap.Config.RGB_565;
                Bitmap rough=BitmapFactory.decodeFile(file.getAbsolutePath(),options);if(rough==null)return null;
                image=centerCropScale(rough,width,height);
            }
            File tmp=null;
            try{
                if(cacheDir.isDirectory()||cacheDir.mkdirs()){
                    tmp=File.createTempFile("thumb-",".tmp",cacheDir);
                    boolean saved;try(OutputStream out=new BufferedOutputStream(new FileOutputStream(tmp))){saved=image.compress(Bitmap.CompressFormat.JPEG,85,out);}
                    if(saved&&!tmp.renameTo(disk))Log.w(TAG,"Cannot publish thumbnail cache");
                    long now=SystemClock.elapsedRealtime(),due=nextDiskTrim.get();
                    if((diskWrites.incrementAndGet()%16==0||now>=due)&&nextDiskTrim.compareAndSet(due,now+30000))pruneDiskCache(cacheDir,DISK_LIMIT);
                }
            }catch(IOException e){Log.w(TAG,"Thumbnail cache write",e);}finally{if(tmp!=null&&tmp.exists())tmp.delete();}
            return new ThumbResult(image,calculateAccent(image));
        }catch(RuntimeException|OutOfMemoryError e){Log.w(TAG,"Thumbnail decode",e);return null;}
    }
    /** Runs on thumbnail workers; keeps recently read JPEGs and removes stale temp files. */
    private static synchronized void pruneDiskCache(File directory,long limit){
        File[] files=directory.listFiles();if(files==null)return;
        long now=System.currentTimeMillis(),bytes=0;ArrayList<File> images=new ArrayList<>();
        for(File file:files){if(!file.isFile())continue;String name=file.getName();
            if(name.endsWith(".tmp")){if(now-file.lastModified()>3600000)file.delete();}
            else if(name.endsWith(".jpg")){images.add(file);bytes+=file.length();}}
        Collections.sort(images,Comparator.comparingLong(File::lastModified));
        for(File file:images){if(bytes<=limit)break;long size=file.length();if(file.delete())bytes-=size;}
    }
    public static int calculateAccent(Bitmap bitmap){
        if(bitmap==null||bitmap.isRecycled())return -1;long r=0,g=0,b=0;int w=bitmap.getWidth(),h=bitmap.getHeight();
        for(int y=1;y<=6;y++)for(int x=1;x<=6;x++){int color=bitmap.getPixel(w*x/7,h*y/7);r+=(color>>16)&255;g+=(color>>8)&255;b+=color&255;}
        float[] hsv=new float[3];Color.RGBToHSV((int)(r/36),(int)(g/36),(int)(b/36),hsv);hsv[1]=Math.min(hsv[1],.62f);hsv[2]=Math.max(hsv[2],.95f);return Color.HSVToColor(hsv);
    }
    public static void prewarm(Context context,List<File> files,int limit){
        if(context==null||files==null||files.isEmpty())return;
        final Context app=context.getApplicationContext();final List<File> copy=new ArrayList<>(files.subList(0,Math.min(files.size(),Math.max(0,limit))));final long epoch;
        synchronized(LOCK){if(!cacheWrites)return;epoch=cacheEpoch;}
        sExecutor.execute(()->{
            File cacheDir=new File(app.getCacheDir(),DISK_CACHE_DIR);
            for(File file:copy){
                synchronized(LOCK){if(!cacheWrites||cacheEpoch!=epoch)return;}
                String key=getCacheKey(file),variant=variant(key,480,270);
                if(sFastKeyCache.size()>=512)sFastKeyCache.clear();
                sFastKeyCache.put(file.getPath(),key);
                File disk=new File(cacheDir,variant+".jpg");
                if(disk.isFile())continue;
                ThumbResult result=loadOrDecode(app,file,480,270,cacheDir,key);
                if(result!=null){
                    boolean keepInRam;
                    synchronized(LOCK){
                        keepInRam=cacheWrites&&cacheEpoch==epoch&&!sessions.isEmpty();
                        if(keepInRam)sMemCache.put(variant,result);
                    }
                    if(!keepInRam&&result.bitmap!=null&&!result.bitmap.isRecycled())result.bitmap.recycle();
                }
            }
        });
    }
    public static void trimMemory(int level){
        if(level<5)return;
        synchronized(LOCK){cacheEpoch++;cacheWrites=false;sMemCache.evictAll();}
        if(level>=20||level==10||level==15)sFastKeyCache.clear();
        // Active sessions still deliver visible images, but old/background work cannot refill the LRU.
    }
}
