package local.launcherfix;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import java.lang.ref.WeakReference;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Device-dialog readings; start/stop are called on the UI thread with window visibility. */
final class DeviceInfoReader {
    interface Listener {void onReading(Reading reading);}
    private static final long CPU_INTERVAL=3000, RAM_INTERVAL=5000;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor WORKER=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(1),task->new Thread(()->{
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            task.run();
        },"launcher-device-info"));
    static {WORKER.allowCoreThreadTimeOut(true);}

    private final Context app;
    private final WeakReference<Listener> listener;
    private final Runnable tick=this::sample;
    private boolean running;
    private long generation,nextCpu,nextRam;
    private Future<?> pending;
    private Session session;

    DeviceInfoReader(Context context,Listener listener){
        app=context.getApplicationContext();this.listener=new WeakReference<>(listener);
    }
    void start(){
        if(running||listener.get()==null)return;
        running=true;generation++;session=new Session(app);
        nextCpu=nextRam=SystemClock.uptimeMillis();MAIN.post(tick);
    }
    void stop(){
        running=false;generation++;MAIN.removeCallbacks(tick);
        if(pending!=null){pending.cancel(true);if(pending instanceof Runnable)WORKER.remove((Runnable)pending);pending=null;}
        session=null;
    }
    private void sample(){
        if(!running)return;
        if(listener.get()==null){stop();return;}
        long now=SystemClock.uptimeMillis();boolean cpu=now>=nextCpu,ram=now>=nextRam;
        if(!cpu&&!ram){schedule();return;}
        if(cpu)nextCpu=now+CPU_INTERVAL;if(ram)nextRam=now+RAM_INTERVAL;
        try{pending=WORKER.submit(new Read(new WeakReference<>(this),generation,session,cpu,ram));}
        catch(RejectedExecutionException busy){MAIN.postDelayed(tick,CPU_INTERVAL);}
    }
    private void schedule(){
        if(running)MAIN.postDelayed(tick,Math.max(1,Math.min(nextCpu,nextRam)-SystemClock.uptimeMillis()));
    }
    private void accept(long token,Reading result){
        if(!running||generation!=token)return;
        pending=null;
        Listener target=listener.get();
        if(target==null){stop();return;}
        target.onReading(result);
        if(running&&generation==token)schedule();
    }

    static final class Reading {
        final boolean cpuRead,ramRead;
        final int cpuPercent,cpuSource,ramPercent;
        final long ramUsed,ramTotal,storageUsed,storageTotal,uptimeMs;
        final String hardware,kernel;
        Reading(Session session,boolean cpuRead,boolean ramRead){
            this.cpuRead=cpuRead;this.ramRead=ramRead;
            cpuPercent=session.cpuPercent;cpuSource=session.cpuSource;ramPercent=session.ramPercent;
            ramUsed=session.ramUsed;ramTotal=session.ramTotal;
            storageUsed=session.storageUsed;storageTotal=session.storageTotal;
            hardware=session.hardware;kernel=session.kernel;uptimeMs=SystemClock.elapsedRealtime();
        }
    }
    private static final class Read implements Runnable {
        final WeakReference<DeviceInfoReader> target;
        final long token;final Session session;final boolean cpu,ram;
        Read(WeakReference<DeviceInfoReader> target,long token,Session session,boolean cpu,boolean ram){
            this.target=target;this.token=token;this.session=session;this.cpu=cpu;this.ram=ram;
        }
        public void run(){
            if(Thread.currentThread().isInterrupted())return;
            session.read(cpu,ram);
            if(Thread.currentThread().isInterrupted())return;
            Reading reading=new Reading(session,cpu,ram);
            MAIN.post(()->{DeviceInfoReader reader=target.get();if(reader!=null)reader.accept(token,reading);});
        }
    }
    private static final class Session {
        final Context app;
        final DockStats.Sampler sampler=new DockStats.Sampler();
        int cpuPercent=-1,cpuSource,ramPercent=-1;
        long ramUsed=-1,ramTotal=-1,storageUsed=-1,storageTotal=-1;
        String hardware="—",kernel="—";
        boolean detailsRead;
        Session(Context app){this.app=app;}
        void read(boolean cpu,boolean ram){
            if(!detailsRead){detailsRead=true;readDetails();}
            if(cpu){cpuPercent=sampler.cpu();cpuSource=DockStats.cpuSource();}
            if(ram){
                ramPercent=sampler.ram(app);
                ramTotal=ramPercent<0?-1:sampler.memory.totalMem;
                ramUsed=ramPercent<0?-1:ramTotal-sampler.memory.availMem;
            }
        }
        private void readDetails(){
            String abi=Build.SUPPORTED_ABIS.length>0?Build.SUPPORTED_ABIS[0]:"—";
            hardware=Build.HARDWARE+" · "+abi+" · "+sampler.cores+" cores";
            try{String value=System.getProperty("os.version");if(value!=null&&!value.isEmpty())kernel=value;}
            catch(RuntimeException unavailable){}
            try{
                StatFs data=new StatFs(app.getFilesDir().getAbsolutePath());
                long total=data.getTotalBytes(),free=data.getFreeBytes();
                if(total>0&&free>=0&&free<=total){storageTotal=total;storageUsed=total-free;}
            }catch(RuntimeException unavailable){}
        }
    }
}
