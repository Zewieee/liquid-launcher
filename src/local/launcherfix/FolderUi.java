package local.launcherfix;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;
import com.gathin.tvhome.Backdrop;
import com.gathin.tvhome.ControlCenter;
import com.gathin.tvhome.MainActivity;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Folder pages share one dialog, one catalog load and an explicit membership draft. */
public final class FolderUi {
    private static final int VIEW=0, MANAGE=1, CHOOSE=2, TITLE=3, EDIT=4, DELETE=5;
    private static final Handler UI=new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor WORK=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,
        new LinkedBlockingQueue<Runnable>(), task -> new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            task.run();
        },"launcher-folders"));
    private static WeakReference<Session> active=new WeakReference<>(null);
    private static long pendingAction;
    static { WORK.allowCoreThreadTimeOut(true); }
    private FolderUi() {}

    public static void show(MainActivity owner,String id) { open(owner,id,VIEW,null,null); }
    public static void manage(MainActivity owner,String id) { open(owner,id,MANAGE,null,null); }
    private static void open(MainActivity owner,String id,int page,String component,String pkg) {
        if(owner==null||owner.isFinishing()||owner.isDestroyed())return;
        Session previous=active.get();
        if(previous!=null&&!previous.closed)return;
        try {
            FolderStore.Snapshot snapshot=FolderStore.read(owner);
            if(id!=null&&snapshot.find(id)==null)return;
            Session session=new Session(owner,snapshot,id,page,component,pkg);
            active=new WeakReference<>(session);session.show();
        } catch(IOException error) {
            Toast.makeText(owner,isVi(owner)?"Không đọc được thư mục":"Could not read folders",Toast.LENGTH_LONG).show();
        }
    }
    public static boolean ownsDialog(Dialog dialog) {
        Session session=active.get();
        return session!=null&&session.dialog==dialog;
    }
    public static boolean dismiss(Activity owner) {
        pendingAction++;
        Session session=active.get();
        if(session==null||session.owner!=owner)return false;
        session.lifecycleClose=true;session.close();PopupOptimizer.dismissImmediate(session.dialog,false);return true;
    }
    public static boolean dismissFromBack(Activity owner) {
        Session session=active.get();
        if(session==null||session.owner!=owner||session.closed)return false;
        session.back();
        return true;
    }
    public static void addToMenu(MainActivity owner,LinearLayout menu,PopupWindow popup,String component,String pkg) {
        if(owner==null||pkg==null||component==null||pkg.equals(owner.getPackageName()))return;
        int position=owner.adapter.positionOf(component);
        if(position<0||owner.adapter.items.get(position).kind!=0)return;
        float density=owner.getResources().getDisplayMetrics().density;
        TextView action=new TextView(owner);
        action.setText(isVi(owner)?"Chuyển vào thư mục":"Move to folder");
        action.setTextSize(14);action.setTextColor(Color.WHITE);action.setSingleLine(true);
        action.setEllipsize(TextUtils.TruncateAt.END);action.setGravity(Gravity.CENTER_VERTICAL);
        action.setPadding(Math.round(14*density),0,Math.round(14*density),0);
        action.setFocusable(true);action.setClickable(true);
        action.setBackground(WallpaperTheme.states(density,WallpaperTheme.current(ControlPanelHelper.getActiveCenter())));
        FontChoice.apply(action);
        action.setOnClickListener(view -> {
            PopupOptimizer.dismissPopupImmediate(popup);postForHome(owner,() -> open(owner,null,CHOOSE,component,pkg));
        });
        menu.addView(action,new LinearLayout.LayoutParams(-1,Math.round(42*density)));
    }
    private static boolean isVi(Context context) {
        return "vi".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage());
    }
    private static void postForHome(MainActivity owner,Runnable action) {
        final long token=++pendingAction,interaction=TileLoader.interactionSerial(owner);
        owner.root.post(new Runnable() {
            int attempts;
            @Override public void run() {
                if(token!=pendingAction||owner.isFinishing()||owner.isDestroyed()||!owner.started
                        ||interaction!=TileLoader.interactionSerial(owner))return;
                if(owner.hasWindowFocus())action.run();
                else if(++attempts<6)owner.root.postDelayed(this,40);
            }
        });
    }

    private static final class Row {
        final String label,pkg;final Drawable icon;final Runnable action;final boolean check;
        Row(String label,Drawable icon,String pkg,boolean check,Runnable action) {
            this.label=label;this.icon=icon;this.pkg=pkg;this.check=check;this.action=action;
        }
    }
    private static final class Session {
        final MainActivity owner;final Dialog dialog;final LinearLayout panel;
        final boolean vi;final float density;final WallpaperTheme.Tone tone;
        final WeakReference<View> origin;final String seedComponent,seedPackage;
        final ArrayDeque<Integer> history=new ArrayDeque<>();
        final LinkedHashSet<String> draft=new LinkedHashSet<>();
        final ArrayList<Row> rows=new ArrayList<>();
        FolderStore.Snapshot snapshot;String folderId,returnKey;
        int page,confirmKey=KeyEvent.KEYCODE_UNKNOWN;long confirmTime;
        boolean closed,lifecycleClose,launched,loaded,loadFailed,rename;
        List<AppSearch.App> apps=Collections.emptyList();Future<?> loading;
        ListView list;TextView status;EditText input;View topFocus,bottomFocus;Rows adapter;

        Session(MainActivity owner,FolderStore.Snapshot snapshot,String id,int page,String component,String pkg) {
            this.owner=owner;this.snapshot=snapshot;folderId=id;this.page=page;
            seedComponent=component;seedPackage=pkg;returnKey=id==null?component:FolderStore.componentKey(owner,id);
            vi=isVi(owner);density=owner.getResources().getDisplayMetrics().density;
            ControlCenter center=ControlPanelHelper.getActiveCenter();
            tone=WallpaperTheme.current(center!=null&&center.activity==owner?center:null);
            origin=new WeakReference<>(owner.getCurrentFocus());
            dialog=new Dialog(owner) {
                @Override public boolean dispatchKeyEvent(KeyEvent event) {
                    if(event.getAction()==KeyEvent.ACTION_DOWN)owner.onUserInteraction();
                    return super.dispatchKeyEvent(event);
                }
                @Override public void onBackPressed() { back(); }
            };
            dialog.setOwnerActivity(owner);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            panel=new LinearLayout(owner) {
                @Override protected void onMeasure(int width,int height) {
                    int available=View.MeasureSpec.getSize(height),target=px(370);
                    if(View.MeasureSpec.getMode(height)!=View.MeasureSpec.UNSPECIFIED)target=Math.min(target,Math.max(0,available-px(24)));
                    super.onMeasure(View.MeasureSpec.makeMeasureSpec(Math.min(px(500),View.MeasureSpec.getSize(width)),View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(target,View.MeasureSpec.EXACTLY));
                }
            };
            panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(px(20),px(18),px(20),px(16));
            panel.setBackground(WallpaperTheme.glass(density,tone));
            final int panelRadius=px(22);
            panel.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View view,android.graphics.Outline outline) {
                    outline.setRoundRect(0,0,view.getWidth(),view.getHeight(),panelRadius);
                }
            });
            panel.setClipToOutline(true);
            Backdrop.wrap(owner,dialog,panel,true,0);
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            dialog.setOnDismissListener(ignored -> close());
        }
        int px(float value) { return Math.round(value*density); }
        String t(String viText,String enText) { return vi?viText:enText; }
        FolderStore.Folder folder() { return snapshot.find(folderId); }
        TextView text(String value,int size) {
            TextView view=new TextView(owner);view.setText(value);view.setTextColor(Color.WHITE);
            view.setTextSize(size);view.setSingleLine(true);view.setEllipsize(TextUtils.TruncateAt.END);
            view.setGravity(Gravity.CENTER_VERTICAL);return view;
        }
        TextView button(String label,Runnable action) {
            TextView view=text(label,13);view.setId(View.generateViewId());view.setGravity(Gravity.CENTER);
            view.setPadding(px(10),0,px(10),0);view.setFocusable(true);view.setClickable(true);
            view.setBackground(WallpaperTheme.states(density,tone));view.setOnClickListener(v -> action.run());return view;
        }
        LinearLayout buttons() {
            LinearLayout line=new LinearLayout(owner);line.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,px(40));params.topMargin=px(8);
            panel.addView(line,params);return line;
        }
        void addButton(LinearLayout line,TextView button) {
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-1,1);
            if(line.getChildCount()>0)params.setMarginStart(px(8));line.addView(button,params);
        }
        void heading(String title) {
            panel.addView(text(title,18),new LinearLayout.LayoutParams(-1,px(30)));
            status=text("",11);status.setTextColor(0xffcbd2da);
        }
        void addStatus() { panel.addView(status,new LinearLayout.LayoutParams(-1,px(26))); }
        void show() {
            render();dialog.show();dialog.getWindow().setLayout(-1,-1);focusInitial();
            final Context context=owner.getApplicationContext();final WeakReference<Session> ref=new WeakReference<>(this);
            final long interaction=TileLoader.interactionSerial(owner);final int initialPage=page;
            loading=WORK.submit(() -> {
                List<AppSearch.App> data=Collections.emptyList();boolean failed=false;
                try { data=AppSearch.load(context); } catch(RuntimeException|OutOfMemoryError error) { failed=true; }
                final List<AppSearch.App> result=data;final boolean failure=failed;
                UI.post(() -> {
                    Session session=ref.get();
                    if(session==null||session.closed||session.owner.isFinishing()||session.owner.isDestroyed())return;
                    session.apps=result;session.loaded=true;session.loadFailed=failure;session.loading=null;
                    // A loading result must not replace text being typed on the name page.
                    if(session.page==VIEW||session.page==EDIT) {
                        if(interaction==TileLoader.interactionSerial(session.owner)&&initialPage==session.page){session.render();session.focusInitial();}
                        else session.refreshKeepingFocus();
                    }
                });
            });
        }
        void refreshKeepingFocus() {
            View focused=dialog.getCurrentFocus();boolean inList=list!=null&&list.hasFocus();
            int selected=inList?list.getSelectedItemPosition():0;
            String label=focused instanceof TextView?((TextView)focused).getText().toString():null;
            render();
            if(inList&&list!=null&&!rows.isEmpty()){list.requestFocus();list.setSelection(Math.max(0,Math.min(selected,rows.size()-1)));}
            else if(label==null||!focusText(panel,label))focusInitial();
        }
        boolean focusText(View view,String label) {
            if(view instanceof TextView&&view.isFocusable()&&view.isEnabled()&&label.contentEquals(((TextView)view).getText()))return view.requestFocus();
            if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(focusText(((ViewGroup)view).getChildAt(i),label))return true;
            return false;
        }
        void go(int next) {
            hideKeyboard();history.push(page);page=next;
            if(next==EDIT){draft.clear();draft.addAll(folder().packages);}
            render();focusInitial();animateSubpage(1f);
        }
        void back() {
            hideKeyboard();
            if(history.isEmpty()){PopupOptimizer.dismissAnimated(dialog,true);return;}
            page=history.pop();render();focusInitial();animateSubpage(-1f);
        }
        void animateSubpage(float dir) {
            if(!PopupOptimizer.motionEnabled(owner))return;
            for(int i=0;i<panel.getChildCount();i++){
                View ch=panel.getChildAt(i);
                ch.setAlpha(0.25f);ch.setTranslationX(dir*px(8));
                ch.animate().alpha(1f).translationX(0f).setDuration(165L).setInterpolator(PopupOptimizer.ENTRANCE_EASE).start();
            }
        }
        void render() {
            if(closed)return;
            panel.removeAllViews();rows.clear();list=null;input=null;topFocus=null;bottomFocus=null;confirmKey=KeyEvent.KEYCODE_UNKNOWN;
            FolderStore.Folder folder=folder();
            if(page!=CHOOSE&&page!=TITLE&&folder==null){dialog.dismiss();return;}
            if(page==TITLE){renderTitle();FontChoice.apply(panel);return;}
            heading(page==CHOOSE?t("Chuyển vào thư mục","Move to folder"):folder.name);
            if(page==VIEW) {
                LinearLayout line=buttons();TextView edit=button(t("Chọn ứng dụng","Edit apps"),() -> go(EDIT));
                addButton(line,edit);addButton(line,button(t("Tùy chọn","More"),() -> go(MANAGE)));topFocus=edit;
                for(AppSearch.App app:apps)if(folder.packages.contains(app.component.getPackageName()))
                    rows.add(new Row(app.label,app.icon,null,false,() -> launch(app)));
                status.setText(!loaded?t("Đang tải…","Loading…"):loadFailed?t("Không tải được ứng dụng. Hãy mở lại.","Could not load apps. Reopen to retry."):
                    rows.isEmpty()?(folder.packages.isEmpty()?t("Thư mục trống · Chọn ứng dụng để thêm","Empty folder · Choose Edit apps to add"):
                        t("Các ứng dụng trong nhóm chưa khả dụng","Apps in this folder are unavailable")):
                    rows.size()+(vi?" ứng dụng":rows.size()==1?" app":" apps"));
                addStatus();addList();
            } else if(page==CHOOSE) {
                LinearLayout line=buttons();TextView create=button(t("Tạo thư mục","New folder"),() -> {rename=false;go(TITLE);});
                addButton(line,create);topFocus=create;status.setText(t("Chọn nhóm cho ứng dụng","Choose a folder for this app"));addStatus();
                for(FolderStore.Folder item:snapshot.folders)rows.add(new Row(item.name,null,null,false,() -> moveSeed(item.id)));
                addList();
            } else if(page==MANAGE) {
                addStatus();
                rows.add(new Row(t("Mở thư mục","Open folder"),null,null,false,() -> go(VIEW)));
                rows.add(new Row(t("Chọn ứng dụng","Edit apps"),null,null,false,() -> go(EDIT)));
                rows.add(new Row(t("Đổi tên","Rename"),null,null,false,() -> {rename=true;go(TITLE);}));
                rows.add(new Row(t("Di chuyển","Move"),null,null,false,() -> {
                    String id=folderId;lifecycleClose=true;close();dialog.dismiss();
                    postForHome(owner,() -> {if(!FolderTiles.move(owner,id))Toast.makeText(owner,t("Hãy thử lại khi ứng dụng tải xong","Try again when apps have finished loading"),Toast.LENGTH_SHORT).show();});
                }));
                rows.add(new Row(t("Xóa thư mục","Delete folder"),null,null,false,() -> go(DELETE)));
                addList();
            } else if(page==EDIT) {
                status.setText(!loaded?t("Đang tải…","Loading…"):loadFailed?t("Không tải được ứng dụng. Hãy mở lại.","Could not load apps. Reopen to retry."):selectedLabel());
                addStatus();
                java.util.Map<String,String> membership=snapshot.membership();
                for(AppSearch.App app:apps) {
                    String pkg=app.component.getPackageName();
                    FolderStore.Folder group=snapshot.find(membership.get(pkg));
                    String label=group!=null&&!group.id.equals(folderId)?app.label+" · "+group.name:app.label;
                    rows.add(new Row(label,app.icon,pkg,true,() -> {
                        int position=list.getSelectedItemPosition();
                        View selected=list.getSelectedView();
                        int top=selected==null?0:selected.getTop()-list.getPaddingTop();
                        if(!draft.remove(pkg))draft.add(pkg);
                        adapter.notifyDataSetChanged();status.setText(selectedLabel());
                        list.setSelectionFromTop(Math.max(0,position),top);
                    }));
                }
                addList();LinearLayout line=buttons();TextView save=button(t("Lưu","Save"),() -> saveMembers());
                save.setEnabled(loaded&&!loadFailed);save.setAlpha(save.isEnabled()?1f:.45f);
                TextView cancel=button(t("Hủy","Cancel"),() -> back());
                addButton(line,save);addButton(line,cancel);bottomFocus=save.isEnabled()?save:cancel;wireFooter(line);
            } else if(page==DELETE) {
                TextView description=text(t("Ứng dụng sẽ trở lại màn hình chính.","Apps will return to the home screen."),14);
                description.setSingleLine(false);panel.addView(description,new LinearLayout.LayoutParams(-1,0,1));addStatus();
                LinearLayout line=buttons();TextView cancel=button(t("Hủy","Cancel"),() -> back());
                addButton(line,cancel);addButton(line,button(t("Xóa thư mục","Delete folder"),() -> deleteFolder()));topFocus=cancel;
            }
            FontChoice.apply(panel);
        }
        String selectedLabel() { return draft.size()+t(" ứng dụng được chọn"," selected"); }
        void addList() {
            list=new ListView(owner);list.setId(View.generateViewId());list.setDivider(new ColorDrawable(Color.TRANSPARENT));
            list.setDividerHeight(px(4));list.setSelector(new ColorDrawable(Color.TRANSPARENT));list.setCacheColorHint(Color.TRANSPARENT);
            list.setItemsCanFocus(false);list.setVerticalScrollBarEnabled(false);list.setOverScrollMode(View.OVER_SCROLL_NEVER);
            adapter=new Rows();list.setAdapter(adapter);panel.addView(list,new LinearLayout.LayoutParams(-1,0,1));
            list.setOnItemClickListener((parent,view,position,id) -> {if(position>=0&&position<rows.size())rows.get(position).action.run();});
            list.setOnKeyListener((view,key,event) -> {
                if(key==KeyEvent.KEYCODE_DPAD_UP&&list.getSelectedItemPosition()<=0&&topFocus!=null) {
                    if(event.getAction()==KeyEvent.ACTION_DOWN)topFocus.requestFocus();return true;
                }
                if(key==KeyEvent.KEYCODE_DPAD_DOWN&&list.getSelectedItemPosition()>=rows.size()-1&&bottomFocus!=null) {
                    if(event.getAction()==KeyEvent.ACTION_DOWN)bottomFocus.requestFocus();return true;
                }
                return false;
            });
            if(topFocus!=null) {
                topFocus.setNextFocusDownId(list.getId());
                ViewGroup parent=(ViewGroup)topFocus.getParent();
                for(int i=0;i<parent.getChildCount();i++)parent.getChildAt(i).setNextFocusDownId(list.getId());
            }
        }
        void wireFooter(LinearLayout line) {
            for(int i=0;i<line.getChildCount();i++)line.getChildAt(i).setOnKeyListener((view,key,event) -> {
                if(key==KeyEvent.KEYCODE_DPAD_UP&&list!=null&&!rows.isEmpty()) {
                    if(event.getAction()==KeyEvent.ACTION_DOWN){list.requestFocus();list.setSelection(rows.size()-1);}return true;
                }
                return false;
            });
        }
        void renderTitle() {
            heading(rename?t("Đổi tên thư mục","Rename folder"):t("Tạo thư mục","New folder"));
            input=new EditText(owner);input.setId(View.generateViewId());input.setSingleLine(true);input.setTextColor(Color.WHITE);
            input.setHintTextColor(0xffcbd2da);input.setHint(t("Tên thư mục","Folder name"));input.setTextSize(16);
            input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
            input.setImeOptions(EditorInfo.IME_ACTION_DONE|EditorInfo.IME_FLAG_NO_EXTRACT_UI);input.setShowSoftInputOnFocus(false);
            input.setSelectAllOnFocus(true);input.setPadding(px(12),0,px(12),0);input.setBackground(WallpaperTheme.states(density,tone));
            if(rename)input.setText(folder().name);
            LinearLayout.LayoutParams field=new LinearLayout.LayoutParams(-1,px(46));field.topMargin=px(10);panel.addView(input,field);
            input.setOnFocusChangeListener((view,focused) -> {if(!focused)confirmKey=KeyEvent.KEYCODE_UNKNOWN;});
            input.setOnClickListener(view -> keyboard().showSoftInput(input,InputMethodManager.SHOW_IMPLICIT));
            input.setOnKeyListener((view,key,event) -> {
                if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER) {
                    if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){confirmKey=key;confirmTime=event.getDownTime();}
                    else if(event.getAction()==KeyEvent.ACTION_UP){boolean valid=confirmKey==key&&confirmTime==event.getDownTime()&&!event.isCanceled();confirmKey=KeyEvent.KEYCODE_UNKNOWN;if(valid)keyboard().showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);}
                    return true;
                }
                return false;
            });
            input.setOnEditorActionListener((view,action,event) -> {
                if(action!=EditorInfo.IME_ACTION_DONE&&action!=EditorInfo.IME_ACTION_GO)return false;
                hideKeyboard();saveTitle();return true;
            });
            LinearLayout presets=buttons();
            for(String name:new String[]{t("Phim","Movies"),t("Trò chơi","Games"),t("Công cụ","Tools")})
                addButton(presets,button(name,() -> {input.setText(name);hideKeyboard();bottomFocus.requestFocus();}));
            addStatus();panel.addView(new View(owner),new LinearLayout.LayoutParams(-1,0,1));
            LinearLayout line=buttons();TextView save=button(rename?t("Lưu","Save"):t("Tạo","Create"),() -> saveTitle());
            addButton(line,save);addButton(line,button(t("Hủy","Cancel"),() -> back()));bottomFocus=save;topFocus=input;
            input.setNextFocusDownId(presets.getChildAt(0).getId());
        }
        void focusInitial() {
            if(closed)return;
            if(page==VIEW&&list!=null&&!rows.isEmpty()){list.requestFocus();list.setSelection(0);}
            else if(topFocus!=null)topFocus.requestFocus();
            else if(list!=null&&!rows.isEmpty()){list.requestFocus();list.setSelection(0);}
            else if(bottomFocus!=null)bottomFocus.requestFocus();
        }
        void saveTitle() {
            String name=input.getText().toString().trim();
            if(name.isEmpty()){error(t("Hãy nhập tên thư mục","Enter a folder name"));return;}
            try {
                if(rename)FolderStore.rename(owner,folderId,name);
                else {
                    FolderStore.Folder created=FolderStore.create(owner,name,
                        seedPackage==null?Collections.emptyList():Collections.singletonList(seedPackage),seedComponent);
                    folderId=created.id;
                }
                changed();hideKeyboard();history.clear();page=VIEW;render();focusInitial();
            } catch(IOException|IllegalArgumentException failure){error(t("Không lưu được thư mục","Could not save folder"));}
        }
        void moveSeed(String id) {
            try {
                LinkedHashSet<String> members=new LinkedHashSet<>(snapshot.find(id).packages);members.add(seedPackage);
                FolderStore.updateMembers(owner,id,members);folderId=id;changed();history.clear();page=VIEW;render();focusInitial();
            } catch(IOException|IllegalArgumentException failure){error(t("Không chuyển được ứng dụng","Could not move app"));}
        }
        void saveMembers() {
            if(!loaded||loadFailed)return;
            try {FolderStore.updateMembers(owner,folderId,draft);changed();history.clear();page=VIEW;render();focusInitial();}
            catch(IOException|IllegalArgumentException failure){error(t("Không lưu được lựa chọn","Could not save selection"));}
        }
        void changed() throws IOException {
            snapshot=FolderStore.read(owner);returnKey=FolderStore.componentKey(owner,folderId);
            MainActivity.lastFocusedKey=returnKey;owner.reloadApps(false,true,false);
        }
        void deleteFolder() {
            try {
                FolderStore.delete(owner,folderId);returnKey=null;MainActivity.lastFocusedKey=null;
                owner.reloadApps(false,true,false);PopupOptimizer.dismissAnimated(dialog,false);
            } catch(IOException failure){error(t("Không xóa được thư mục","Could not delete folder"));}
        }
        void error(String value) { if(status!=null)status.setText(value); }
        InputMethodManager keyboard() { return (InputMethodManager)owner.getSystemService(Context.INPUT_METHOD_SERVICE); }
        void hideKeyboard() { keyboard().hideSoftInputFromWindow(panel.getWindowToken(),0); }
        void launch(AppSearch.App app) {
            Intent intent=new Intent(Intent.ACTION_MAIN).setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            if(app.television)intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            hideKeyboard();
            try {launched=true;RecentApps.record(owner,app.component.getPackageName());owner.startActivity(intent);close();dialog.dismiss();}
            catch(ActivityNotFoundException|SecurityException failure){launched=false;error(t("Không mở được ","Could not open ")+app.label);}
        }
        void close() {
            if(closed)return;closed=true;hideKeyboard();panel.animate().cancel();
            if(loading!=null){loading.cancel(true);if(loading instanceof Runnable)WORK.remove((Runnable)loading);loading=null;}
            if(active.get()==this)active=new WeakReference<>(null);
            apps=Collections.emptyList();rows.clear();if(list!=null)list.setAdapter(null);
            if(!launched&&!lifecycleClose&&!owner.isFinishing()&&!owner.isDestroyed()) {
                owner.onUserInteraction();CrystalSoundEngine.scheduleAmbientCheck(owner);
                final long serial=TileLoader.interactionSerial(owner);
                owner.root.post(() -> {
                    if(owner.isFinishing()||owner.isDestroyed()||!owner.hasWindowFocus()||serial!=TileLoader.interactionSerial(owner))return;
                    int position=returnKey==null?-1:owner.adapter.positionOf(returnKey);
                    if(position>=0)owner.focusTile(position);
                    else {
                        View previous=origin.get();
                        if(previous!=null&&previous.isAttachedToWindow())previous.requestFocus();
                        else if(!owner.adapter.items.isEmpty())owner.focusTile(Math.min(Math.max(0,MainActivity.lastFocused),owner.adapter.items.size()-1));
                        else ControlPanelHelper.focusDockFromGrid(owner,0);
                    }
                });
            }
        }
        final class Rows extends BaseAdapter {
            @Override public int getCount(){return rows.size();}
            @Override public Object getItem(int position){return rows.get(position);}
            @Override public long getItemId(int position){return position;}
            @Override public View getView(int position,View recycled,ViewGroup parent) {
                LinearLayout row;
                if(recycled instanceof LinearLayout)row=(LinearLayout)recycled;
                else {
                    row=new LinearLayout(owner);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(px(10),0,px(10),0);row.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1,px(42)));
                    StateListDrawable background=new StateListDrawable();
                    background.addState(new int[]{android.R.attr.state_pressed},WallpaperTheme.focusWash(px(10),tone,true));
                    background.addState(new int[]{android.R.attr.state_selected},WallpaperTheme.focusWash(px(10),tone,false));
                    background.addState(new int[]{},WallpaperTheme.tileGlass(px(10),tone));row.setBackground(background);
                    ImageView icon=new ImageView(owner);icon.setScaleType(ImageView.ScaleType.FIT_CENTER);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    row.addView(icon,new LinearLayout.LayoutParams(px(28),px(28)));
                    TextView label=text("",14);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-1,1);params.setMarginStart(px(12));row.addView(label,params);
                    CheckBox check=new CheckBox(owner);check.setFocusable(false);check.setClickable(false);check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    check.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));row.addView(check,new LinearLayout.LayoutParams(px(32),-1));FontChoice.apply(row);
                }
                Row item=rows.get(position);ImageView icon=(ImageView)row.getChildAt(0);
                icon.setImageDrawable(item.icon);icon.setVisibility(item.icon==null?View.GONE:View.VISIBLE);
                ((TextView)row.getChildAt(1)).setText(item.label);
                CheckBox check=(CheckBox)row.getChildAt(2);check.setVisibility(item.check?View.VISIBLE:View.GONE);check.setChecked(item.check&&draft.contains(item.pkg));
                row.setContentDescription(item.label+(item.check?(draft.contains(item.pkg)?t(", đã chọn",", selected"):t(", chưa chọn",", not selected")):""));return row;
            }
        }
    }
}
