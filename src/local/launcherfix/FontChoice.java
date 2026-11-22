package local.launcherfix;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.util.SparseArray;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import com.gathin.tvhome.Backdrop;
import com.gathin.tvhome.ClearGlass;
import com.gathin.tvhome.SettingsPopup;
import java.util.ArrayList;
import java.util.Locale;
import java.util.WeakHashMap;
import java.lang.ref.WeakReference;

/** UI-thread font selection; only created/bound views are visited, never a frame loop. */
public final class FontChoice {
    private static final String PREFS="launcher_font",KEY="family",PREVIEW="launcher_font_preview";
    private static final int CUSTOM=5;
    private static final String[] KEYS={"default","sans","serif","mono","condensed","custom"};
    private static final String[] FAMILIES={null,"sans-serif","serif","monospace","sans-serif-condensed",null};
    private static final String[] NAMES={"Default","Sans","Serif","Mono","Condensed","Imported font"};
    private static final WeakHashMap<TextView,Face> VIEWS=new WeakHashMap<>();
    private static final SparseArray<Typeface> STYLES=new SparseArray<>();
    private static int selected=-1;
    private static Typeface family;
    private static Typeface customFace;
    private static String customName;
    private static boolean customLoadStarted;
    private static long selectionEpoch;
    private static long faceEpoch;
    private static WeakReference<LinearLayout> visibleOptions=new WeakReference<>(null);
    private static WeakReference<Dialog> visibleDialog=new WeakReference<>(null);

    private static final class Face {
        Typeface original,lastApplied;
        Face(Typeface face){original=lastApplied=face;}
    }
    private FontChoice(){}
    private static boolean vi(Context context){return "vi".equals(Locale.getDefault().getLanguage());}
    private static String name(int index,boolean vi){return index==CUSTOM&&customName!=null?customName:index==0&&vi?"Mặc định":NAMES[index];}
    private static void initialize(Context context) {
        if(selected>=0)return;
        String key=context.getApplicationContext().getSharedPreferences(PREFS,0).getString(KEY,KEYS[0]);
        customName=context.getApplicationContext().getSharedPreferences(PREFS,0).getString("custom_name",null);
        int index=0;for(int i=1;i<KEYS.length;i++)if(KEYS[i].equals(key)){index=i;break;}
        selectFamily(index);
        if(index==CUSTOM)loadCustom(context);
    }
    private static void loadCustom(Context context) {
        if(customLoadStarted)return;
        customLoadStarted=true;
        FontImport.loadSaved(context.getApplicationContext());
    }
    private static void selectFamily(int index) {
        selected=index;
        family=index==CUSTOM?customFace:index==0?null:Typeface.create(FAMILIES[index],Typeface.NORMAL);
        STYLES.clear();
        faceEpoch++;
    }
    static long faceEpoch(){return faceEpoch;}
    private static Typeface resolve(Typeface original) {
        if(selected==0||family==null)return original;
        Typeface source=original==null?Typeface.DEFAULT:original;
        int weight=Build.VERSION.SDK_INT>=28?Math.max(1,source.getWeight()):(source.isBold()?700:400);
        int key=weight*2+(source.isItalic()?1:0);
        Typeface result=STYLES.get(key);
        if(result==null) {
            result=Build.VERSION.SDK_INT>=28?Typeface.create(family,weight,source.isItalic()):
                Typeface.create(family,source.getStyle());
            STYLES.put(key,result);
        }
        return result;
    }
    static Typeface style(Context context, boolean bold) {
        initialize(context);
        return resolve(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }
    public static void apply(View root) {
        if(root==null||PREVIEW.equals(root.getTag()))return;
        initialize(root.getContext());
        if(root instanceof TextView)applyText((TextView)root);
        if(root instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)root;
            for(int i=0;i<group.getChildCount();i++)apply(group.getChildAt(i));
        }
    }
    private static void applyText(TextView view) {
        Typeface current=view.getTypeface();
        Face face=VIEWS.get(view);
        if(face==null){face=new Face(current);VIEWS.put(view,face);}
        else if(current!=face.lastApplied)face.original=current;
        Typeface next=resolve(face.original);
        if(current!=next)view.setTypeface(next);
        face.lastApplied=next;
    }
    public static String description(Context context) {
        initialize(context);
        return (vi(context)?"Phông chữ: ":"Font: ")+name(selected,vi(context));
    }
    private static void choose(Context context,int index) {
        selectionEpoch++;
        if(selected==index)return;
        context.getApplicationContext().getSharedPreferences(PREFS,0).edit().putString(KEY,KEYS[index]).apply();
        selectFamily(index);
        refreshViews();
    }
    private static void refreshViews() {
        for(TextView view:new ArrayList<>(VIEWS.keySet()))if(view!=null)applyText(view);
    }
    public static long epoch(){return selectionEpoch;}
    public static void onCustomReady(Context context,Typeface face,String displayName,boolean imported,long epoch) {
        initialize(context);
        customFace=face;customName=displayName;customLoadStarted=true;
        if(imported&&epoch==selectionEpoch) {
            selectionEpoch++;
            context.getApplicationContext().getSharedPreferences(PREFS,0).edit().putString(KEY,KEYS[CUSTOM]).apply();
            selectFamily(CUSTOM);refreshViews();
        } else if(selected==CUSTOM) {
            // One imported-font slot: replacing its file refreshes an already selected custom face.
            selectFamily(CUSTOM);refreshViews();
        }
        refreshOptions();
    }
    public static void onCustomFailure(boolean imported) {
        if(!imported&&customFace==null&&selected==CUSTOM){selectFamily(0);refreshViews();}
        refreshOptions();
    }
    private static void refreshOptions() {
        LinearLayout options=visibleOptions.get();
        if(options==null)return;
        boolean hasCustom=customFace!=null||FontImport.hasSaved(options.getContext());
        TextView custom=(TextView)options.getChildAt(CUSTOM);
        custom.setVisibility(hasCustom?View.VISIBLE:View.GONE);
        custom.setEnabled(customFace!=null);custom.setAlpha(customFace==null?.45f:1f);
        custom.setText(name(CUSTOM,vi(options.getContext()))+"   ·   Aa 12:34 · Tiếng Việt");
        custom.setContentDescription(name(CUSTOM,vi(options.getContext())));
        if(customFace!=null)custom.setTypeface(customFace);
        ArrayList<View> focusable=new ArrayList<>();
        for(int i=0;i<options.getChildCount();i++) {
            View row=options.getChildAt(i);
            row.setSelected(i<KEYS.length&&i==selected);
            if(row.getVisibility()==View.VISIBLE&&row.isEnabled())focusable.add(row);
        }
        for(int i=0;i<focusable.size();i++) {
            View row=focusable.get(i);
            row.setNextFocusUpId(focusable.get(Math.max(0,i-1)).getId());
            row.setNextFocusDownId(focusable.get(Math.min(focusable.size()-1,i+1)).getId());
            row.setNextFocusLeftId(row.getId());row.setNextFocusRightId(row.getId());
        }
    }
    public static void show(SettingsPopup popup,View anchor) {
        if(popup==null||popup.activity.isFinishing()||popup.activity.isDestroyed())return;
        Dialog existing=visibleDialog.get();if(existing!=null&&existing.isShowing())return;
        Context context=popup.activity;
        initialize(context);
        if(FontImport.hasSaved(context))loadCustom(context);
        float dp=popup.dp;
        Dialog dialog=new Dialog(context);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=new LinearLayout(context);panel.setOrientation(LinearLayout.VERTICAL);
        int pad=Math.round(20*dp);panel.setPadding(pad,pad,pad,pad);
        panel.setBackground(ClearGlass.panel(24*dp,dp));panel.setClickable(true);
        TextView title=new TextView(context);title.setText(popup.vi?"Phông chữ":"Font");
        title.setTextColor(0xffffffff);title.setTextSize(18);title.setTypeface(Typeface.DEFAULT_BOLD);
        ClearGlass.shadow(title,dp);
        LinearLayout.LayoutParams titleLp=new LinearLayout.LayoutParams(-1,-2);titleLp.bottomMargin=Math.round(12*dp);
        panel.addView(title,titleLp);
        int width=Math.min(Math.round(420*dp),context.getResources().getDisplayMetrics().widthPixels-Math.round(104*dp));
        LinearLayout options=new LinearLayout(context);options.setOrientation(LinearLayout.VERTICAL);
        panel.addView(options,new LinearLayout.LayoutParams(-2,-2));
        TextView[] rows=new TextView[KEYS.length];
        for(int i=0;i<rows.length;i++) {
            final int index=i;
            TextView row=new TextView(context);rows[i]=row;row.setTag(PREVIEW);
            row.setId(View.generateViewId());row.setFocusable(true);row.setClickable(true);
            row.setText(name(i,popup.vi)+"   ·   Aa 12:34 · Tiếng Việt");row.setContentDescription(name(i,popup.vi));
            row.setTextSize(16);row.setTextColor(ClearGlass.textColors());
            row.setTypeface(i==CUSTOM?customFace:i==0?Typeface.DEFAULT:Typeface.create(FAMILIES[i],Typeface.NORMAL));
            row.setSingleLine(true);row.setHorizontallyScrolling(false);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(Math.round(14*dp),0,Math.round(14*dp),0);
            row.setBackground(new ClearGlass.Plate(12*dp,dp,-1));row.setSelected(i==selected);
            row.setAccessibilityDelegate(new View.AccessibilityDelegate(){
                @Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){
                    super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName("android.widget.RadioButton");
                    info.setCheckable(true);info.setChecked(host.isSelected());
                }
            });
            row.setOnClickListener(v->{choose(context,index);if(anchor!=null)anchor.setContentDescription(description(context));PopupOptimizer.dismissAnimated(dialog,false);});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(width,Math.round(48*dp));
            if(i>0)lp.topMargin=Math.round(6*dp);options.addView(row,lp);
        }
        TextView importButton=new TextView(context);
        importButton.setId(View.generateViewId());importButton.setFocusable(true);importButton.setClickable(true);
        importButton.setText(popup.vi?"+  Nhập font TTF/OTF từ file":"+  Import TTF/OTF file");
        importButton.setTextSize(16);importButton.setTextColor(0xffffffff);
        importButton.setGravity(Gravity.CENTER_VERTICAL);importButton.setPadding(Math.round(14*dp),0,Math.round(14*dp),0);
        importButton.setSingleLine(true);importButton.setHorizontallyScrolling(false);importButton.setEllipsize(android.text.TextUtils.TruncateAt.END);
        importButton.setBackground(new ClearGlass.Plate(12*dp,dp,-1));
        importButton.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            @Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){
                super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName("android.widget.Button");
            }
        });
        importButton.setOnClickListener(v->{PopupOptimizer.dismissImmediate(dialog,false);FontImport.launch(popup.activity);});
        LinearLayout.LayoutParams importLp=new LinearLayout.LayoutParams(width,Math.round(48*dp));
        importLp.topMargin=Math.round(6*dp);options.addView(importButton,importLp);
        visibleOptions=new WeakReference<>(options);visibleDialog=new WeakReference<>(dialog);
        refreshOptions();
        Backdrop.wrap(popup.activity,dialog,panel,true,0);
        View content=dialog.findViewById(android.R.id.content);
        if(content!=null)content.setOnClickListener(v->{CrystalSoundEngine.playClose(context);PopupOptimizer.dismissAnimated(dialog,true);});
        dialog.setOnDismissListener(d->{
            if(visibleDialog.get()==dialog){visibleDialog=new WeakReference<>(null);visibleOptions=new WeakReference<>(null);}
            if(anchor!=null&&anchor.isAttachedToWindow())anchor.requestFocus();
        });
        dialog.show();(rows[selected].isEnabled()?rows[selected]:rows[0]).requestFocus();
    }
}
