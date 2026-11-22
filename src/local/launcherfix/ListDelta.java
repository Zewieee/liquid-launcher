package local.launcherfix;
import java.util.*;
import java.util.function.*;
/** Ordered keyed updates. Existing objects survive when their content is unchanged. */
public final class ListDelta {
    public interface Sink {void inserted(int at);void removed(int at);void moved(int from,int to);void changed(int at);}
    public static <T> int apply(List<T> live,List<T> desired,Function<T,String> key,BiPredicate<T,T> same,Sink sink){
        int edits=0;
        Set<String> wantedKeys=new HashSet<>();for(T row:desired)wantedKeys.add(key.apply(row));
        for(int i=live.size()-1;i>=0;i--)if(!wantedKeys.contains(key.apply(live.get(i)))){live.remove(i);sink.removed(i);edits++;}
        for(int i=0;i<desired.size();i++){
            T next=desired.get(i);String wanted=key.apply(next);int found=-1;
            for(int j=i;j<live.size();j++)if(wanted.equals(key.apply(live.get(j)))){found=j;break;}
            if(found<0){live.add(i,next);sink.inserted(i);edits++;}
            else {
                if(found!=i){T old=live.remove(found);live.add(i,old);sink.moved(found,i);edits++;}
                if(!same.test(live.get(i),next)){live.set(i,next);sink.changed(i);edits++;}
            }
        }
        while(live.size()>desired.size()){int at=live.size()-1;live.remove(at);sink.removed(at);edits++;}
        return edits;
    }
}
