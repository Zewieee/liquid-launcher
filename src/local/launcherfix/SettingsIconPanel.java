package local.launcherfix;

import android.graphics.*;
import android.graphics.drawable.*;
import android.text.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import com.gathin.tvhome.ControlCenter;
import com.gathin.tvhome.SettingsPopup;

/** Reuses the native controls and callbacks; only their presentation changes. */
public final class SettingsIconPanel {
    private static final int WALLPAPER=0, ANIMATION=1, EFFECTS=2, TRANSITION=3,
            AMBIENT=4, ICONS=5, TINT=6, DOCK=7, SOUND=8, TV=9, COLUMNS=10, OPACITY=11;
    private final SettingsPopup popup;
    private final float dp;

    private SettingsIconPanel(SettingsPopup popup) { this.popup=popup; dp=popup.dp; }
    private int px(float value) { return Math.round(value*dp); }
    private String t(String vi,String en) { return popup.vi?vi:en; }

    public static void apply(SettingsPopup popup,LinearLayout panel) {
        new SettingsIconPanel(popup).build(panel);
    }

    private void build(LinearLayout panel) {
        LinearLayout right=(LinearLayout)panel.findViewWithTag(WallpaperIcons.TAG).getParent();
        TextView[] actions={popup.wallpaperRow,popup.animationsRow,popup.advancedRow,
            panel.findViewWithTag(CrystalSoundEngine.TAG_TRANSITION_SETTING),
            panel.findViewWithTag(CrystalSoundEngine.TAG_AMBIENT_SETTING),
            (TextView)right.getChildAt(0),panel.findViewWithTag(WallpaperIcons.TAG),
            (TextView)right.getChildAt(1),panel.findViewWithTag(CrystalSoundEngine.TAG_SETTING),
            (TextView)right.getChildAt(2)};
        for(TextView view:popup.columnChips) detach(view);
        for(TextView view:popup.dockChips) detach(view);
        for(TextView view:actions) detach(view);
        panel.removeAllViews();
        panel.setPadding(px(20),px(16),px(20),px(20));
        int width=Math.min(px(640),popup.activity.getResources().getDisplayMetrics().widthPixels-px(64));
        int contentWidth=width-px(40);

        ImageView heading=new ImageView(popup.activity);
        heading.setImageDrawable(new ControlCenter.Glyph(ControlCenter.Glyph.GEAR,dp,26));
        heading.setContentDescription(t("Cài đặt Launcher","Launcher settings"));
        if(android.os.Build.VERSION.SDK_INT>=28)heading.setAccessibilityHeading(true);
        heading.setScaleType(ImageView.ScaleType.CENTER);
        FrameLayout header=new FrameLayout(popup.activity);
        header.addView(heading,new FrameLayout.LayoutParams(-1,-1));
        TextView fontButton=new TextView(popup.activity);
        fontButton.setText("Aa");fontButton.setTextSize(20);fontButton.setTextColor(ReferenceControlPanel.TEXT);
        fontButton.setTypeface(Typeface.DEFAULT_BOLD);fontButton.setGravity(Gravity.CENTER);
        fontButton.setSingleLine(true);fontButton.setHorizontallyScrolling(false);
        fontButton.setFocusable(true);fontButton.setClickable(true);fontButton.setId(View.generateViewId());
        fontButton.setContentDescription(FontChoice.description(popup.activity));fontButton.setBackground(background());
        fontButton.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            @Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){
                super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName("android.widget.Button");
            }
        });
        fontButton.setOnClickListener(v->FontChoice.show(popup,fontButton));
        header.addView(fontButton,new FrameLayout.LayoutParams(px(48),px(48),Gravity.END|Gravity.CENTER_VERTICAL));
        panel.addView(header,new LinearLayout.LayoutParams(contentWidth,px(48)));

        LinearLayout choices=new LinearLayout(popup.activity);
        LinearLayout.LayoutParams choicesLp=new LinearLayout.LayoutParams(contentWidth,px(64));
        choicesLp.topMargin=px(12);
        panel.addView(choices,choicesLp);
        optionGroup(choices,popup.columnChips,COLUMNS,0);
        optionGroup(choices,popup.dockChips,OPACITY,12);

        for(int row=0;row<2;row++) {
            LinearLayout line=new LinearLayout(popup.activity);
            LinearLayout.LayoutParams lineLp=new LinearLayout.LayoutParams(contentWidth,px(80));
            lineLp.topMargin=px(row==0?20:8);
            panel.addView(line,lineLp);
            for(int col=0;col<5;col++) {
                int index=row*5+col;
                skin(actions[index],index,0);
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);
                if(col>0)lp.setMarginStart(px(8));
                line.addView(actions[index],lp);
            }
        }

        // Explicit rows keep focus predictable, including a disabled advanced-effects tile.
        View[] top=new View[8];
        System.arraycopy(popup.columnChips,0,top,0,4);
        System.arraycopy(popup.dockChips,0,top,4,4);
        View[][] rows={{fontButton},top,java.util.Arrays.copyOfRange(actions,0,5),java.util.Arrays.copyOfRange(actions,5,10)};
        for(int row=0;row<rows.length;row++)for(int col=0;col<rows[row].length;col++) {
            final int r=row,c=col;
            rows[row][col].setOnKeyListener((view,key,event)-> {
                int nextRow=r,nextCol=c;
                if(key==KeyEvent.KEYCODE_DPAD_LEFT)nextCol--;
                else if(key==KeyEvent.KEYCODE_DPAD_RIGHT)nextCol++;
                else if(key==KeyEvent.KEYCODE_DPAD_UP)nextRow--;
                else if(key==KeyEvent.KEYCODE_DPAD_DOWN)nextRow++;
                else return false;
                if(event.getAction()!=KeyEvent.ACTION_DOWN)return true;
                if(nextRow<0||nextRow>=rows.length)return true;
                if(nextRow!=r)nextCol=Math.min(rows[nextRow].length-1,
                    (int)((c+.5f)*rows[nextRow].length/rows[r].length));
                int direction=key==KeyEvent.KEYCODE_DPAD_LEFT?-1:1;
                while(nextCol>=0&&nextCol<rows[nextRow].length) {
                    View target=rows[nextRow][nextCol];
                    if(target.isEnabled()&&target.isFocusable()){target.requestFocus();return true;}
                    nextCol+=direction;
                }
                return true;
            });
        }
    }

    private static void detach(View view) { ((android.view.ViewGroup)view.getParent()).removeView(view); }

    private void optionGroup(LinearLayout parent,TextView[] chips,int kind,int margin) {
        LinearLayout group=new LinearLayout(popup.activity);
        group.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams groupLp=new LinearLayout.LayoutParams(0,-1,1);
        groupLp.setMarginStart(px(margin));
        parent.addView(group,groupLp);
        ImageView icon=new ImageView(popup.activity);
        icon.setImageDrawable(new ControlCenter.Glyph(kind==COLUMNS?ControlCenter.Glyph.ICON:ControlCenter.Glyph.DOCK,dp,24));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        group.addView(icon,new LinearLayout.LayoutParams(px(28),px(32)));
        for(int i=0;i<chips.length;i++) {
            TextView chip=chips[i];
            chip.setContentDescription(kind==COLUMNS?
                t("Số ứng dụng mỗi hàng: ","Apps per row: ")+(i+4):
                t("Độ đậm dock: ","Dock opacity: ")+SettingsPopup.DOCK_OPACITIES[i]+"%");
            skin(chip,kind,kind==COLUMNS?i+4:SettingsPopup.DOCK_OPACITIES[i]);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,px(56),1);
            lp.setMarginStart(px(6));group.addView(chip,lp);
        }
    }

    private GradientDrawable surface(int fill) {
        GradientDrawable shape=new GradientDrawable();shape.setColor(fill);
        shape.setCornerRadius(px(14));return shape;
    }
    private StateListDrawable background() {
        StateListDrawable states=new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused,android.R.attr.state_selected},surface(0xcc536c87));
        states.addState(new int[]{android.R.attr.state_focused},surface(0xb83e5067));
        states.addState(new int[]{android.R.attr.state_pressed},surface(0xcc536c87));
        states.addState(new int[]{android.R.attr.state_selected},surface(0x99536c87));
        states.addState(new int[]{},surface(0x30ffffff));return states;
    }

    private boolean on(int kind) {
        if(kind==ANIMATION)return popup.animations;
        if(kind==EFFECTS)return popup.advanced;
        return kind==TINT&&WallpaperIcons.enabled(popup.activity);
    }

    private void skin(TextView view,int kind,int value) {
        if(view.getId()==View.NO_ID)view.setId(View.generateViewId());
        view.setPadding(0,0,0,0);view.setMinimumWidth(0);view.setMinimumHeight(0);
        // Inherited singleLine scrolls centered text and its foreground far outside the tile.
        view.setHorizontallyScrolling(false);
        view.scrollTo(0,0);
        view.setGravity(Gravity.CENTER);view.setTextColor(Color.TRANSPARENT);
        view.setShadowLayer(0,0,0,Color.TRANSPARENT);
        view.setCompoundDrawablesRelative(null,null,null,null);
        view.setBackground(background());
        Glyph glyph=new Glyph(kind,value);
        view.setForeground(glyph);
        Runnable spoken=()-> {
            if(kind<COLUMNS)view.setContentDescription(view.getText());
            glyph.invalidateSelf();
        };
        spoken.run();
        view.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){spoken.run();}
            public void afterTextChanged(Editable text){}
        });
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host,info);
                info.setText(null);
                boolean toggle=kind==ANIMATION||kind==EFFECTS||kind==TINT;
                info.setClassName(toggle?"android.widget.Switch":kind>=COLUMNS?"android.widget.RadioButton":"android.widget.Button");
                info.setCheckable(toggle||kind>=COLUMNS);
                info.setChecked(toggle?on(kind):host.isSelected());
            }
        });
    }

    private final class Glyph extends Drawable {
        final int kind,value;
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path=new Path();
        final RectF box=new RectF();
        final Drawable stock;
        Glyph(int kind,int value) {
            this.kind=kind;this.value=value;
            int resource=kind==WALLPAPER?ControlCenter.Glyph.WALLPAPER:
                kind==ICONS?ControlCenter.Glyph.ICON:kind==DOCK?ControlCenter.Glyph.DOCK:
                kind==SOUND?ControlCenter.Glyph.SOUND:-1;
            stock=resource<0?null:new ControlCenter.Glyph(resource,dp,32);
            paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        }
        public void draw(Canvas canvas) {
            Rect bounds=getBounds();
            boolean toggle=kind==ANIMATION||kind==EFFECTS||kind==TINT;
            float size=Math.min(px(kind>=COLUMNS?34:32),Math.min(bounds.width()-px(8),bounds.height()-px(12)));
            int save=canvas.save();
            canvas.translate(bounds.exactCenterX()-size/2,bounds.exactCenterY()-size/2-(toggle?px(7):0));
            canvas.scale(size/32,size/32);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f);paint.setColor(ReferenceControlPanel.TEXT);
            if(stock!=null){stock.setBounds(0,0,32,32);stock.draw(canvas);}
            else if(kind==COLUMNS) {
                float step=28f/value;
                paint.setStyle(Paint.Style.FILL);
                for(int row=0;row<2;row++)for(int col=0;col<value;col++) {
                    box.set(2+col*step,9+row*9,2+col*step+step-1.6f,15+row*9);
                    canvas.drawRoundRect(box,1,1,paint);
                }
            } else if(kind==OPACITY) {
                paint.setStyle(Paint.Style.FILL);
                for(int row=0;row<3;row++)for(int col=0;col<4;col++) {
                    paint.setColor((row+col)%2==0?0xff344454:0xff8090a0);
                    canvas.drawRect(4+col*6,7+row*6,10+col*6,13+row*6,paint);
                }
                paint.setColor(Color.argb(Math.round(255*value/100f),245,249,255));
                box.set(4,7,28,25);canvas.drawRect(box,paint);
                paint.setStyle(Paint.Style.STROKE);paint.setColor(ReferenceControlPanel.TEXT);
                canvas.drawRoundRect(box,3,3,paint);
            } else if(kind==ANIMATION) {
                canvas.drawLine(2,10,10,10,paint);canvas.drawLine(4,16,9,16,paint);canvas.drawLine(2,22,10,22,paint);
                path.reset();path.moveTo(14,6);path.lineTo(29,16);path.lineTo(14,26);path.close();canvas.drawPath(path,paint);
            } else if(kind==EFFECTS) {
                star(canvas,13,17,10);star(canvas,26,6,4);star(canvas,26,26,3);
            } else if(kind==TRANSITION) {
                box.set(2,6,15,26);canvas.drawRoundRect(box,3,3,paint);
                box.set(20,3,30,23);canvas.drawRoundRect(box,3,3,paint);
                canvas.drawLine(10,16,24,16,paint);canvas.drawLine(20,12,24,16,paint);canvas.drawLine(20,20,24,16,paint);
            } else if(kind==AMBIENT) {
                path.reset();path.moveTo(21,3);path.cubicTo(6,1,0,20,13,28);
                path.cubicTo(21,32,29,25,29,20);path.cubicTo(16,24,11,10,21,3);canvas.drawPath(path,paint);
                star(canvas,26,8,3);
            } else if(kind==TINT) {
                path.reset();path.moveTo(16,3);path.cubicTo(12,9,5,15,5,21);
                path.cubicTo(5,34,27,34,27,21);path.cubicTo(27,15,20,9,16,3);canvas.drawPath(path,paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xffa9cbea);canvas.drawCircle(12,22,3,paint);
                paint.setColor(0xffd7bfe7);canvas.drawCircle(20,22,3,paint);
            } else if(kind==TV) {
                box.set(2,3,30,23);canvas.drawRoundRect(box,3,3,paint);
                canvas.drawLine(11,29,21,29,paint);canvas.drawLine(16,24,16,29,paint);
                canvas.drawLine(7,10,25,10,paint);canvas.drawLine(7,17,25,17,paint);
                paint.setStyle(Paint.Style.FILL);canvas.drawCircle(12,10,3,paint);canvas.drawCircle(21,17,3,paint);
            }
            canvas.restoreToCount(save);
            if(toggle) {
                float x=bounds.exactCenterX(),y=bounds.bottom-px(16);
                paint.setStyle(Paint.Style.FILL);paint.setColor(on(kind)?0xffa9cbea:0xff627080);
                box.set(x-px(12),y-px(5),x+px(12),y+px(5));canvas.drawRoundRect(box,px(5),px(5),paint);
                paint.setColor(on(kind)?0xff15273a:0xfff2f1f6);
                canvas.drawCircle(x+px(on(kind)?7:-7),y,px(3),paint);
            }
        }
        private void star(Canvas c,float x,float y,float r) {
            path.reset();path.moveTo(x,y-r);path.lineTo(x+r*.3f,y-r*.3f);path.lineTo(x+r,y);
            path.lineTo(x+r*.3f,y+r*.3f);path.lineTo(x,y+r);path.lineTo(x-r*.3f,y+r*.3f);
            path.lineTo(x-r,y);path.lineTo(x-r*.3f,y-r*.3f);path.close();c.drawPath(path,paint);
        }
        public int getOpacity(){return PixelFormat.TRANSLUCENT;}
        public void setAlpha(int alpha){}
        public void setColorFilter(ColorFilter filter){}
    }
}
