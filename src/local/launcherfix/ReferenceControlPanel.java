package local.launcherfix;

import android.app.Activity;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Build;
import android.media.AudioManager;
import android.text.*;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.view.*;
import android.widget.*;
import com.gathin.tvhome.*;

/** Native three-column panel: no sample telemetry, remote assets or polling. */
final class ReferenceControlPanel {
 static final int ICON_DP=20;
 static final float LABEL_SP=12f,SECONDARY_SP=11f;
 static final int ACCENT=0xfff2f1f6,TEXT=0xfffafbff,MUTED=0xffd3dbe5,TILE=0x30ffffff;
 final ControlCenter c;final Activity a;final float d;final boolean vi;final Typeface medium,body;
 ReferenceControlPanel(ControlCenter center){c=center;a=c.activity;d=c.dp;vi=c.vi;medium=c.callFont("font_medium");body=c.callFont("font_body");}
 int px(float n){return Math.round(n*d);}String t(String vn,String en){return vi?vn:en;}
 static void position(ControlCenter c){
  DisplayMetrics dm=c.activity.getResources().getDisplayMetrics();
  int w=Math.min(Math.round(660*c.dp),dm.widthPixels-Math.round(64*c.dp));
  int h=Math.min(Math.round(360*c.dp),dm.heightPixels-Math.round(64*c.dp));
  c.panel.setLayoutParams(new FrameLayout.LayoutParams(Math.max(1,w),Math.max(1,h),Gravity.CENTER));
  c.panel.setScaleX(1f);c.panel.setScaleY(1f);c.panel.setPivotX(w/2f);c.panel.setPivotY(h/2f);
 }
 static GradientDrawable frosted(float d){return WallpaperTheme.glass(d,WallpaperTheme.sample(null));}
 static LinearLayout build(ControlCenter c){return new ReferenceControlPanel(c).build();}
 LinearLayout column(String tag){LinearLayout l=new LinearLayout(a);l.setOrientation(LinearLayout.VERTICAL);l.setTag(tag);return l;}
 GradientDrawable surface(int color){GradientDrawable bg=new GradientDrawable();bg.setColor(color);bg.setCornerRadius(px(12));return bg;}
 StateListDrawable tileBackground(){StateListDrawable states=new StateListDrawable();states.addState(new int[]{android.R.attr.state_focused},surface(0x786e829a));states.addState(new int[]{android.R.attr.state_pressed},surface(0x927f94ac));states.addState(new int[]{},surface(TILE));return states;}
 TextView text(String value,float sp,boolean strong){TextView v=new TextView(a);v.setText(value);v.setTextSize(sp);v.setTextColor(strong?TEXT:MUTED);v.setTypeface(strong?medium:body);v.setFontFeatureSettings("tnum");v.setSingleLine(true);v.setEllipsize(TextUtils.TruncateAt.END);v.setIncludeFontPadding(false);return v;}
 TextView caption(String value){TextView v=text(value,LABEL_SP,true);v.setLetterSpacing(0.02f);v.setTextColor(ACCENT);v.setTag("cc_theme_accent");return v;}
 void focus(View v,String tag,String spoken,Runnable action){v.setTag(tag);v.setId(View.generateViewId());v.setFocusable(true);v.setClickable(true);v.setContentDescription(spoken);v.setOnClickListener(view->action.run());}
 void add(LinearLayout parent,View view,int height,int top){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,height<0?height:px(height));lp.topMargin=px(top);parent.addView(view,lp);}
 LinearLayout action(String title,String subtitle,int glyph,String tag,Runnable run){
  LinearLayout box=column(tag);box.setGravity(Gravity.CENTER_VERTICAL);box.setPadding(px(12),px(8),px(12),px(8));focus(box,tag,title,run);
  LinearLayout heading=new LinearLayout(a);heading.setGravity(Gravity.CENTER_VERTICAL);ImageView icon=new ImageView(a);icon.setImageDrawable(new ControlCenter.Glyph(glyph,d,ICON_DP));heading.addView(icon,new LinearLayout.LayoutParams(px(ICON_DP),px(ICON_DP)));
  LinearLayout.LayoutParams label=new LinearLayout.LayoutParams(0,-2,1);label.setMarginStart(px(8));heading.addView(caption(title),label);box.addView(heading,new LinearLayout.LayoutParams(-1,-2));
  if(subtitle!=null){TextView sub=text(subtitle,SECONDARY_SP,false);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=px(8);box.addView(sub,lp);}return box;
 }
 LinearLayout build(){
  LinearLayout root=new LinearLayout(a);root.setTag(ControlPanelHelper.TAG_PANEL_V2);root.setOrientation(LinearLayout.HORIZONTAL);root.setPadding(px(8),px(8),px(8),px(8));root.setBackground(frosted(d));root.setElevation(px(12));
  LinearLayout left=column("cc_reference_left"),middle=column("cc_reference_middle"),right=column("cc_reference_right");
  LinearLayout.LayoutParams l=new LinearLayout.LayoutParams(0,-1,30);root.addView(left,l);LinearLayout.LayoutParams m=new LinearLayout.LayoutParams(0,-1,40);m.setMarginStart(px(6));m.setMarginEnd(px(6));root.addView(middle,m);root.addView(right,new LinearLayout.LayoutParams(0,-1,30));

  // Left: real network, system memory/device details, and wallpaper shortcut.
  LinearLayout network=action(t("Kết nối","Network"),null,ControlCenter.Glyph.NETWORK,"cc_reference_network",c::callOpenNetwork);
  c.networkState=text(t("Đang kiểm tra…","Checking…"),SECONDARY_SP,false);c.networkState.setTag(ControlPanelHelper.TAG_NET_SUB);c.networkDot=new GradientDrawable();c.networkDot.setShape(GradientDrawable.OVAL);c.networkDot.setColor(ACCENT);c.networkDot.setBounds(0,0,px(5),px(5));c.networkState.setCompoundDrawablesRelative(c.networkDot,null,null,null);c.networkState.setCompoundDrawablePadding(px(6));
  // Align subtitle text with the heading after accounting for the status dot.
  c.networkState.setPaddingRelative(px(ICON_DP+8-5-6),0,0,0);add(network,c.networkState,-2,8);add(left,network,76,0);
  LinearLayout system=column("cc_reference_system");system.setPadding(px(12),px(12),px(12),px(12));focus(system,"cc_reference_system",t("Thông tin thiết bị","Device information"),()->DeviceInfoDialog.show(c,system));add(system,caption(t("Thiết bị","Device")),-2,0);
  add(system,text(Build.MODEL,15,true),-2,10);add(system,text("Android "+Build.VERSION.RELEASE,SECONDARY_SP,false),-2,8);
  TextView ram=text("",SECONDARY_SP,false);ram.setTag(ControlPanelHelper.TAG_RAM_STATE);ram.setSingleLine(false);ram.setMaxLines(2);add(system,ram,-2,8);
  LinearLayout colors=new LinearLayout(a);int[] palette={0xff3b5978,0xff4f759b,0xff6d93b8,0xff94b4d1,0xffb8cfe3,0xffd6e4f0,0xffeef4f8};for(int color:palette){View dot=new View(a);dot.setTag("cc_theme_dot:"+colors.getChildCount());GradientDrawable bg=new GradientDrawable();bg.setShape(GradientDrawable.OVAL);bg.setColor(color);dot.setBackground(bg);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(px(9),px(9));lp.setMarginEnd(px(8));colors.addView(dot,lp);}add(system,colors,-2,10);
  LinearLayout.LayoutParams infoLp=new LinearLayout.LayoutParams(-1,0,1);infoLp.topMargin=px(8);left.addView(system,infoLp);
  android.widget.FrameLayout wallpaper=new android.widget.FrameLayout(a);focus(wallpaper,"cc_reference_wallpaper",t("Hình nền đang dùng","Current wallpaper"),c::callOpenWallpaper);wallpaper.setClipToOutline(true);
  ImageView preview=new ImageView(a);WallpaperPreview.bind(preview,c);wallpaper.addView(preview,new android.widget.FrameLayout.LayoutParams(-1,-1));
  View shade=new View(a);shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x08000000,0xb8000000}));wallpaper.addView(shade,new android.widget.FrameLayout.LayoutParams(-1,-1));
  LinearLayout previewLabel=new LinearLayout(a);previewLabel.setGravity(Gravity.CENTER_VERTICAL);previewLabel.setPadding(px(12),px(8),px(12),px(8));ImageView previewIcon=new ImageView(a);previewIcon.setImageDrawable(new ControlCenter.Glyph(ControlCenter.Glyph.WALLPAPER,d,ICON_DP));previewLabel.addView(previewIcon,new LinearLayout.LayoutParams(px(ICON_DP),px(ICON_DP)));TextView previewTitle=text(t("Hình nền","Wallpaper"),LABEL_SP,true);previewTitle.setTextColor(0xffffffff);LinearLayout.LayoutParams titleLp=new LinearLayout.LayoutParams(0,-2,1);titleLp.setMarginStart(px(8));previewLabel.addView(previewTitle,titleLp);wallpaper.addView(previewLabel,new android.widget.FrameLayout.LayoutParams(-1,px(38),Gravity.BOTTOM));add(left,wallpaper,94,8);

  // Center: native clocks and central avatar (no redundant close/settings row).
  middle.setGravity(Gravity.CENTER_HORIZONTAL);middle.setPadding(px(10),px(10),px(10),px(10));
  TextClock clock=new TextClock(a);clock.setTag("cc_reference_clock");clock.setFormat24Hour("HHmm");clock.setFormat12Hour("HHmm");clock.setTextSize(90);clock.setTextScaleX(.70f);clock.setLetterSpacing(-.045f);clock.setTextColor(ACCENT);clock.setTypeface(Typeface.create("sans-serif-condensed",Typeface.BOLD));clock.setGravity(Gravity.CENTER);clock.setSingleLine(true);clock.setIncludeFontPadding(false);clock.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);add(middle,clock,104,0);
  TextClock date=new TextClock(a);date.setTag("cc_reference_date");String datePattern="EEEE '·' d MMM";date.setFormat24Hour(datePattern);date.setFormat12Hour(datePattern);date.setTextColor(TEXT);date.setTextSize(10);date.setAllCaps(true);date.setLetterSpacing(.08f);date.setTypeface(body);date.setGravity(Gravity.CENTER);date.setSingleLine(true);date.setIncludeFontPadding(false);add(middle,date,18,0);
  View springTop=new View(a);middle.addView(springTop,new LinearLayout.LayoutParams(1,0,1));
  ImageView avatar=new ImageView(a);avatar.setTag("cc_reference_avatar");avatar.setId(View.generateViewId());avatar.setFocusable(true);avatar.setClickable(true);avatar.setContentDescription(t("Đổi avatar","Change avatar"));avatar.setPadding(0,0,0,0);avatar.setClipToOutline(true);
  GradientDrawable avatarShape=new GradientDrawable();avatarShape.setColor(Color.TRANSPARENT);avatarShape.setCornerRadius(px(24));avatar.setBackground(avatarShape);
  StateListDrawable avatarFocus=new StateListDrawable();GradientDrawable selected=new GradientDrawable();selected.setColor(0x38eaf2ff);selected.setCornerRadius(px(24));avatarFocus.addState(new int[]{android.R.attr.state_focused},selected);avatarFocus.addState(new int[]{android.R.attr.state_pressed},selected);avatarFocus.addState(new int[]{},new ColorDrawable(Color.TRANSPARENT));avatar.setForeground(avatarFocus);
  avatar.setImageDrawable(new ControlCenter.Glyph(ControlCenter.Glyph.ICON,d,56));avatar.setScaleType(ImageView.ScaleType.CENTER);avatar.setOnClickListener(v->AvatarStore.showChooser(c,avatar));AvatarStore.bind(avatar);
  LinearLayout.LayoutParams avatarLp=new LinearLayout.LayoutParams(px(140),px(140));middle.addView(avatar,avatarLp);
  View springBottom=new View(a);middle.addView(springBottom,new LinearLayout.LayoutParams(1,0,1));

  // Right: three compact native metrics above a single tall actions area.
  LinearLayout row1=new LinearLayout(a);row1.setOrientation(LinearLayout.HORIZONTAL);
  TextView brightnessValue=text("—",SECONDARY_SP,false),volumeValue=text("—",SECONDARY_SP,false),pictureValue=text("",SECONDARY_SP,false);
  c.backlightValue=brightnessValue;c.volumeValue=volumeValue;c.pictureValue=pictureValue;
  c.backlightGlass=new ClearGlass.Plate(px(6),d,-1);c.volumeGlass=new ClearGlass.Plate(px(6),d,-1);
  LinearLayout brightness=quick(t("Đèn nền","Backlight"),ControlCenter.Glyph.SUN,brightnessValue,"cc_reference_brightness",()->ControlPanelHelper.cycleOrOpenBacklight(c));
  LinearLayout volume=quick(t("Âm lượng","Volume"),ControlCenter.Glyph.SOUND,volumeValue,"cc_reference_volume",()->ControlPanelHelper.toggleMute(c));
  c.backlightSlider=brightness;c.volumeSlider=volume;
  brightness.setOnLongClickListener(v->{c.callOpenPicture();return true;});volume.setOnLongClickListener(v->{c.callOpenTv(".device.sound.SoundActivity","android.settings.SOUND_SETTINGS");return true;});
  LinearLayout picture=quick(t("Hình ảnh","Picture"),ControlCenter.Glyph.PICTURE,pictureValue,"cc_reference_picture",()->ControlPanelHelper.cycleOrOpenPicture(c));c.pictureModule=picture;
  TextView dockTile=button(t("Thanh Dock","Dock"),ControlCenter.Glyph.DOCK,ControlPanelHelper.TAG_DOCK_CARD,c::callOpenDock);
  for(View tile:new View[]{brightness,volume,picture}){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);if(tile!=brightness)lp.setMarginStart(px(5));row1.addView(tile,lp);}add(right,row1,88,0);
  LinearLayout utilities=column("cc_reference_utilities");utilities.setPadding(px(8),px(12),px(8),px(8));utilities.setBackground(surface(TILE));TextView heading=caption(t("Điều khiển nhanh","Quick controls"));heading.setTag("cc_adjustment_status");heading.setTextSize(SECONDARY_SP);heading.setPadding(px(12),0,px(12),px(8));utilities.addView(heading,new LinearLayout.LayoutParams(-1,-2));
  TextView inputs=button(t("Nguồn vào","Inputs"),ControlCenter.Glyph.INPUT,"cc_reference_inputs",c::callOpenInputs),tv=button(t("Cài đặt TV","TV settings"),ControlCenter.Glyph.GEAR,ControlPanelHelper.TAG_TV_SETTINGS,c::callOpenSettings),home=button(t("Cài đặt Launcher","Launcher settings"),ControlCenter.Glyph.SLIDERS,ControlPanelHelper.TAG_HOME_SETTINGS,c::callOpenLauncherSettings);
  for(TextView button:new TextView[]{dockTile,inputs,tv,home}){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,0,1);if(button!=dockTile)lp.topMargin=px(5);utilities.addView(button,lp);}LinearLayout.LayoutParams utilLp=new LinearLayout.LayoutParams(-1,0,1);utilLp.topMargin=px(8);right.addView(utilities,utilLp);
  new AdjustmentMode(brightness,heading,vi,c::callNudgeBacklight,()->c.canSetBacklight,c::callOpenPicture);
  new AdjustmentMode(volume,heading,vi,c::callNudgeVolume,()->true,()->c.callOpenTv(".device.sound.SoundActivity","android.settings.SOUND_SETTINGS"));
  c.firstButton=network;c.topRow=row1;c.bottomRow=utilities;
  // Explicit D-pad graph across Left (network, device, wallpaper), Middle (avatar), and Right (row1 + utilities).
  link(network,network,avatar,network,system);link(system,system,avatar,network,wallpaper);link(wallpaper,wallpaper,avatar,system,wallpaper);
  link(avatar,system,brightness,avatar,avatar);
  link(brightness,avatar,volume,brightness,dockTile);link(volume,brightness,picture,volume,dockTile);
  link(picture,volume,picture,picture,dockTile);link(dockTile,avatar,dockTile,brightness,inputs);
  link(inputs,avatar,inputs,dockTile,tv);link(tv,avatar,tv,inputs,home);link(home,avatar,home,tv,home);
  WallpaperTheme.apply(root,WallpaperTheme.current(c),d);
  return root;
 }
 TextView button(String title,int glyph,String tag,Runnable run){
  TextView b=text(title,LABEL_SP,true);b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setPadding(px(12),0,px(12),0);
  Drawable icon=new ControlCenter.Glyph(glyph,d,ICON_DP);icon.setBounds(0,0,px(ICON_DP),px(ICON_DP));
  b.setCompoundDrawablesRelative(icon,null,null,null);b.setCompoundDrawablePadding(px(8));focus(b,tag,title,run);return b;
 }
 void link(View v,View left,View right,View up,View down){v.setNextFocusLeftId(left.getId());v.setNextFocusRightId(right.getId());v.setNextFocusUpId(up.getId());v.setNextFocusDownId(down.getId());}
 LinearLayout quick(String title,int glyph,TextView value,String tag,Runnable run){
  LinearLayout box=column(tag);box.setGravity(Gravity.CENTER);box.setPadding(px(5),px(5),px(5),px(5));focus(box,tag,title,run);
  Ring ring=new Ring(a,new ControlCenter.Glyph(glyph,d,ICON_DP),value);box.addView(ring,new LinearLayout.LayoutParams(px(34),px(34)));
  TextView label=text(title,11,true);label.setGravity(Gravity.CENTER);add(box,label,18,4);value.setGravity(Gravity.CENTER);add(box,value,16,1);
  value.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){ring.update(s);}public void afterTextChanged(Editable e){}});ring.update(value.getText());return box;
 }
 final class Ring extends View {
  final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);final RectF bounds=new RectF();final Drawable glyph;final boolean percent;final int volumeMax;float level=-1;int accent=ACCENT;
  Ring(Activity a,Drawable glyph,TextView value){super(a);this.glyph=glyph;percent=value==c.backlightValue||value==c.volumeValue;int max=0;if(value==c.volumeValue)try{AudioManager audio=(AudioManager)a.getSystemService(Activity.AUDIO_SERVICE);if(audio!=null)max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);}catch(RuntimeException ignored){}volumeMax=max;setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
  void update(CharSequence s){level=-1;if(percent&&s!=null){String value=s.toString().trim();try{if(value.endsWith("%"))level=Math.max(0,Math.min(100,Float.parseFloat(value.substring(0,value.length()-1))))/100f;else if(volumeMax>0)level=Math.max(0,Math.min(volumeMax,Float.parseFloat(value)))/volumeMax;}catch(NumberFormatException ignored){}}invalidate();}
  protected void onDraw(Canvas canvas){float inset=px(3);bounds.set(inset,inset,getWidth()-inset,getHeight()-inset);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(px(2));p.setStrokeCap(Paint.Cap.ROUND);p.setColor(0x55ffffff);canvas.drawOval(bounds,p);p.setColor(accent);if(level>=0)canvas.drawArc(bounds,-90,level*360,false,p);else if(!percent)canvas.drawArc(bounds,-110,265,false,p);int size=px(ICON_DP),x=(getWidth()-size)/2,y=(getHeight()-size)/2;glyph.setBounds(x,y,x+size,y+size);glyph.draw(canvas);}
 }
}
