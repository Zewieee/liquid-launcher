package local.launcherfix;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Small, lifecycle-bound dock readings; never refreshes the panel or glass background. */
public final class DockStats {
    private static final long CPU_INTERVAL=3000, RAM_INTERVAL=5000;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor WORKER=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(1),task->new Thread(()->{
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            task.run();
        },"launcher-dock-stats"));
    private static final WeakHashMap<Activity,Host> HOSTS=new WeakHashMap<>();
    // A denied/unavailable system source is not retried on every sample or resume.
    private static volatile int cpuSource; // 0 unknown, 1 TV, 2 this app
    static int cpuSource(){return cpuSource;}
    static {WORKER.allowCoreThreadTimeOut(true);}
    private DockStats(){}

    private static final class Host {
        boolean resumed,ambient;
        WeakReference<StatsView> view=new WeakReference<>(null);
    }
    private static Host host(Activity owner){
        Host host=HOSTS.get(owner);
        if(host==null){host=new Host();HOSTS.put(owner,host);}
        return host;
    }
    public static View create(Activity owner,View pill,float dp,Typeface font,boolean vi){
        Host host=host(owner);
        StatsView previous=host.view.get();
        if(previous!=null)previous.dispose();
        StatsView view=new StatsView(owner,pill,host,dp,font,vi);
        host.view=new WeakReference<>(view);
        return view;
    }
    public static void onResume(Activity owner){if(owner==null)return;host(owner).resumed=true;refreshVisibility(owner);}
    public static void onPause(Activity owner){if(owner==null)return;Host host=HOSTS.get(owner);if(host!=null){host.resumed=false;refreshVisibility(owner);}}
    public static void setAmbient(Activity owner,boolean ambient){if(owner==null)return;host(owner).ambient=ambient;refreshVisibility(owner);}
    public static void refreshVisibility(Activity owner){Host host=HOSTS.get(owner);StatsView view=host==null?null:host.view.get();if(view!=null)view.reconcile();}
    public static void onDestroy(Activity owner){Host host=HOSTS.remove(owner);if(host!=null){StatsView view=host.view.get();if(view!=null)view.dispose();}}

    private static final class StatsView extends LinearLayout {
        final WeakReference<Activity> owner;
        final WeakReference<View> pill;
        final Host host;
        final Context app;
        final boolean vi;
        final TextView cpuValue,ramValue;
        final Runnable tick=this::sample;
        final Runnable visibility=this::reconcile;
        boolean running,disposed;
        long generation,nextCpu,nextRam;
        int cpu=-1,ram=-1,source;
        Future<?> pending;
        Sampler sampler;

        StatsView(Activity owner,View pill,Host host,float dp,Typeface font,boolean vi){
            super(owner);
            this.owner=new WeakReference<>(owner);this.pill=new WeakReference<>(pill);
            this.host=host;this.app=owner.getApplicationContext();this.vi=vi;
            setTag("dock_cpu_ram");setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);
            setBaselineAligned(false);setFocusable(false);setClickable(false);
            setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            cpuValue=metric(dp,font,false);
            ramValue=metric(dp,font,true);
            source=cpuSource;paintValues();
        }
        private TextView metric(float dp,Typeface font,boolean memory){
            LinearLayout group=new LinearLayout(getContext());group.setGravity(Gravity.CENTER_VERTICAL);
            group.setBaselineAligned(false);group.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            ImageView icon=new ImageView(getContext());icon.setImageDrawable(new StatGlyph(dp,memory));
            icon.setScaleType(ImageView.ScaleType.CENTER);
            LayoutParams iconLp=new LayoutParams(Math.round(10*dp),Math.round(12*dp));
            iconLp.setMarginEnd(Math.round(2*dp));group.addView(icon,iconLp);
            TextView value=text(font,9.5f,0xe6ffffff);
            value.setText("--");group.addView(value,new LayoutParams(0,-1,1));
            addView(group,new LayoutParams(Math.round(31*dp),Math.round(16*dp)));
            return value;
        }
        private TextView text(Typeface font,float size,int color){
            TextView text=new TextView(getContext());text.setTypeface(font);text.setTextSize(size);text.setTextColor(color);
            text.setSingleLine(true);text.setHorizontallyScrolling(false);text.setIncludeFontPadding(false);
            text.setEllipsize(TextUtils.TruncateAt.END);text.setFontFeatureSettings("tnum");
            text.setGravity(Gravity.CENTER_VERTICAL);text.setFocusable(false);text.setClickable(false);
            return text;
        }
        private boolean eligible(){
            Activity activity=owner.get();View dock=pill.get();
            return !disposed&&host.resumed&&!host.ambient&&activity!=null&&!activity.isFinishing()
                &&!activity.isDestroyed()&&isAttachedToWindow()&&isShown()&&getWindowVisibility()==VISIBLE
                &&hasWindowFocus()&&dock!=null&&dock.getAlpha()>.01f;
        }
        void reconcile(){
            if(host==null)return; // View constructors can dispatch visibility callbacks.
            if(!eligible()){stop();return;}
            if(running)return;
            running=true;generation++;sampler=new Sampler();
            // Keep the last readings while the restarted CPU sampler establishes its baseline.
            paintValues();
            nextCpu=nextRam=SystemClock.uptimeMillis();MAIN.post(tick);
        }
        void stop(){
            running=false;generation++;MAIN.removeCallbacks(tick);MAIN.removeCallbacks(visibility);
            if(pending!=null){pending.cancel(true);if(pending instanceof Runnable)WORKER.remove((Runnable)pending);pending=null;}
            sampler=null;
        }
        void dispose(){disposed=true;stop();}
        private void sample(){
            if(!running||!eligible()){stop();return;}
            long now=SystemClock.uptimeMillis();boolean readCpu=now>=nextCpu,readRam=now>=nextRam;
            if(!readCpu&&!readRam){schedule();return;}
            if(readCpu)nextCpu=now+CPU_INTERVAL;if(readRam)nextRam=now+RAM_INTERVAL;
            try{pending=WORKER.submit(new Read(app,new WeakReference<>(this),generation,sampler,readCpu,readRam));}
            catch(RejectedExecutionException busy){MAIN.postDelayed(tick,CPU_INTERVAL);}
        }
        private void schedule(){if(running)MAIN.postDelayed(tick,Math.max(1,Math.min(nextCpu,nextRam)-SystemClock.uptimeMillis()));}
        void accept(long token,Reading result){
            if(!running||generation!=token)return;
            pending=null;
            if(!eligible()){stop();return;}
            if(result.cpuRead){if(!result.cpuWarmup||source!=result.source)cpu=result.cpu;source=result.source;}
            if(result.ramRead)ram=result.ram;
            paintValues();schedule();
        }
        private void paintValues(){
            changed(cpuValue,number(cpu));changed(ramValue,number(ram));
            String cpuName=source==1?(vi?"CPU toàn TV":"TV CPU"):
                source==2?(vi?"CPU launcher, phần trăm tổng năng lực CPU":"Launcher CPU, percent of total CPU capacity"):
                (vi?"CPU đang đọc":"CPU loading");
            String description=cpuName+": "+spoken(cpu)+", "+(vi?"RAM hệ thống đang dùng: ":"System RAM used: ")+spoken(ram);
            if(!TextUtils.equals(getContentDescription(),description))setContentDescription(description);
        }
        private String spoken(int value){return value<0?(vi?"chưa có số liệu":"unavailable"):value+(vi?" phần trăm":" percent");}
        private static String number(int value){return value<0?"--":Integer.toString(value);}
        private static void changed(TextView view,String value){if(!TextUtils.equals(view.getText(),value))view.setText(value);}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();reconcile();}
        @Override protected void onDetachedFromWindow(){stop();super.onDetachedFromWindow();}
        @Override protected void onVisibilityChanged(View changed,int visibility){super.onVisibilityChanged(changed,visibility);if(host!=null)reconcile();}
        @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(host!=null)reconcile();}
        @Override public void onWindowFocusChanged(boolean focused){
            super.onWindowFocusChanged(focused);
            if(host==null)return;
            if(!focused)stop();else {MAIN.removeCallbacks(visibility);MAIN.post(visibility);}
        }
    }

    private static final class Reading {
        boolean cpuRead,ramRead,cpuWarmup;
        int cpu=-1,ram=-1,source;
    }
    private static final class Read implements Runnable {
        final Context app;final WeakReference<StatsView> target;final long token;
        final Sampler sampler;final boolean cpu,ram;
        Read(Context app,WeakReference<StatsView> target,long token,Sampler sampler,boolean cpu,boolean ram){
            this.app=app;this.target=target;this.token=token;this.sampler=sampler;this.cpu=cpu;this.ram=ram;
        }
        public void run(){
            if(Thread.currentThread().isInterrupted())return;
            Reading result=new Reading();
            if(cpu){result.cpuRead=true;result.cpu=sampler.cpu();result.cpuWarmup=sampler.cpuWarmup;result.source=cpuSource;}
            if(ram){result.ramRead=true;result.ram=sampler.ram(app);}
            if(Thread.currentThread().isInterrupted())return;
            MAIN.post(()->{StatsView view=target.get();if(view!=null)view.accept(token,result);});
        }
    }
    static final class Sampler {
        long total=-1,idle=-1,appCpu=-1,appTime=-1;
        boolean cpuWarmup;
        final int cores=Math.max(1,Runtime.getRuntime().availableProcessors());
        final ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();
        int cpu(){
            cpuWarmup=false;
            if(cpuSource!=2){
                try{
                    long[] current=systemCpu();cpuSource=1;
                    cpuWarmup=total<0;
                    long elapsed=current[0]-total,rest=current[1]-idle;
                    int value=total<0||elapsed<=0||rest<0||rest>elapsed?-1:percent(elapsed-rest,elapsed);
                    total=current[0];idle=current[1];return value;
                }catch(IOException|RuntimeException unavailable){
                    if(Thread.currentThread().isInterrupted())return -1;
                    cpuSource=2;
                }
            }
            try{
                long now=SystemClock.uptimeMillis(),used=android.os.Process.getElapsedCpuTime();
                if(used<0){appTime=appCpu=-1;return -1;}
                cpuWarmup=appTime<0;
                long elapsed=now-appTime,delta=used-appCpu;
                int value=appTime<0||elapsed<=0||delta<0?-1:percent(delta,(double)elapsed*cores);
                appTime=now;appCpu=used;return value;
            }catch(RuntimeException unavailable){appTime=appCpu=-1;return -1;}
        }
        int ram(Context app){
            try{
                ActivityManager manager=(ActivityManager)app.getSystemService(Context.ACTIVITY_SERVICE);
                if(manager==null)return -1;
                manager.getMemoryInfo(memory);
                if(memory.totalMem<=0||memory.availMem<0||memory.availMem>memory.totalMem)return -1;
                return percent(memory.totalMem-memory.availMem,memory.totalMem);
            }catch(RuntimeException unavailable){return -1;}
        }
        private static int percent(long used,double total){return (int)Math.max(0,Math.min(100,Math.round(used*100d/total)));}
        private static long[] systemCpu() throws IOException {
            try(BufferedReader input=new BufferedReader(new FileReader("/proc/stat"),1024)){
                String line=input.readLine();
                if(line==null)throw new IOException("No aggregate CPU counters");
                String[] fields=line.trim().split("\\s+");
                if(fields.length<9||!"cpu".equals(fields[0]))throw new IOException("Invalid CPU counters");
                long total=0,idle=0;
                // guest and guest_nice are already included in user/nice: read only the first eight.
                for(int i=1;i<=8;i++){
                    long value=Long.parseLong(fields[i]);
                    if(value<0||Long.MAX_VALUE-total<value)throw new IOException("Invalid CPU counter range");
                    total+=value;if(i==4||i==5)idle+=value;
                }
                return new long[]{total,idle};
            }
        }
    }
    private static final class StatGlyph extends Drawable {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect=new RectF();final int size;final boolean memory;
        StatGlyph(float dp,boolean memory){size=Math.round(10*dp);this.memory=memory;paint.setColor(0xe6ffffff);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.6f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);}
        public void draw(Canvas canvas){
            Rect bounds=getBounds();int save=canvas.save();
            canvas.translate(bounds.left,bounds.top);canvas.scale(bounds.width()/16f,bounds.height()/16f);
            if(memory){
                rect.set(1.5f,4,14.5f,11.5f);canvas.drawRoundRect(rect,1,1,paint);
                for(int x=4;x<=12;x+=4){canvas.drawLine(x,7,x,8.5f,paint);canvas.drawLine(x,12,x,14,paint);}
            }else{
                rect.set(4,4,12,12);canvas.drawRoundRect(rect,1.2f,1.2f,paint);
                for(int x=5;x<=11;x+=3){canvas.drawLine(x,1.5f,x,4,paint);canvas.drawLine(x,12,x,14.5f,paint);
                    canvas.drawLine(1.5f,x,4,x,paint);canvas.drawLine(12,x,14.5f,x,paint);}
            }
            canvas.restoreToCount(save);
        }
        public int getIntrinsicWidth(){return size;}public int getIntrinsicHeight(){return size;}
        public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
        public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
        public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
