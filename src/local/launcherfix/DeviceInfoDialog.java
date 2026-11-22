package local.launcherfix;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.gathin.tvhome.ClearGlass;
import com.gathin.tvhome.ControlCenter;
import com.gathin.tvhome.MainActivity;
import java.lang.ref.WeakReference;
import java.util.Locale;

/** A static portrait and native device readout, sampled only while its window has focus. */
public final class DeviceInfoDialog extends Dialog implements DeviceInfoReader.Listener {
    private static WeakReference<DeviceInfoDialog> active=new WeakReference<>(null);
    private final Activity activity;
    private final WeakReference<ControlCenter> parent;
    private final WeakReference<View> returnTo;
    private final float density;
    private final boolean vi;
    private final int accent;
    private final LinearLayout panel;
    private final FrameLayout background;
    private final ImageView backdrop,portrait;
    private final TextView hardware,kernel,uptime;
    private final Metric cpu,ram,disk;
    private final DeviceInfoReader reader;
    private boolean disposed,restoreFocus=true;

    private DeviceInfoDialog(ControlCenter center,View anchor){
        super(center.activity);
        activity=center.activity;parent=new WeakReference<>(center);returnTo=new WeakReference<>(anchor);
        density=center.dp;vi=center.vi;accent=WallpaperTheme.current(center).accent;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setOwnerActivity(activity);
        panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);panel.setTag("device_info_panel");
        panel.setPadding(dp(22),dp(18),dp(22),dp(22));panel.setBackground(ClearGlass.panel(dp(24),density));
        panel.setClickable(true);
        panel.setFocusable(true);
        panel.setFocusableInTouchMode(true);
        panel.setDescendantFocusability(LinearLayout.FOCUS_BLOCK_DESCENDANTS);

        LinearLayout header=new LinearLayout(activity);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text(vi?"Thiết bị":"Device",20,accent);
        if(Build.VERSION.SDK_INT>=28)title.setAccessibilityHeading(true);
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        panel.addView(header,new LinearLayout.LayoutParams(-1,dp(48)));

        DisplayMetrics metrics=activity.getResources().getDisplayMetrics();
        int width=Math.min(dp(740),metrics.widthPixels-dp(64));
        int bodyHeight=Math.min(dp(282),metrics.heightPixels-dp(164));
        LinearLayout body=new LinearLayout(activity);body.setGravity(Gravity.TOP);
        LinearLayout art=new LinearLayout(activity);art.setOrientation(LinearLayout.VERTICAL);
        portrait=new ImageView(activity);portrait.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        GradientDrawable crop=new GradientDrawable();crop.setColor(0x18ffffff);crop.setCornerRadius(dp(14));
        portrait.setBackground(crop);portrait.setClipToOutline(true);copyAvatar(center);
        art.addView(portrait,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout palette=new LinearLayout(activity);
        WallpaperTheme.Tone tone=WallpaperTheme.current(center);
        for(int i=0;i<7;i++){
            View swatch=new View(activity);
            swatch.setBackgroundColor(WallpaperTheme.color(255,tone.hue,.12f+.06f*i,.34f+.09f*i));
            LinearLayout.LayoutParams swatchLp=new LinearLayout.LayoutParams(0,dp(6),1);
            if(i>0)swatchLp.setMarginStart(dp(3));palette.addView(swatch,swatchLp);
        }
        LinearLayout.LayoutParams paletteLp=new LinearLayout.LayoutParams(-1,dp(6));paletteLp.topMargin=dp(12);
        art.addView(palette,paletteLp);
        body.addView(art,new LinearLayout.LayoutParams(Math.min(dp(188),width/4),-1));

        LinearLayout details=new LinearLayout(activity);details.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams detailsLp=new LinearLayout.LayoutParams(0,-1,1);detailsLp.setMarginStart(dp(28));
        body.addView(details,detailsLp);
        row(details,"model",Build.MANUFACTURER+" · "+Build.MODEL);
        row(details,"os","Android "+Build.VERSION.RELEASE+" · API "+Build.VERSION.SDK_INT);
        hardware=row(details,"hardware","—");
        cpu=metric(details,"cpu");
        ram=metric(details,"ram");disk=metric(details,"disk /data");
        row(details,"display",metrics.widthPixels+" × "+metrics.heightPixels+" px");
        kernel=row(details,"kernel","—");uptime=row(details,"uptime","—");
        LinearLayout.LayoutParams bodyLp=new LinearLayout.LayoutParams(-1,Math.max(dp(246),bodyHeight));
        bodyLp.topMargin=dp(12);panel.addView(body,bodyLp);

        FrameLayout screen=new FrameLayout(activity);
        background=new FrameLayout(activity);background.setBackgroundColor(0x48000000);
        backdrop=new ImageView(activity);backdrop.setScaleType(ImageView.ScaleType.FIT_XY);
        backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Borrow the control panel's already blurred+dimmed frame; its teardown dismisses us before recycling.
        Bitmap shot=center.shot;
        if(shot!=null&&!shot.isRecycled())backdrop.setImageBitmap(shot);
        backdrop.setColorFilter(null);
        background.addView(backdrop,new FrameLayout.LayoutParams(-1,-1));
        screen.addView(background,new FrameLayout.LayoutParams(-1,-1));
        screen.addView(panel,new FrameLayout.LayoutParams(width,-2,Gravity.CENTER));
        screen.setOnClickListener(v->{CrystalSoundEngine.playClose(activity);PopupOptimizer.dismissAnimated(this,true);});
        setContentView(screen);
        Window window=getWindow();
        if(window!=null){
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));window.setElevation(0);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
            window.setWindowAnimations(0);
        }
        reader=new DeviceInfoReader(activity,this);
        setOnDismissListener(d->dispose());
        CrystalSoundEngine.attachDialog(activity,this);
    }

    public static void show(ControlCenter center,View anchor){
        if(center==null||center.activity==null||!center.open||center.activity.isFinishing()||center.activity.isDestroyed())return;
        DeviceInfoDialog previous=active.get();
        if(previous!=null&&previous.isShowing())return;
        DeviceInfoDialog dialog=new DeviceInfoDialog(center,anchor);active=new WeakReference<>(dialog);
        PopupOptimizer.registerDialog(dialog,dialog.background,dialog.panel,1f,false);
        boolean animate=PopupOptimizer.motionEnabled(center.activity);
        if(animate){
            dialog.background.setAlpha(0f);dialog.panel.setAlpha(0f);
            dialog.panel.setScaleX(.956f);dialog.panel.setScaleY(.956f);
            dialog.panel.setTranslationY(dialog.dp(11));
        }
        dialog.show();Window window=dialog.getWindow();if(window!=null)window.setLayout(-1,-1);
        dialog.panel.requestFocus();
        if(animate){
            dialog.panel.postOnAnimation(()->{
                if(!dialog.panel.isAttachedToWindow()||PopupOptimizer.isClosing(dialog))return;
                dialog.background.animate().alpha(1f).setDuration(255L)
                    .setInterpolator(PopupOptimizer.ENTRANCE_EASE).start();
                dialog.panel.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(285L).setInterpolator(PopupOptimizer.ENTRANCE_EASE).withLayer().start();
            });
        }
    }
    public static boolean dismissForActivity(Activity owner){
        DeviceInfoDialog dialog=active.get();if(dialog==null||dialog.activity!=owner)return false;
        dialog.restoreFocus=false;dialog.dispose();PopupOptimizer.dismissImmediate(dialog,false);return true;
    }
    public static boolean dismissFromBack(Activity owner){
        DeviceInfoDialog dialog=active.get();if(dialog==null||dialog.activity!=owner||dialog.disposed)return false;
        CrystalSoundEngine.playClose(owner);PopupOptimizer.dismissAnimated(dialog,true);return true;
    }
    @Override public void onWindowFocusChanged(boolean focused){
        super.onWindowFocusChanged(focused);
        if(reader==null)return;
        if(focused&&!disposed&&!activity.isFinishing()&&!activity.isDestroyed())reader.start();else reader.stop();
    }
    @Override protected void onStop(){
        panel.animate().cancel();background.animate().cancel();
        if(reader!=null)reader.stop();super.onStop();
    }
    private void dispose(){
        if(disposed)return;disposed=true;reader.stop();
        panel.animate().cancel();background.animate().cancel();
        if(active.get()==this)active=new WeakReference<>(null);
        backdrop.setImageDrawable(null);portrait.setImageDrawable(null);
        ControlCenter center=parent.get();View anchor=returnTo.get();
        if(restoreFocus&&center!=null&&center.open&&!activity.isFinishing()&&!activity.isDestroyed()
                &&anchor!=null&&anchor.isAttachedToWindow())anchor.requestFocus();
    }
    private int dp(float value){return Math.round(value*density);}
    private TextView text(String value,float size,int color){
        TextView text=new TextView(activity);text.setText(value);text.setTextSize(size);text.setTextColor(color);
        text.setTypeface(Typeface.MONOSPACE);text.setSingleLine(true);text.setHorizontallyScrolling(false);
        text.setEllipsize(TextUtils.TruncateAt.END);text.setIncludeFontPadding(false);text.setFontFeatureSettings("tnum");
        text.setGravity(Gravity.CENTER_VERTICAL);return text;
    }
    private TextView row(LinearLayout parent,String name,String value){
        LinearLayout line=new LinearLayout(activity);line.setGravity(Gravity.CENTER_VERTICAL);
        TextView label=text(name,12,accent);line.addView(label,new LinearLayout.LayoutParams(dp(88),-1));
        TextView output=text(value,12,ReferenceControlPanel.TEXT);
        line.addView(output,new LinearLayout.LayoutParams(0,-1,1));
        parent.addView(line,new LinearLayout.LayoutParams(-1,0,1));return output;
    }
    private Metric metric(LinearLayout parent,String name){
        LinearLayout block=new LinearLayout(activity);block.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line=new LinearLayout(activity);line.setGravity(Gravity.CENTER_VERTICAL);
        TextView label=text(name,12,accent);line.addView(label,new LinearLayout.LayoutParams(dp(88),-1));
        TextView value=text("—",12,ReferenceControlPanel.TEXT);line.addView(value,new LinearLayout.LayoutParams(0,-1,1));
        block.addView(line,new LinearLayout.LayoutParams(-1,0,1));
        ProgressBar bar=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(false);bar.setMax(100);bar.setProgress(0);
        bar.setProgressTintList(ColorStateList.valueOf(accent));bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x26ffffff));
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams barLp=new LinearLayout.LayoutParams(-1,dp(2));barLp.setMarginStart(dp(88));barLp.bottomMargin=dp(3);
        block.addView(bar,barLp);parent.addView(block,new LinearLayout.LayoutParams(-1,0,1));
        return new Metric(label,value,bar);
    }
    private void copyAvatar(ControlCenter center){
        View avatar=center.panel==null?null:center.panel.findViewWithTag("cc_reference_avatar");
        Drawable source=avatar instanceof ImageView?((ImageView)avatar).getDrawable():null;
        if(source instanceof BitmapDrawable){
            Bitmap bitmap=((BitmapDrawable)source).getBitmap();
            if(bitmap!=null&&!bitmap.isRecycled()){portrait.setImageBitmap(bitmap);portrait.setScaleType(ImageView.ScaleType.CENTER_CROP);return;}
        }
        portrait.setScaleType(ImageView.ScaleType.CENTER);
        portrait.setImageDrawable(new ControlCenter.Glyph(ControlCenter.Glyph.ICON,density,72));
    }
    @Override public void onReading(DeviceInfoReader.Reading reading){
        if(disposed||!isShowing())return;
        changed(hardware,reading.hardware);changed(kernel,reading.kernel);
        String source=reading.cpuSource==1?" · TV":reading.cpuSource==2?" · Launcher":"";
        String cpuSpoken=reading.cpuSource==2?(vi?"CPU Launcher, phần trăm tổng năng lực CPU":"Launcher CPU, percent of total CPU capacity"):
            (vi?"CPU toàn TV":"TV CPU");
        cpu.update(percent(reading.cpuPercent)+source,reading.cpuPercent,cpuSpoken);
        ram.update(capacity(reading.ramUsed,reading.ramTotal,reading.ramPercent),reading.ramPercent,vi?"RAM hệ thống đang dùng":"System RAM used");
        int diskPercent=reading.storageTotal<=0?-1:(int)Math.round(100d*reading.storageUsed/reading.storageTotal);
        disk.update(capacity(reading.storageUsed,reading.storageTotal,diskPercent),diskPercent,vi?"Phân vùng dữ liệu đang dùng":"Data partition used");
        long minutes=reading.uptimeMs/60000,days=minutes/1440,hours=minutes/60%24;
        String duration=(days>0?days+"d ":"")+hours+"h "+minutes%60+"m";
        changed(uptime,duration);
    }
    private static String percent(int value){return value<0?"—":value+"%";}
    private static String capacity(long used,long total,int percent){
        if(used<0||total<=0||percent<0)return "—";
        return String.format(Locale.US,"%.2f / %.2f GiB · %d%%",used/1073741824d,total/1073741824d,percent);
    }
    private static void changed(TextView view,String value){
        if(!TextUtils.equals(view.getText(),value))view.setText(value);
    }
    private static final class Metric {
        final TextView label,value;final ProgressBar bar;
        Metric(TextView label,TextView value,ProgressBar bar){this.label=label;this.value=value;this.bar=bar;}
        void update(String text,int amount,String description){
            changed(value,text);String spoken=description+": "+text;
            if(!TextUtils.equals(value.getContentDescription(),spoken))value.setContentDescription(spoken);
            int progress=Math.max(0,Math.min(100,amount));if(bar.getProgress()!=progress)bar.setProgress(progress);
            float alpha=amount<0?.25f:1f;if(bar.getAlpha()!=alpha)bar.setAlpha(alpha);
        }
    }
}
