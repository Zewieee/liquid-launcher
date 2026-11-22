package local.launcherfix;
import android.graphics.*;import android.graphics.drawable.*;import android.view.*;import android.widget.*;import com.gathin.tvhome.*;
import java.lang.ref.WeakReference;

/** Wallpaper-derived glass tint from at most 96 existing software bitmap pixels, cached by generation. */
public final class WallpaperTheme {
 public static final class Tone {
  public final int accent,top,middle,bottom,tile,focus,hairline,hue;
  Tone(float rawHue,float saturation){
   float h=calibrateHue(rawHue);
   this.hue=Math.round(h);
   float s=Math.min(.42f,Math.max(.08f,saturation*.60f));
   accent=color(255,h,Math.min(.26f,s*.65f),.98f);
   top=color(96,h,s*.38f,.34f);
   middle=color(118,h,s*.65f,.19f);
   bottom=color(152,h,s*.82f,.09f);
   tile=color(78,h,s*.68f,.17f);
   focus=color(212,h,s*.68f,.35f);
   hairline=(accent&0x00ffffff)|0x24000000;
  }
 }
 static float calibrateHue(float h){
  float norm=((h%360f)+360f)%360f;
  // Taste-Skill Rule 2 & system.md: Never violet/lavender (268..332 deg -> 248 blue or 346 rose)
  if(norm>268f&&norm<332f)return norm<300f?248f:346f;
  return norm;
 }
 private static final Tone DEFAULT_TONE=new Tone(215,.12f);
 private static final java.util.WeakHashMap<View,Tone> APPLIED_TONES=new java.util.WeakHashMap<>();
 private static WeakReference<Bitmap> cachedBitmap;
 private static int cachedGen=-1,cachedW=-1,cachedH=-1;
 private static Tone cachedTone;
 static int color(int alpha,float h,float s,float v){return Color.HSVToColor(alpha,new float[]{h,s,v});}
 public static synchronized void trimCache(){cachedBitmap=null;cachedTone=null;cachedGen=-1;cachedW=-1;cachedH=-1;}
 public static Tone sample(Bitmap bitmap){
  if(bitmap==null||bitmap.isRecycled())return DEFAULT_TONE;
  try{
   int width=bitmap.getWidth(),height=bitmap.getHeight();if(width<1||height<1)return DEFAULT_TONE;
   int gen=bitmap.getGenerationId();
   synchronized(WallpaperTheme.class){
    if(cachedTone!=null&&cachedBitmap!=null&&cachedBitmap.get()==bitmap&&cachedGen==gen&&cachedW==width&&cachedH==height)return cachedTone;
   }
   float[] weights=new float[13],r=new float[13],g=new float[13],b=new float[13],hsv=new float[3];
   for(int y=0;y<8;y++)for(int x=0;x<12;x++){
    int pixel=bitmap.getPixel(Math.min(width-1,(int)((x+.5f)*width/12)),Math.min(height-1,(int)((y+.5f)*height/8)));if(Color.alpha(pixel)<128)continue;
    float luma=(Color.red(pixel)*.2126f+Color.green(pixel)*.7152f+Color.blue(pixel)*.0722f)/255f;if(luma<.02f||luma>.94f)continue;
    Color.colorToHSV(pixel,hsv);int bucket=hsv[1]<.10f?12:Math.min(11,(int)(hsv[0]/30));float weight=.18f+hsv[1]*.82f;weights[bucket]+=weight;r[bucket]+=Color.red(pixel)*weight;g[bucket]+=Color.green(pixel)*weight;b[bucket]+=Color.blue(pixel)*weight;
   }
   int best=0;for(int i=1;i<13;i++)if(weights[i]>weights[best])best=i;
   Tone result;
   if(weights[best]<=0){result=DEFAULT_TONE;}
   else{int dominant=Color.rgb(Math.round(r[best]/weights[best]),Math.round(g[best]/weights[best]),Math.round(b[best]/weights[best]));Color.colorToHSV(dominant,hsv);result=new Tone(best==12?215:hsv[0],best==12?.12f:hsv[1]);}
   synchronized(WallpaperTheme.class){cachedBitmap=new WeakReference<>(bitmap);cachedGen=gen;cachedW=width;cachedH=height;cachedTone=result;}
   return result;
  }catch(RuntimeException ignored){return DEFAULT_TONE;}
 }
 public static Tone current(ControlCenter c){Bitmap bitmap=null;if(c!=null&&c.activity instanceof MainActivity){WallpaperPlayer player=((MainActivity)c.activity).wallpaperPlayer;if(player!=null)bitmap=player.current;}if(bitmap==null&&c!=null)bitmap=c.shot;return sample(bitmap);}
 public static GradientDrawable glass(float density,Tone tone){GradientDrawable bg=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{tone.top,tone.middle,tone.bottom});bg.setCornerRadius(20*density);return bg;}
 static GradientDrawable surface(float d,int color){GradientDrawable bg=new GradientDrawable();bg.setColor(color);bg.setCornerRadius(12*d);return bg;}
 // One light material for idle cards and image veils; only the content beneath differs.
 static GradientDrawable tileGlass(float radius,Tone tone){
  int rgb=tone.accent&0xffffff;
  GradientDrawable bg=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
    new int[]{rgb|0x1c000000,rgb|0x10000000});
  bg.setCornerRadius(radius);return bg;
 }
 // A translucent accent wash keeps the glass visible without animating every focus change.
 static GradientDrawable focusWash(float radius,Tone tone,boolean pressed){
  int rgb=tone.accent&0xffffff;
  GradientDrawable bg=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
    new int[]{rgb|(pressed?0x60000000:0x48000000),rgb|(pressed?0x30000000:0x1c000000)});
  bg.setCornerRadius(radius);return bg;
 }
 static StateListDrawable states(float d,Tone tone){
  StateListDrawable bg=new StateListDrawable();
  // Activated identifies slider adjustment mode and must remain stronger than focus.
  bg.addState(new int[]{android.R.attr.state_activated},surface(d,tone.focus|0xff000000));
  bg.addState(new int[]{android.R.attr.state_pressed},focusWash(12*d,tone,true));
  bg.addState(new int[]{android.R.attr.state_focused},focusWash(12*d,tone,false));
  bg.addState(new int[]{},tileGlass(12*d,tone));return bg;
 }
 static StateListDrawable imageFocus(float radius,Tone tone){
  StateListDrawable highlight=new StateListDrawable();
  highlight.addState(new int[]{android.R.attr.state_pressed},focusWash(radius,tone,true));
  highlight.addState(new int[]{android.R.attr.state_focused},focusWash(radius,tone,false));
  highlight.addState(new int[]{},tileGlass(radius,tone));return highlight;
 }
 public static void apply(ControlCenter c,Bitmap bitmap){if(c==null||c.panel==null)return;apply(c.panel,sample(bitmap),c.dp);}
 public static void applyCurrent(ControlCenter c){if(c!=null&&c.panel!=null){WallpaperPreview.update(c,WallpaperPreview.current(c));apply(c.panel,current(c),c.dp);}}
 static void apply(LinearLayout root,Tone tone,float density){
  if(root==null||tone==null)return;
  synchronized(APPLIED_TONES){
   if(APPLIED_TONES.get(root)==tone)return;
   APPLIED_TONES.put(root,tone);
  }
  root.setBackground(glass(density,tone));tint(root,tone,density);
 }
 static void tint(View view,Tone tone,float d){
  Object tag=view.getTag();
  if(view instanceof ReferenceControlPanel.Ring){((ReferenceControlPanel.Ring)view).accent=tone.accent;view.invalidate();}
  if("cc_reference_clock".equals(tag)||"cc_theme_accent".equals(tag)){((TextView)view).setTextColor(tone.accent);}
  if(view.isFocusable()&&view.hasOnClickListeners()&&!"cc_reference_avatar".equals(tag)&&!"cc_reference_wallpaper".equals(tag))view.setBackground(states(d,tone));
  if("cc_reference_wallpaper".equals(tag)&&view instanceof FrameLayout){
   // Keep its clipping shape, but apply the material only in front of transparent images.
   view.setBackground(surface(d,Color.TRANSPARENT));view.setForeground(imageFocus(12*d,tone));
  }
  if("cc_reference_avatar".equals(tag))view.setForeground(imageFocus(24*d,tone));
  // Do not darken the quick controls a second time behind their individual glass cards.
  if("cc_reference_utilities".equals(tag))view.setBackground(null);
  if(tag instanceof String&&((String)tag).startsWith("cc_theme_dot:")){int index=Integer.parseInt(((String)tag).substring(13));GradientDrawable dot=new GradientDrawable();dot.setShape(GradientDrawable.OVAL);dot.setColor(color(255,tone.hue,.12f+.06f*index,.34f+.09f*index));view.setBackground(dot);}
  if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)tint(group.getChildAt(i),tone,d);}
 }
 public static void onWallpaper(WallpaperPlayer player,Bitmap bitmap){ControlCenter c=ControlPanelHelper.getActiveCenter();if(c==null||!c.open||!(c.activity instanceof MainActivity)||((MainActivity)c.activity).wallpaperPlayer!=player)return;WallpaperPreview.update(c,bitmap);apply(c,bitmap);}
}
