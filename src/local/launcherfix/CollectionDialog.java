package local.launcherfix;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import com.gathin.tvhome.Backdrop;
import com.gathin.tvhome.ClearGlass;
import com.gathin.tvhome.WallpaperPopup;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;

/** Collection picker using the same glass surfaces and backdrop as native settings. */
public final class CollectionDialog {
    public interface Source {List<WallpaperCollectionHelper.Collection> load(Context app,boolean force) throws Exception;}
    private static final Source DEFAULT_SOURCE=WallpaperCollectionHelper::scanCollections;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    static final ThreadPoolExecutor WORKER=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,
        new LinkedBlockingQueue<Runnable>(),task->new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);task.run();},"wallpaper-collections"));
    static {WORKER.allowCoreThreadTimeOut(true);}
    private static WeakReference<CollectionDialog> active=new WeakReference<>(null);
    private final Activity activity;
    private final WeakReference<Object> parent;
    private final WeakReference<View> returnTo;
    private final Source source;
    private final float density;
    private final boolean vi;
    private final Typeface mediumFont,bodyFont;
    private final Dialog dialog;
    private final LinearLayout panel,list;
    private final TextView status,rescan,close;
    private final ArrayList<View> rows=new ArrayList<>();
    private Future<?> pending;
    private int generation;
    private boolean closed,scanning,applying,userNavigated,firstBind=true,restoreFocus=true;
    private View preferredRow;

    private CollectionDialog(Activity activity,Object parent,View returnTo,Source source){
        this.activity=activity;this.parent=new WeakReference<>(parent);this.returnTo=new WeakReference<>(returnTo);this.source=source;
        density=activity.getResources().getDisplayMetrics().density;vi="vi".equals(Locale.getDefault().getLanguage());
        mediumFont=resolveFont(true);bodyFont=resolveFont(false);
        dialog=new Dialog(activity);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);panel.setTag("collection_panel");
        panel.setPadding(dp(22),dp(20),dp(22),dp(18));panel.setBackground(ClearGlass.panel(dp(24),density));
        panel.setClipChildren(false);panel.setClipToPadding(false);
        LinearLayout header=new LinearLayout(activity);header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon=new ImageView(activity);icon.setScaleType(ImageView.ScaleType.CENTER);icon.setImageDrawable(new FolderIcon(density,28));icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(icon,new LinearLayout.LayoutParams(dp(34),dp(34)));
        LinearLayout heading=new LinearLayout(activity);heading.setOrientation(LinearLayout.VERTICAL);
        heading.addView(text(vi?"Bộ sưu tập hình nền":"Wallpaper collections",18,true,0xffffffff));
        TextView hint=text(vi?"Chọn bộ sưu tập cho hình nền của bạn":"Choose a collection for your wallpaper",12,false,0xd2ffffff);
        LinearLayout.LayoutParams hintParams=new LinearLayout.LayoutParams(-1,-2);hintParams.topMargin=dp(4);heading.addView(hint,hintParams);
        LinearLayout.LayoutParams headingParams=new LinearLayout.LayoutParams(0,-2,1);headingParams.setMarginStart(dp(12));header.addView(heading,headingParams);
        panel.addView(header,new LinearLayout.LayoutParams(-1,-2));
        View line=new View(activity);line.setBackgroundColor(0x26ffffff);LinearLayout.LayoutParams lineParams=new LinearLayout.LayoutParams(-1,dp(1));lineParams.topMargin=dp(16);lineParams.bottomMargin=dp(12);panel.addView(line,lineParams);
        ScrollView scroll=new ScrollView(activity);scroll.setTag("collection_scroll");scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);scroll.setFocusable(false);scroll.setClipToPadding(false);
        list=new LinearLayout(activity);list.setOrientation(LinearLayout.VERTICAL);list.setTag("collection_list");list.setPadding(dp(2),dp(2),dp(2),dp(2));scroll.addView(list,new ScrollView.LayoutParams(-1,-2));panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=new LinearLayout(activity);footer.setGravity(Gravity.CENTER_VERTICAL);
        status=text("",11,false,0xd2ffffff);status.setTag("collection_status");footer.addView(status,new LinearLayout.LayoutParams(0,-2,1));
        rescan=button(vi?"Quét lại":"Rescan","collection_rescan");rescan.setOnClickListener(v->{if(!scanning&&!applying)scan(true);});
        footer.addView(rescan,new LinearLayout.LayoutParams(dp(106),dp(48)));
        close=button(vi?"Đóng":"Close","collection_close");close.setOnClickListener(v->{CrystalSoundEngine.playClose(activity);PopupOptimizer.dismissAnimated(dialog,false);});
        LinearLayout.LayoutParams closeParams=new LinearLayout.LayoutParams(dp(90),dp(48));closeParams.setMarginStart(dp(10));footer.addView(close,closeParams);
        LinearLayout.LayoutParams footerParams=new LinearLayout.LayoutParams(-1,-2);footerParams.topMargin=dp(14);panel.addView(footer,footerParams);
        android.util.DisplayMetrics metrics=activity.getResources().getDisplayMetrics();
        Backdrop.wrap(activity,dialog,panel,true,0);
        panel.setLayoutParams(new android.widget.FrameLayout.LayoutParams(Math.min(dp(560),Math.max(dp(260),metrics.widthPixels-dp(80))),Math.min(dp(420),Math.max(dp(200),metrics.heightPixels-dp(64))),Gravity.CENTER));
        // Backdrop.wrap owns OnShowListener and the shared reveal animation.
        dialog.setOnKeyListener((d,key,event)->{if(event.getAction()==KeyEvent.ACTION_DOWN&&key>=KeyEvent.KEYCODE_DPAD_UP&&key<=KeyEvent.KEYCODE_DPAD_RIGHT)userNavigated=true;return false;});
        dialog.setOnDismissListener(d->dispose());
        wire();
    }
    public static void show(Activity activity,Object parent,View returnTo){
        if(activity==null||activity.isFinishing()||activity.isDestroyed())return;
        CollectionDialog previous=active.get();
        if(previous!=null&&previous.activity==activity&&previous.dialog.isShowing())return;
        if(previous!=null)previous.dialog.dismiss();
        CollectionDialog picker=new CollectionDialog(activity,parent,returnTo,DEFAULT_SOURCE);picker.open();
    }
    public static Dialog getActiveDialog(){CollectionDialog picker=active.get();return picker==null?null:picker.dialog;}
    public static boolean dismissForActivity(Activity activity){CollectionDialog picker=active.get();if(picker==null||picker.activity!=activity)return false;picker.dispose();PopupOptimizer.dismissImmediate(picker.dialog,false);return true;}
    public static boolean dismissFromBack(Activity activity){CollectionDialog picker=active.get();if(picker==null||picker.activity!=activity||picker.closed)return false;CrystalSoundEngine.playClose(activity);PopupOptimizer.dismissAnimated(picker.dialog,true);return true;}
    private void open(){
        active=new WeakReference<>(this);dialog.show();
        Window window=dialog.getWindow();if(window!=null)window.setLayout(-1,-1);
        List<WallpaperCollectionHelper.Collection> cached=source==DEFAULT_SOURCE?WallpaperCollectionHelper.cachedCollections():null;
        if(cached!=null)bind(cached);else{message(vi?"Đang tải bộ sưu tập…":"Loading collections…");scan(false);}
        panel.post(()->{if(live()&&!userNavigated)(preferredRow==null?close:preferredRow).requestFocus();});
    }
    private boolean live(){return !closed&&!activity.isFinishing()&&!activity.isDestroyed()&&dialog.isShowing();}
    private void cancelPending(){generation++;if(pending!=null){pending.cancel(true);if(pending instanceof Runnable)WORKER.remove((Runnable)pending);pending=null;}}
    private void dispose(){
        if(closed)return;closed=true;cancelPending();
        if(active.get()==this)active=new WeakReference<>(null);
        panel.animate().cancel();for(View row:rows)row.animate().cancel();
        View anchor=returnTo.get();if(restoreFocus&&!activity.isFinishing()&&!activity.isDestroyed()&&anchor!=null&&anchor.isAttachedToWindow())anchor.requestFocus();
    }
    private void scan(boolean force){
        cancelPending();int token=generation;scanning=true;status.setText(vi?"Đang quét…":"Scanning…");rescan.setAlpha(.55f);
        WeakReference<CollectionDialog> owner=new WeakReference<>(this);Context app=activity.getApplicationContext();Source loader=source;
        pending=WORKER.submit(()->{
            List<WallpaperCollectionHelper.Collection> result=null;Exception failure=null;
            try{result=loader.load(app,force);}catch(Exception error){failure=error;}
            final List<WallpaperCollectionHelper.Collection> output=result==null?Collections.emptyList():new ArrayList<>(result);final Exception problem=failure;
            MAIN.post(()->{CollectionDialog picker=owner.get();if(picker==null||!picker.live()||picker.generation!=token)return;
                picker.scanning=false;picker.pending=null;picker.rescan.setAlpha(1);
                if(problem!=null){android.util.Log.w("TvHome.Collections","Collection scan failed",problem);picker.status.setText(picker.vi?"Không tải được. Hãy quét lại.":"Could not load. Try Rescan.");if(picker.rows.isEmpty())picker.message(picker.vi?"Chưa tải được bộ sưu tập":"Collections unavailable");}
                else picker.bind(output);
            });
        });
    }
    private void bind(List<WallpaperCollectionHelper.Collection> collections){
        View focused=dialog.getCurrentFocus();String focusTag=focused==null?null:String.valueOf(focused.getTag());
        boolean restoreRow=focused!=null&&rows.contains(focused);list.removeAllViews();rows.clear();preferredRow=null;
        String activeId=activity.getSharedPreferences(WallpaperCollectionHelper.PREFS_COLLECTIONS,0).getString(WallpaperCollectionHelper.KEY_ACTIVE_COLLECTION_ID,null);
        boolean activeFound=false;for(WallpaperCollectionHelper.Collection item:collections)if(item!=null&&Objects.equals(activeId,item.id)){activeFound=true;break;}
        if(!activeFound)for(WallpaperCollectionHelper.Collection item:collections)if(item!=null){activeId=item.id;break;}
        for(WallpaperCollectionHelper.Collection collection:collections){
            if(collection==null)continue;boolean current=Objects.equals(activeId,collection.id);
            LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setId(View.generateViewId());row.setTag("collection_row:"+collection.id);row.setFocusable(true);row.setClickable(true);row.setSelected(current);row.setPadding(dp(14),dp(10),dp(14),dp(10));row.setBackground(new ClearGlass.Plate(dp(14),density,-1));
            ImageView folder=new ImageView(activity);folder.setScaleType(ImageView.ScaleType.CENTER);folder.setImageDrawable(new FolderIcon(density,28));folder.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(folder,new LinearLayout.LayoutParams(dp(28),dp(28)));
            LinearLayout labels=new LinearLayout(activity);labels.setOrientation(LinearLayout.VERTICAL);
            TextView name=text(collection.name,14,true,0xffffffff);labels.addView(name);
            TextView count=text(collection.count()+ (vi?" hình nền":" wallpapers"),11,false,0xd2ffffff);LinearLayout.LayoutParams countParams=new LinearLayout.LayoutParams(-1,-2);countParams.topMargin=dp(3);labels.addView(count,countParams);
            LinearLayout.LayoutParams labelsParams=new LinearLayout.LayoutParams(0,-2,1);labelsParams.setMarginStart(dp(12));row.addView(labels,labelsParams);
            if(current){TextView badge=text(vi?"Đang dùng":"In use",11,true,0xffffffff);row.addView(badge);preferredRow=row;}
            row.setContentDescription(collection.name+", "+collection.count()+(vi?" hình nền":" wallpapers")+(current?(vi?", đang dùng":", in use"):""));
            row.setOnFocusChangeListener((v,hasFocus)->{if(hasFocus){rescan.setNextFocusUpId(v.getId());close.setNextFocusUpId(v.getId());}});
            row.setOnClickListener(v->{if(!applying)select(collection);});
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(64));params.bottomMargin=dp(8);list.addView(row,params);rows.add(row);
        }
        if(preferredRow==null&&!rows.isEmpty())preferredRow=rows.get(0);
        if(rows.isEmpty())message(vi?"Chưa có thư mục hình nền. Thêm hình nền rồi chọn Quét lại.":"No wallpaper folders yet. Add wallpapers, then choose Rescan.");
        status.setText(rows.size()+(vi?" bộ sưu tập":" collections"));wire();
        View restore=null;if(restoreRow&&focusTag!=null)restore=list.findViewWithTag(focusTag);
        if(restore!=null)restore.requestFocus();else if(firstBind&&!userNavigated)(preferredRow==null?close:preferredRow).requestFocus();
        firstBind=false;
    }
    private void wire(){
        for(int i=0;i<rows.size();i++){View row=rows.get(i);row.setNextFocusUpId(rows.get(Math.max(0,i-1)).getId());row.setNextFocusDownId(i+1<rows.size()?rows.get(i+1).getId():rescan.getId());row.setNextFocusLeftId(row.getId());row.setNextFocusRightId(row.getId());}
        int up=preferredRow==null?close.getId():preferredRow.getId();rescan.setNextFocusUpId(up);close.setNextFocusUpId(up);
        rescan.setNextFocusLeftId(rescan.getId());rescan.setNextFocusRightId(close.getId());close.setNextFocusLeftId(rescan.getId());close.setNextFocusRightId(close.getId());rescan.setNextFocusDownId(rescan.getId());close.setNextFocusDownId(close.getId());
    }
    private void select(WallpaperCollectionHelper.Collection collection){
        cancelPending();applying=true;scanning=false;int token=generation;rescan.setEnabled(false);status.setText(vi?"Đang chọn…":"Selecting…");
        WeakReference<CollectionDialog> owner=new WeakReference<>(this);
        ArrayList<File> input=new ArrayList<>(collection.files);
        pending=WORKER.submit(()->{
            ArrayList<File> files=new ArrayList<>();String prepared=null;RuntimeException failure=null;
            try{for(File file:input){if(Thread.currentThread().isInterrupted())return;if(WallpaperCollectionHelper.IMAGE_FILTER.accept(file))files.add(file);}
                files.sort(Comparator.comparing(File::getAbsolutePath));prepared=WallpaperCollectionHelper.computeSignature(files);
            }catch(RuntimeException error){failure=error;}
            final String signature=prepared;final RuntimeException problem=failure;
            MAIN.post(()->{CollectionDialog picker=owner.get();if(picker==null||!picker.live()||picker.generation!=token)return;
                picker.pending=null;picker.applying=false;picker.rescan.setEnabled(true);
                if(problem!=null){android.util.Log.w("TvHome.Collections","Collection selection failed",problem);picker.status.setText(picker.vi?"Không đọc được hình nền. Hãy quét lại.":"Could not read wallpapers. Try Rescan.");return;}
                if(files.isEmpty()){picker.status.setText(picker.vi?"Thư mục không còn hình nền. Hãy quét lại.":"No wallpapers remain. Try Rescan.");return;}
                WallpaperCollectionHelper.applyCollectionSnapshot(picker.activity,collection,files,signature,true);
                picker.restoreFocus=false;picker.dialog.dismiss();Object parent=picker.parent.get();
                Runnable reopen=()->{if(!picker.activity.isFinishing()&&!picker.activity.isDestroyed())WallpaperPopup.show(picker.activity);};
                if(parent instanceof WallpaperPopup)((WallpaperPopup)parent).closeFromHelper(reopen);else reopen.run();
            });
        });
    }
    private void message(String message){list.removeAllViews();TextView empty=text(message,13,false,0xd2ffffff);empty.setSingleLine(false);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(16),dp(22),dp(16),dp(22));list.addView(empty,new LinearLayout.LayoutParams(-1,-2));}
    private int dp(float value){return Math.round(value*density);}
    private Typeface resolveFont(boolean medium){int id=activity.getResources().getIdentifier(medium?"font_medium":"font_body","string",activity.getPackageName());return id==0?Typeface.DEFAULT:Typeface.create(activity.getString(id),Typeface.NORMAL);}
    private Typeface font(boolean medium){return medium?mediumFont:bodyFont;}
    private TextView text(String value,float size,boolean medium,int color){TextView text=new TextView(activity);text.setText(value);text.setTextSize(size);text.setTextColor(color);text.setTypeface(font(medium));text.setSingleLine();text.setEllipsize(TextUtils.TruncateAt.END);text.setIncludeFontPadding(false);ClearGlass.shadow(text,density);FontChoice.apply(text);return text;}
    private TextView button(String label,String tag){TextView button=text(label,13,true,0xffffffff);button.setGravity(Gravity.CENTER);button.setId(View.generateViewId());button.setTag(tag);button.setFocusable(true);button.setClickable(true);button.setBackground(new ClearGlass.Plate(dp(24),density,-1));return button;}
    static final class FolderIcon extends Drawable {
        final Paint paint=new Paint(3);final float density;final int size;
        FolderIcon(float density,int sizeDp){this.density=density;size=Math.round(sizeDp*density);paint.setColor(0xffffffff);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f*density);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);}
        public void draw(Canvas canvas){android.graphics.Rect bounds=getBounds();float width=Math.max(0,Math.min(Math.min(bounds.width(),bounds.height()),28*density)-paint.getStrokeWidth()),height=width*.72f,x=bounds.exactCenterX()-width/2,y=bounds.exactCenterY()-height/2;Path path=new Path();path.moveTo(x,y+height*.2f);path.lineTo(x,y);path.lineTo(x+width*.38f,y);path.lineTo(x+width*.52f,y+height*.2f);path.lineTo(x+width,y+height*.2f);path.lineTo(x+width,y+height);path.lineTo(x,y+height);path.close();canvas.drawPath(path,paint);}
        public int getIntrinsicWidth(){return size;}public int getIntrinsicHeight(){return size;}
        public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }
}
