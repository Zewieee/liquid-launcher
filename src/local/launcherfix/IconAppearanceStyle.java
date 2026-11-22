package local.launcherfix;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.StateListDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.gathin.tvhome.ClearGlass;

/** Restyle existing controls so icon selection and persistence stay native. */
public final class IconAppearanceStyle {
    private IconAppearanceStyle() {}
    private static int dp(float density, float value) { return Math.round(density * value); }

    public static void overlay(Activity activity) {
        activity.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    }

    private static android.graphics.drawable.GradientDrawable surface(float d,float radius,int color) {
        android.graphics.drawable.GradientDrawable shape=new android.graphics.drawable.GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(d,radius));
        return shape;
    }

    private static StateListDrawable choiceStates(float d) {
        StateListDrawable states=new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused},surface(d,14,0x30ffffff));
        states.addState(new int[]{android.R.attr.state_pressed},surface(d,14,0x40ffffff));
        states.addState(new int[0],surface(d,14,0));
        return states;
    }

    public static StateListDrawable choices(Activity activity) {
        return choiceStates(activity.getResources().getDisplayMetrics().density);
    }

    public static void apply(Activity activity, LinearLayout panel) {
        if (panel == null || "icon_appearance_panel".equals(panel.getTag())) return;
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        float d = metrics.density;
        panel.setTag("icon_appearance_panel");
        panel.setPadding(dp(d,14),dp(d,12),dp(d,14),dp(d,12));
        panel.setBackground(surface(d,20,0));
        panel.setElevation(dp(d,12));
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        style(panel,d);
        if (panel.getChildCount() >= 2) {
            View header = panel.getChildAt(0);
            if (header instanceof LinearLayout) {
                LinearLayout row = (LinearLayout)header;
                for(int i=row.getChildCount()-1;i>=0;i--)
                    if(row.getChildAt(i).isFocusable() || row.getChildAt(i).hasOnClickListeners())
                        row.removeViewAt(i);
                for (int i=0;i<row.getChildCount();i++) {
                    View item=row.getChildAt(i);
                    if (item instanceof TextView && !item.isFocusable()) {
                        ((TextView)item).setTextSize(18);
                        ((TextView)item).setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
                    }
                }
            }
            View subtitle=panel.getChildAt(1);
            if (subtitle instanceof TextView) {
                ((TextView)subtitle).setTextSize(12);
                ((TextView)subtitle).setTextColor(0xd2ffffff);
                if(activity.getString(0x7f10000c).contentEquals(((TextView)subtitle).getText()))
                    subtitle.setVisibility(View.GONE);
            }
        }
        // Options already contain a preview followed by the complete native choice list.
        if (panel.getChildCount()==3 && panel.getChildAt(2) instanceof ScrollView) {
            ScrollView scroll=(ScrollView)panel.getChildAt(2);
            if (scroll.getChildCount()==1 && scroll.getChildAt(0) instanceof LinearLayout) {
                LinearLayout choices=(LinearLayout)scroll.getChildAt(0);
                if (choices.getChildCount()>0 && choices.getChildAt(0) instanceof LinearLayout) {
                    LinearLayout preview=(LinearLayout)choices.getChildAt(0);
                    choices.removeView(preview);
                    panel.removeView(scroll);
                    preview.setOrientation(LinearLayout.VERTICAL);
                    preview.setGravity(Gravity.CENTER);
                    preview.setPadding(dp(d,12),dp(d,18),dp(d,12),dp(d,18));
                    preview.setBackground(null);
                    for(int i=0;i<preview.getChildCount();i++) {
                        View item=preview.getChildAt(i);
                        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,
                            item instanceof ImageView?dp(d,100):-2);
                        if(item instanceof TextView) {
                            item.setVisibility(View.GONE);
                        }
                        item.setLayoutParams(lp);
                    }
                    LinearLayout body=new LinearLayout(activity);
                    body.setTag("icon_appearance_body");
                    body.setOrientation(LinearLayout.HORIZONTAL);
                    body.addView(preview,new LinearLayout.LayoutParams(dp(d,130),-1));
                    LinearLayout.LayoutParams options=new LinearLayout.LayoutParams(0,-1,1);
                    options.setMarginStart(dp(d,12));
                    body.addView(scroll,options);
                    panel.addView(body,new LinearLayout.LayoutParams(-1,0,1));
                }
            }
        }
        ViewGroup parent=(ViewGroup)panel.getParent();
        if(parent!=null)parent.removeView(panel);
        FrameLayout stage=new FrameLayout(activity);
        stage.setBackgroundColor(0x330a0f16);
        stage.setClipChildren(false);
        com.gathin.tvhome.ControlCenter center=ControlPanelHelper.getActiveCenter();
        Activity source=TileLoader.currentActivity();
        if(source==null && center!=null)source=center.activity;
        if(source!=null && source!=activity && !source.isDestroyed()) {
            ImageView backdrop=new ImageView(activity);
            backdrop.setTag("icon_appearance_backdrop");
            backdrop.setAlpha(0f);
            backdrop.setColorFilter(0x330a0f16,android.graphics.PorterDuff.Mode.SRC_ATOP);
            backdrop.setScaleType(ImageView.ScaleType.FIT_XY);
            backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            stage.addView(backdrop,new FrameLayout.LayoutParams(-1,-1));
            com.gathin.tvhome.Backdrop.capture(source,backdrop,metrics.widthPixels,metrics.heightPixels);
        }
        int width=Math.max(1,Math.min(dp(d,560),metrics.widthPixels-dp(d,64)));
        int height=Math.max(1,Math.min(dp(d,320),metrics.heightPixels-dp(d,48)));
        stage.addView(panel,new FrameLayout.LayoutParams(width,height,Gravity.CENTER));
        FontChoice.apply(stage);
        activity.setContentView(stage);
    }

    private static void style(View view,float d) {
        if(view.isFocusable() && view.getBackground() instanceof StateListDrawable)
            view.setBackground(choiceStates(d));
        if(view instanceof TextView) {
            TextView text=(TextView)view;
            if(view.isFocusable()) {
                text.setTextColor(ClearGlass.textColors());
                text.setTextSize(13);
                text.setMinimumHeight(dp(d,44));
                ViewGroup.LayoutParams lp=view.getLayoutParams();
                if(lp!=null && lp.height>0 && lp.height<dp(d,44))lp.height=dp(d,44);
            }
            ClearGlass.shadow(text,d);
        }
        if(view instanceof ScrollView) {
            ((ScrollView)view).setVerticalScrollBarEnabled(false);
            ((ScrollView)view).setClipToPadding(false);
        }
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++)style(group.getChildAt(i),d);
        }
    }
}
