package local.launcherfix;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ChangedPackages;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Enumeration cache is worker-confined; package events use a concurrent version map. */
public final class Catalog {
    private static final Map<String,Entry> ENTRIES=new HashMap<>();
    private static final ConcurrentHashMap<String,Long> EVENTS=new ConcurrentHashMap<>();
    private static final AtomicLong SERIAL=new AtomicLong(),RESET=new AtomicLong();
    private static int fullQueries,scopedQueries;
    private static int imageSequence;
    private static Set<String> lastScoped=Collections.emptySet();
    private static final class Entry {List<ResolveInfo> rows;Map<String,Long> seen;int sequence;long reset;}
    private Catalog(){}
    public static void changed(Intent intent){
        String action=intent.getAction();
        if(Intent.ACTION_PACKAGE_REMOVED.equals(action)&&intent.getBooleanExtra(Intent.EXTRA_REPLACING,false))return;
        if(intent.getData()==null)return;
        String pkg=intent.getData().getSchemeSpecificPart();
        touch(pkg);
    }
    public static void touch(String pkg){
        if(pkg==null||pkg.isEmpty())return;
        ImageCache.invalidate(pkg);
        if(EVENTS.size()>256){EVENTS.clear();RESET.incrementAndGet();}
        EVENTS.put(pkg,SERIAL.incrementAndGet());
    }
    public static synchronized List<ResolveInfo> query(Context context,PackageManager pm,Intent intent,int flags){
        if(intent.getPackage()!=null)return pm.queryIntentActivities(intent,flags);
        TreeSet<String> categories=new TreeSet<>();if(intent.getCategories()!=null)categories.addAll(intent.getCategories());
        String key=intent.getAction()+"|"+categories+"|"+flags+"|"+ImageCache.configuration(context);
        Entry old=ENTRIES.get(key);Map<String,Long> seen=new HashMap<>(EVENTS);Set<String> changed=new LinkedHashSet<>();
        int sequence=old==null?0:old.sequence;
        ChangedPackages system=pm.getChangedPackages(sequence);if(system!=null){sequence=system.getSequenceNumber();changed.addAll(system.getPackageNames());}
        if(system!=null && system.getSequenceNumber()>imageSequence){
            if(imageSequence==0)imageSequence=system.getSequenceNumber();
            else {
                ChangedPackages fresh=pm.getChangedPackages(imageSequence);
                if(fresh!=null){for(String pkg:fresh.getPackageNames())ImageCache.invalidate(pkg);imageSequence=fresh.getSequenceNumber();}
            }
        }
        if(old!=null)for(Map.Entry<String,Long> event:seen.entrySet())if(!event.getValue().equals(old.seen.get(event.getKey())))changed.add(event.getKey());
        long reset=RESET.get();List<ResolveInfo> rows;
        lastScoped=Collections.emptySet();
        if(old==null || old.reset!=reset || changed.size()>8){rows=pm.queryIntentActivities(intent,flags);fullQueries++;}
        else {
            rows=new ArrayList<>(old.rows);
            if(!changed.isEmpty()){
                rows.removeIf(row->row.activityInfo!=null&&changed.contains(row.activityInfo.packageName));
                for(String pkg:changed){rows.addAll(pm.queryIntentActivities(new Intent(intent).setPackage(pkg),flags));scopedQueries++;}
                lastScoped=new HashSet<>(changed);
            }
        }
        if(ENTRIES.size()>6)ENTRIES.clear();
        if(rows.size()<=512){Entry entry=new Entry();entry.rows=new ArrayList<>(rows);entry.seen=seen;entry.sequence=sequence;entry.reset=reset;ENTRIES.put(key,entry);}
        return rows;
    }
}
