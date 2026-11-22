package local.launcherfix;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Copies a selected font into private storage before publishing its Typeface. */
public final class FontImport {
    private static final int REQUEST_FONT=29037;
    private static final long MAX_BYTES=20L*1024*1024;
    private static final String PREFS="launcher_font",FILE="custom_file",NAME="custom_name";
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor WORKER=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,
        new LinkedBlockingQueue<Runnable>(),task->new Thread(()->{
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            task.run();
        },"launcher-font-import"));
    static { WORKER.allowCoreThreadTimeOut(true); }
    private static boolean picking,importing,loadRequested;

    private FontImport(){}
    private static boolean vi(){return "vi".equals(Locale.getDefault().getLanguage());}
    private static void message(Context context,String vn,String en){
        Toast.makeText(context,vi()?vn:en,Toast.LENGTH_SHORT).show();
    }
    public static boolean launch(Activity activity){
        if(activity==null||activity.isFinishing()||activity.isDestroyed())return false;
        if(picking||importing){message(activity,"Đang nhập phông chữ","A font import is already in progress");return false;}
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"font/ttf","font/otf",
            "application/x-font-ttf","application/x-font-truetype","application/x-font-opentype",
            "application/vnd.ms-opentype","application/font-sfnt","application/octet-stream"});
        picking=true;
        try{activity.startActivityForResult(intent,REQUEST_FONT);return true;}
        catch(ActivityNotFoundException missing){
            try{intent.setAction(Intent.ACTION_GET_CONTENT);activity.startActivityForResult(intent,REQUEST_FONT);return true;}
            catch(ActivityNotFoundException|SecurityException failure){
                picking=false;message(activity,"Không mở được trình chọn phông chữ","Could not open a font file picker");return false;
            }
        }catch(SecurityException denied){
            picking=false;message(activity,"Không mở được trình chọn phông chữ","Could not open a font file picker");return false;
        }
    }

    public static boolean handleActivityResult(Activity activity,int request,int result,Intent data){
        if(request!=REQUEST_FONT)return false;
        picking=false;
        if(activity==null||result!=Activity.RESULT_OK||data==null||data.getData()==null)return true;
        if(importing){message(activity,"Đang nhập phông chữ","A font import is already in progress");return true;}
        importing=true;
        final Context app=activity.getApplicationContext();
        final WeakReference<Activity> owner=new WeakReference<>(activity);
        final Uri uri=data.getData();
        final long epoch=FontChoice.epoch();
        message(activity,"Đang nhập phông chữ…","Importing font…");
        WORKER.execute(()->{
            Typeface face=null;String displayName=null;File candidate=null;Throwable failure=null;
            boolean removeCandidate=true;
            try{
                File dir=directory(app);
                if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create font directory");
                candidate=new File(dir,"font-"+UUID.randomUUID().toString().replace("-","")+".ttf");
                if(!candidate.createNewFile())throw new IOException("Font filename collision");
                copy(app,uri,candidate);
                face=readFont(candidate);
                displayName=displayName(app,uri);
                SharedPreferences prefs=app.getSharedPreferences(PREFS,0);
                String previousFile=prefs.getString(FILE,null),previousName=prefs.getString(NAME,null);
                removeCandidate=false;
                if(!prefs.edit().putString(FILE,candidate.getName()).putString(NAME,displayName).commit()){
                    // A failed disk write may still have published the new in-memory value.
                    // Retain its file unless rollback is known to have reached storage.
                    SharedPreferences.Editor rollback=prefs.edit();
                    if(previousFile==null)rollback.remove(FILE);else rollback.putString(FILE,previousFile);
                    if(previousName==null)rollback.remove(NAME);else rollback.putString(NAME,previousName);
                    removeCandidate=rollback.commit();
                    if(!removeCandidate)Log.w("TvHome.Font","Font preference rollback could not be saved; keeping candidate file");
                    throw new IOException("Cannot save font selection");
                }
                // A successfully replaced file can remain mapped by an older live Typeface.
                // Cleanup is best effort: the committed replacement must remain usable.
                try{
                    File old=ownedFile(app,previousFile);
                    if(old!=null&&!old.equals(candidate)&&old.isFile()&&!old.delete())
                        Log.w("TvHome.Font","Previous imported font could not be removed");
                }catch(IOException|RuntimeException|OutOfMemoryError cleanup){
                    Log.w("TvHome.Font","Previous imported font could not be removed",cleanup);
                }
            }catch(IOException|RuntimeException|OutOfMemoryError error){
                failure=error;
                try{
                    if(removeCandidate&&candidate!=null&&candidate.isFile()&&!candidate.delete())
                        Log.w("TvHome.Font","Incomplete font could not be removed");
                }catch(RuntimeException cleanup){Log.w("TvHome.Font","Incomplete font could not be removed",cleanup);}
            }
            final Typeface ready=face;final String label=displayName;final Throwable problem=failure;
            MAIN.post(()->{
                importing=false;
                if(problem==null)FontChoice.onCustomReady(app,ready,label,true,epoch);
                else{Log.w("TvHome.Font","Font import failed",problem);FontChoice.onCustomFailure(true);}
                Activity current=owner.get();
                if(current!=null&&!current.isFinishing()&&!current.isDestroyed()){
                    if(problem==null)message(current,"Đã nhập phông chữ","Font imported");
                    else message(current,"Không đọc được phông chữ TTF/OTF (tối đa 20 MB). Giữ phông cũ.",
                        "Could not read TTF/OTF font (20 MB maximum). Previous font retained.");
                }
            });
        });
        return true;
    }

    public static boolean hasSaved(Context context){
        try{File file=savedFile(context.getApplicationContext());return file!=null&&file.isFile();}
        catch(IOException|RuntimeException failure){return false;}
    }
    public static void loadSaved(Context context){
        if(loadRequested)return;
        loadRequested=true;
        final Context app=context.getApplicationContext();
        WORKER.execute(()->{
            Typeface face=null;String displayName=null;Throwable failure=null;
            try{
                File file=savedFile(app);
                if(file==null)throw new IOException("No saved font");
                face=readFont(file);
                displayName=app.getSharedPreferences(PREFS,0).getString(NAME,"Font");
            }catch(IOException|RuntimeException|OutOfMemoryError error){failure=error;}
            final Typeface ready=face;final String label=displayName;final Throwable problem=failure;
            MAIN.post(()->{
                if(problem==null)FontChoice.onCustomReady(app,ready,label,false,-1);
                else{
                    Log.w("TvHome.Font","Saved font could not be loaded",problem);
                    FontChoice.onCustomFailure(false);
                    message(app,"Không tải được phông chữ đã lưu","Could not load the saved font");
                }
            });
        });
    }

    private static File directory(Context context)throws IOException{
        File parent=context.getFilesDir().getCanonicalFile();
        File dir=new File(parent,"imported-fonts").getCanonicalFile();
        if(!parent.equals(dir.getParentFile()))throw new IOException("Invalid font directory");
        return dir;
    }
    private static File ownedFile(Context context,String basename)throws IOException{
        if(basename==null||!basename.matches("font-[0-9a-f]{32}\\.ttf"))return null;
        File dir=directory(context),file=new File(dir,basename).getCanonicalFile();
        return dir.equals(file.getParentFile())?file:null;
    }
    private static File savedFile(Context context)throws IOException{
        return ownedFile(context,context.getSharedPreferences(PREFS,0).getString(FILE,null));
    }
    private static void copy(Context context,Uri uri,File file)throws IOException{
        try(InputStream in=context.getContentResolver().openInputStream(uri)){
            if(in==null)throw new IOException("Font stream unavailable");
            try(FileOutputStream out=new FileOutputStream(file)){
                byte[] buffer=new byte[8192];long count=0;int read;
                while((read=in.read(buffer))!=-1){
                    count+=read;if(count>MAX_BYTES)throw new IOException("Font exceeds 20 MiB");
                    out.write(buffer,0,read);
                }
                out.getFD().sync();
            }
        }
    }
    private static Typeface readFont(File file)throws IOException{
        if(!file.isFile()||file.length()<12||file.length()>MAX_BYTES)throw new IOException("Invalid font size");
        int signature;
        try(FileInputStream input=new FileInputStream(file)){
            signature=(input.read()<<24)|(input.read()<<16)|(input.read()<<8)|input.read();
        }
        if(signature!=0x00010000&&signature!=0x4f54544f&&signature!=0x74727565)
            throw new IOException("Not a TTF or OTF font");
        Typeface face=new Typeface.Builder(file).build();
        if(face==null)throw new IOException("Invalid font data");
        return face;
    }
    private static String displayName(Context context,Uri uri){
        String name=null;
        try(Cursor cursor=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(cursor!=null&&cursor.moveToFirst())name=cursor.getString(0);
        }catch(RuntimeException ignored){}
        if(name==null||name.trim().isEmpty())return "Font";
        name=name.replaceAll("[\\p{Cntrl}]","").trim();
        return name.isEmpty()?"Font":name.substring(0,Math.min(name.length(),96));
    }
}
