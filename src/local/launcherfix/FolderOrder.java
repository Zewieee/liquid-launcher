package local.launcherfix;

import java.util.*;

/** Keeps hidden app keys in the saved order when a folder is moved on the home screen. */
public final class FolderOrder {
    private FolderOrder() {}
    public static final class Result {
        public final List<String> keys;
        public final int docked;
        Result(Collection<String> keys,int docked) {
            this.keys=Collections.unmodifiableList(new ArrayList<>(keys));this.docked=docked;
        }
    }
    public static String packageName(String component) {
        if(component==null)return "";
        int slash=component.indexOf('/');return slash>0?component.substring(0,slash):"";
    }
    public static Result expand(List<String> visible,int visibleDocked,Map<String,Integer> priorRanks,
            Map<String,? extends Collection<String>> membersByFolderKey) {
        ArrayList<String> prior=new ArrayList<>(priorRanks.keySet());
        prior.removeIf(key->key==null||priorRanks.get(key)==null);
        Collections.sort(prior,(left,right)->{
            int rank=Integer.compare(priorRanks.get(left),priorRanks.get(right));
            return rank!=0?rank:left.compareTo(right);
        });
        return expand(visible,visibleDocked,prior,membersByFolderKey);
    }
    public static Result expand(List<String> visible,int visibleDocked,List<String> prior,
            Map<String,? extends Collection<String>> membersByFolderKey) {
        LinkedHashSet<String> expanded=new LinkedHashSet<>();
        int docked=0,boundary=Math.min(Math.max(0,visibleDocked),visible.size());
        for(int i=0;i<visible.size();i++) {
            String key=visible.get(i);
            if(key!=null&&!key.isEmpty()) {
                expanded.add(key);
                Collection<String> members=membersByFolderKey.get(key);
                if(members!=null&&!members.isEmpty()) {
                    HashSet<String> packages=new HashSet<>(members);
                    for(String old:prior) {
                        if(old!=null&&!membersByFolderKey.containsKey(old)&&packages.contains(packageName(old)))expanded.add(old);
                    }
                }
            }
            if(i+1==boundary)docked=expanded.size();
        }
        return new Result(expanded,visibleDocked<0?-1:docked);
    }
}
