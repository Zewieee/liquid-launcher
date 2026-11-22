package local.launcherfix;
import android.content.*;import android.graphics.*;import android.graphics.drawable.*;import android.view.*;import android.view.accessibility.AccessibilityNodeInfo;import android.widget.*;import com.gathin.tvhome.*;import java.lang.ref.WeakReference;import java.util.*;

/** UI-owned artwork wrappers: no package reload, source recoloring or new bitmap cache. */
public final class WallpaperIcons {
 public static final String PREFS="wallpaper_icon_theme",KEY="enabled",TAG="sp_wallpaper_icon_tint";
 private static final WeakHashMap<ImageView,Context> VIEWS=new WeakHashMap<>();
 private static WeakReference<Bitmap> lastBitmap=new WeakReference<>(null);
 private static WeakReference<MainActivity> lastHome=new WeakReference<>(null);
 private static int accent=WallpaperTheme.sample(null).accent;
 private static ColorMatrixColorFilter filter=makeFilter(accent);

 private WallpaperIcons(){}
 public static boolean enabled(Context c){return c.getSharedPreferences(PREFS,0).getBoolean(KEY,true);}
 static MainActivity home(Context c){for(int i=0;i<8&&c!=null;i++){if(c instanceof MainActivity)return (MainActivity)c;if(!(c instanceof ContextWrapper))break;Context next=((ContextWrapper)c).getBaseContext();if(next==c)break;c=next;}return null;}
 static ColorMatrixColorFilter makeFilter(int color){float r=Color.red(color)/255f,g=Color.green(color)/255f,b=Color.blue(color)/255f;return new ColorMatrixColorFilter(new float[]{.2126f*r,.7152f*r,.0722f*r,0,0,.2126f*g,.7152f*g,.0722f*g,0,0,.2126f*b,.7152f*b,.0722f*b,0,0,0,0,0,1,0});}
 static void palette(Bitmap bitmap){lastBitmap=new WeakReference<>(bitmap);int next=WallpaperTheme.sample(bitmap).accent;if(next!=accent){accent=next;filter=makeFilter(accent);}}
 public static void refreshFromHome(MainActivity activity){if(activity==null)return;lastHome=new WeakReference<>(activity);WallpaperPlayer p=activity.wallpaperPlayer;palette(p!=null&&p.enabled?p.current:null);refresh();}
 public static void onWallpaper(WallpaperPlayer player,Bitmap bitmap){if(player==null||player.root==null)return;MainActivity activity=home(player.root.getContext());if(activity==null||activity.wallpaperPlayer!=player)return;lastHome=new WeakReference<>(activity);palette(bitmap);refresh();}
 public static Drawable render(Drawable source,int color){return source==null?null:new ThemedDrawable(source,color,makeFilter(color));}
 public static Drawable sourceOf(Drawable drawable){return drawable instanceof ThemedDrawable?((ThemedDrawable)drawable).source:drawable;}
 public static int colorOf(ImageView view){return view.getDrawable() instanceof ThemedDrawable?((ThemedDrawable)view.getDrawable()).color:0;}
 private static void applyImage(ImageView image,boolean enabled){Drawable current=image.getDrawable();if(current==null)return;if(enabled){if(current instanceof ThemedDrawable)((ThemedDrawable)current).tint(accent,filter);else image.setImageDrawable(new ThemedDrawable(current,accent,filter));}else if(current instanceof ThemedDrawable)image.setImageDrawable(((ThemedDrawable)current).source);}
 public static void bindImage(ImageView image,Context context){if(image==null)return;if(image.getDrawable()==null){VIEWS.remove(image);return;}Context app=context.getApplicationContext();VIEWS.put(image,app);applyImage(image,enabled(app));}
 public static void bind(AppAdapter adapter,AppAdapter.Holder holder){if(adapter==null||holder==null||adapter.ctx==null)return;Object listener=adapter.listener;MainActivity activity=listener instanceof MainActivity?(MainActivity)listener:home(adapter.ctx);if(activity!=null){lastHome=new WeakReference<>(activity);WallpaperPlayer p=activity.wallpaperPlayer;Bitmap bitmap=p!=null&&p.enabled?p.current:null;if(bitmap!=lastBitmap.get()){palette(bitmap);refresh();}}
  bindImage(holder.icon,adapter.ctx);bindImage(holder.banner,adapter.ctx);
 }
 public static void refresh(){Boolean on=null;Iterator<Map.Entry<ImageView,Context>> it=VIEWS.entrySet().iterator();while(it.hasNext()){Map.Entry<ImageView,Context> entry=it.next();ImageView image=entry.getKey();if(image==null||image.getDrawable()==null){it.remove();continue;}if(on==null)on=enabled(entry.getValue());applyImage(image,on);}}
 public static void setEnabled(Context context,boolean value){context.getSharedPreferences(PREFS,0).edit().putBoolean(KEY,value).apply();MainActivity activity=home(context);if(activity==null)activity=lastHome.get();if(activity!=null)refreshFromHome(activity);else refresh();}
 public static void destroy(MainActivity activity){Iterator<ImageView> it=VIEWS.keySet().iterator();while(it.hasNext()){ImageView view=it.next();if(view==null||home(view.getContext())==activity){if(view!=null)applyImage(view,false);it.remove();}}if(lastHome.get()==activity){lastHome=new WeakReference<>(null);lastBitmap=new WeakReference<>(null);}}
 public static void attachSetting(SettingsPopup popup,LinearLayout rows){if(popup==null||rows==null||rows.findViewWithTag(TAG)!=null)return;Context context=popup.activity;TextView row=new TextView(context);row.setTag(TAG);row.setId(View.generateViewId());row.setFocusable(true);row.setClickable(true);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(Math.round(14*popup.dp),0,Math.round(14*popup.dp),0);row.setTextSize(13);row.setTextColor(0xffffffff);row.setSingleLine(true);row.setEllipsize(android.text.TextUtils.TruncateAt.END);row.setBackground(new ClearGlass.Plate(14*popup.dp,popup.dp,-1));
  Runnable label=()->{boolean on=enabled(context);row.setText(popup.vi?("Icon theo màu hình nền · "+(on?"Bật":"Tắt")):("Wallpaper-colored icons · "+(on?"On":"Off")));row.setContentDescription(row.getText());};label.run();row.setOnClickListener(v->{setEnabled(context,!enabled(context));label.run();});row.setAccessibilityDelegate(new View.AccessibilityDelegate(){public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName("android.widget.Switch");info.setCheckable(true);info.setChecked(enabled(context));}});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,Math.round(44*popup.dp));lp.topMargin=Math.round(6*popup.dp);rows.addView(row,lp);
  CrystalSoundEngine.attachSetting(popup,rows);
 }
 private static final class ThemedDrawable extends Drawable {
  final Drawable source;ColorFilter activeFilter;int alpha=255,color;
  ThemedDrawable(Drawable source,int color,ColorMatrixColorFilter filter){this.source=source;this.color=color;this.activeFilter=filter;if(source.getBounds().isEmpty())source.setBounds(0,0,Math.max(1,source.getIntrinsicWidth()),Math.max(1,source.getIntrinsicHeight()));}
  void tint(int color,ColorMatrixColorFilter filter){if(this.color==color)return;this.color=color;this.activeFilter=filter;invalidateSelf();}
  public void draw(Canvas canvas){Rect out=getBounds(),in=source.getBounds();if(out.isEmpty()||in.isEmpty())return;int save=canvas.save();canvas.translate(out.left,out.top);if(out.width()!=in.width()||out.height()!=in.height())canvas.scale(out.width()/(float)in.width(),out.height()/(float)in.height());if(in.left!=0||in.top!=0)canvas.translate(-in.left,-in.top);source.setColorFilter(activeFilter);if(alpha!=255)source.setAlpha(alpha);source.draw(canvas);source.setColorFilter(null);if(alpha!=255)source.setAlpha(255);canvas.restoreToCount(save);}
  public int getIntrinsicWidth(){return source.getIntrinsicWidth();}public int getIntrinsicHeight(){return source.getIntrinsicHeight();}public int getOpacity(){return PixelFormat.TRANSLUCENT;}public void setAlpha(int alpha){this.alpha=alpha;invalidateSelf();}public void setColorFilter(ColorFilter filter){this.activeFilter=filter;invalidateSelf();}
 }
}

