package local.launcherfix;

import android.app.*;import android.content.*;import android.graphics.*;import android.graphics.drawable.*;import android.media.ExifInterface;import android.net.Uri;import android.os.*;import android.util.AtomicFile;import android.view.*;import android.widget.*;
import com.gathin.tvhome.*;
import java.io.*;import java.lang.ref.WeakReference;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicLong;

/** One private normalized avatar; no persistent external URI or image decoding on UI. */
public final class AvatarStore {
 public static final int REQUEST_AVATAR=0x6a31;
 private static final Handler UI=new Handler(Looper.getMainLooper());
 private static final ThreadPoolExecutor WORK=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new LinkedBlockingQueue<Runnable>(),r->new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);r.run();},"launcher-avatar"));
 static{WORK.allowCoreThreadTimeOut(true);}
 private static final AtomicLong EPOCH=new AtomicLong();private static Future<?> pending;
 private static Bitmap cached;private static boolean loaded;private static int loadEpoch,returnEpoch;
 private static WeakReference<ImageView> visible=new WeakReference<>(null);
 private static WeakReference<Dialog> chooser=new WeakReference<>(null);
 private static WeakReference<Activity> chooserOwner=new WeakReference<>(null),returnOwner=new WeakReference<>(null);
 private AvatarStore(){}
 private static AtomicFile file(Context ctx){return new AtomicFile(new File(ctx.getFilesDir(),"control-panel-avatar.png"));}
 public static void save(Context ctx,Bitmap bitmap)throws IOException{
  if(bitmap==null||bitmap.isRecycled()||bitmap.getWidth()!=256||bitmap.getHeight()!=256)throw new IOException("Invalid normalized avatar");
  AtomicFile output=file(ctx);FileOutputStream stream=null;try{stream=output.startWrite();if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,stream))throw new IOException("Avatar encode failed");output.finishWrite(stream);}catch(IOException|RuntimeException e){if(stream!=null)output.failWrite(stream);throw e;}
 }
 public static Bitmap read(Context ctx)throws IOException{try(InputStream input=file(ctx).openRead()){Bitmap bitmap=BitmapFactory.decodeStream(input);if(bitmap==null)throw new IOException("Unreadable saved avatar");if(bitmap.getWidth()!=256||bitmap.getHeight()!=256){bitmap.recycle();throw new IOException("Invalid saved avatar dimensions");}return bitmap;}}
 public static void delete(Context ctx){file(ctx).delete();}
 public static Bitmap normalize(Bitmap source,int orientation)throws IOException{
  if(source==null||source.isRecycled()||source.getWidth()<1||source.getHeight()<1)throw new IOException("Invalid image");
  Matrix transform=new Matrix();switch(orientation){case 2:transform.setScale(-1,1);break;case 3:transform.setRotate(180);break;case 4:transform.setRotate(180);transform.postScale(-1,1);break;case 5:transform.setRotate(90);transform.postScale(-1,1);break;case 6:transform.setRotate(90);break;case 7:transform.setRotate(-90);transform.postScale(-1,1);break;case 8:transform.setRotate(-90);break;}
  Bitmap rotated=source;if(!transform.isIdentity())rotated=Bitmap.createBitmap(source,0,0,source.getWidth(),source.getHeight(),transform,true);
  try{Bitmap out=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(out);canvas.drawColor(0xff25303b);float side=Math.min(rotated.getWidth(),rotated.getHeight()),x=(rotated.getWidth()-side)/2f,y=(rotated.getHeight()-side)/2f;canvas.drawBitmap(rotated,new Rect(Math.round(x),Math.round(y),Math.round(x+side),Math.round(y+side)),new Rect(0,0,256,256),new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));return out;}finally{if(rotated!=source)rotated.recycle();}
 }
 public static Bitmap decode(Context ctx,Uri uri)throws IOException{
  if(uri==null)throw new IOException("No image URI");BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
  try(InputStream input=ctx.getContentResolver().openInputStream(uri)){if(input==null)throw new IOException("Cannot open image");BitmapFactory.decodeStream(input,null,bounds);}
  if(bounds.outWidth<1||bounds.outHeight<1)throw new IOException("Unsupported image");
  int orientation=1;try(InputStream input=ctx.getContentResolver().openInputStream(uri)){if(input!=null)orientation=new ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);}catch(IOException|RuntimeException ignored){}
  BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.ARGB_8888;options.inSampleSize=1;
  while((long)Math.max(1,bounds.outWidth/options.inSampleSize)*Math.max(1,bounds.outHeight/options.inSampleSize)>1024L*1024&&options.inSampleSize<(1<<29))options.inSampleSize*=2;
  Bitmap bitmap;try(InputStream input=ctx.getContentResolver().openInputStream(uri)){bitmap=BitmapFactory.decodeStream(input,null,options);}if(bitmap==null)throw new IOException("Cannot decode image");
  try{return normalize(bitmap,orientation);}finally{bitmap.recycle();}
 }
 private static void display(ImageView view,Bitmap bitmap){if(bitmap==null){view.setScaleType(ImageView.ScaleType.CENTER);view.setImageDrawable(new ControlCenter.Glyph(ControlCenter.Glyph.ICON,view.getResources().getDisplayMetrics().density,56));}else{view.setScaleType(ImageView.ScaleType.CENTER_CROP);view.setImageBitmap(bitmap);}}
 private static void publish(Bitmap bitmap){cached=bitmap;loaded=true;ImageView view=visible.get();if(view!=null)display(view,bitmap);}
 public static void bind(ImageView view){visible=new WeakReference<>(view);if(loaded){display(view,cached);return;}Context app=view.getContext().getApplicationContext();long token=EPOCH.get();int loadToken=loadEpoch;WeakReference<ImageView> target=new WeakReference<>(view);
  WORK.execute(()->{Bitmap bitmap=null;try{bitmap=read(app);}catch(IOException|RuntimeException ignored){}final Bitmap result=bitmap;UI.post(()->{if(token!=EPOCH.get()||loadToken!=loadEpoch)return;cached=result;loaded=true;ImageView image=target.get();if(image!=null&&visible.get()==image)display(image,result);});});
 }
 public static void trim(int level){if(level>=20||level==10||level==15){loadEpoch++;cached=null;loaded=false;}}
 public static Dialog getChooser(){return chooser.get();}
 public static boolean dismissChooser(Activity owner){Dialog dialog=chooser.get();if(dialog==null||chooserOwner.get()!=owner)return false;chooser=new WeakReference<>(null);chooserOwner=new WeakReference<>(null);PopupOptimizer.dismissImmediate(dialog,false);return true;}
 public static boolean dismissChooserFromBack(Activity owner){Dialog dialog=chooser.get();if(dialog==null||chooserOwner.get()!=owner||!dialog.isShowing())return false;CrystalSoundEngine.playClose(owner);PopupOptimizer.dismissAnimated(dialog,true);return true;}
 public static void cancelReturn(Activity owner){returnEpoch++;if(returnOwner.get()==owner)returnOwner=new WeakReference<>(null);dismissChooser(owner);}
 public static void showChooser(ControlCenter c,ImageView anchor){
  Activity a=c.activity;if(a.isFinishing()||a.isDestroyed())return;Dialog old=chooser.get();if(old!=null&&old.isShowing())return;
  Dialog dialog=new Dialog(a);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);LinearLayout panel=new LinearLayout(a);panel.setOrientation(LinearLayout.VERTICAL);float d=c.dp;int pad=Math.round(18*d);panel.setPadding(pad,pad,pad,pad);panel.setBackground(WallpaperTheme.glass(d,WallpaperTheme.current(c)));
  TextView title=new TextView(a);title.setText(c.vi?"Đổi avatar":"Change avatar");title.setTextSize(18);title.setTextColor(ReferenceControlPanel.TEXT);panel.addView(title,new LinearLayout.LayoutParams(-1,-2));
  Runnable[] actions={()->launchPicker(c),()->useWallpaper(c),()->reset(c)};
  String[] labels=c.vi?new String[]{"Chọn ảnh từ tệp","Dùng hình nền hiện tại","Avatar mặc định","Đóng"}:new String[]{"Choose image file","Use current wallpaper","Default avatar","Close"};String[] tags={"avatar_choose_file","avatar_use_wallpaper","avatar_reset","avatar_close"};
  for(int i=0;i<labels.length;i++){final int index=i;TextView button=new TextView(a);button.setText(labels[i]);button.setTag(tags[i]);button.setId(View.generateViewId());button.setTextSize(13);button.setTextColor(ReferenceControlPanel.TEXT);button.setGravity(Gravity.CENTER);button.setFocusable(true);button.setClickable(true);button.setBackground(new com.gathin.tvhome.ClearGlass.Plate(24*d,d,-1));button.setOnClickListener(v->{if(index==0){PopupOptimizer.dismissImmediate(dialog,false);actions[0].run();}else{if(index<3)actions[index].run();else CrystalSoundEngine.playClose(a);PopupOptimizer.dismissAnimated(dialog,false);}});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,Math.round(48*d));lp.topMargin=Math.round(10*d);panel.addView(button,lp);}
  Backdrop.wrap(a,dialog,panel,true,0);panel.setLayoutParams(new FrameLayout.LayoutParams(Math.min(Math.round(360*d),a.getResources().getDisplayMetrics().widthPixels-Math.round(48*d)),-2,Gravity.CENTER));
  chooser=new WeakReference<>(dialog);chooserOwner=new WeakReference<>(a);WeakReference<ImageView> back=new WeakReference<>(anchor);
  dialog.setOnDismissListener(v->{if(chooser.get()==dialog){chooser=new WeakReference<>(null);chooserOwner=new WeakReference<>(null);}ImageView avatar=back.get();if(c.open&&avatar!=null&&avatar.isAttachedToWindow())avatar.requestFocus();});dialog.show();dialog.getWindow().setLayout(-1,-1);panel.findViewWithTag("avatar_choose_file").requestFocus();
 }
 private static void launchPicker(ControlCenter c){
  Activity a=c.activity;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
  try{c.callClose();returnOwner=new WeakReference<>(a);a.startActivityForResult(intent,REQUEST_AVATAR);}catch(ActivityNotFoundException e){try{intent.setAction(Intent.ACTION_GET_CONTENT);a.startActivityForResult(intent,REQUEST_AVATAR);}catch(ActivityNotFoundException|SecurityException fail){returnOwner=new WeakReference<>(null);message(a,c.vi?"Không có trình chọn ảnh. Hãy dùng hình nền hiện tại.":"No image picker. Use current wallpaper instead.");reopen(a);}}catch(SecurityException e){returnOwner=new WeakReference<>(null);message(a,c.vi?"Không mở được trình chọn ảnh":"Could not open image picker");reopen(a);}
 }
 private static void message(Activity a,String message){if(a!=null&&!a.isFinishing()&&!a.isDestroyed())Toast.makeText(a,message,Toast.LENGTH_SHORT).show();}
 private static void reopen(Activity a){int token=returnEpoch;WeakReference<Activity> owner=new WeakReference<>(a);UI.postDelayed(()->{Activity activity=owner.get();if(token!=returnEpoch||activity==null||activity.isFinishing()||activity.isDestroyed()||!activity.hasWindowFocus())return;ControlCenter c=ControlPanelHelper.getActiveCenter();if(c!=null&&c.activity==activity&&!c.open)c.callShow();},200);}
 private static long next(){long token=EPOCH.incrementAndGet();if(pending!=null){pending.cancel(true);if(pending instanceof Runnable)WORK.remove((Runnable)pending);pending=null;}return token;}
 public static boolean handleActivityResult(Activity activity,int request,int result,Intent data){
  if(request!=REQUEST_AVATAR)return false;
  if(activity==null)return true;
  if(result!=Activity.RESULT_OK||data==null||data.getData()==null){if(returnOwner.get()==activity){returnOwner=new WeakReference<>(null);reopen(activity);}return true;}
  final Uri uri=data.getData();final Context app=activity.getApplicationContext();final long token=next();WeakReference<Activity> owner=new WeakReference<>(activity);
  pending=WORK.submit(()->{Bitmap bitmap=null;Throwable error=null;try{bitmap=decode(app,uri);if(token!=EPOCH.get())return;save(app,bitmap);}catch(IOException|RuntimeException|OutOfMemoryError failure){error=failure;}final Bitmap image=bitmap;final Throwable problem=error;UI.post(()->{if(token!=EPOCH.get())return;pending=null;Activity a=owner.get();if(problem==null)publish(image);message(a,problem==null?"Avatar saved":"Could not read image. Previous avatar retained.");if(a!=null&&returnOwner.get()==a){returnOwner=new WeakReference<>(null);reopen(a);}});});return true;
 }
 private static void useWallpaper(ControlCenter c){
  if(!(c.activity instanceof MainActivity)){return;}MainActivity a=(MainActivity)c.activity;Bitmap current=a.wallpaperPlayer==null?null:a.wallpaperPlayer.current;
  if(current==null||current.isRecycled()){message(a,c.vi?"Chưa có hình nền để dùng":"No wallpaper available");return;}
  // Copy only a 256px crop while the UI owns the source; never hand player-owned pixels to a worker.
  final Bitmap image;try{image=normalize(current,1);}catch(IOException|RuntimeException|OutOfMemoryError error){message(a,c.vi?"Không đọc được hình nền":"Could not read wallpaper");return;}long token=next();Context app=a.getApplicationContext();WeakReference<Activity> owner=new WeakReference<>(a);
  pending=WORK.submit(()->{Throwable error=null;try{if(token!=EPOCH.get())return;save(app,image);}catch(IOException|RuntimeException failure){error=failure;}final Throwable problem=error;UI.post(()->{if(token!=EPOCH.get())return;pending=null;if(problem==null)publish(image);Activity activity=owner.get();message(activity,problem==null?"Avatar saved":"Could not save avatar");});});
 }
 private static void reset(ControlCenter c){long token=next();Context app=c.activity.getApplicationContext();pending=WORK.submit(()->{if(token!=EPOCH.get())return;delete(app);UI.post(()->{if(token!=EPOCH.get())return;pending=null;publish(null);});});}
}
