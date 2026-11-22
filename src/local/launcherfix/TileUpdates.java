package local.launcherfix;
import com.gathin.tvhome.MainActivity;
import com.gathin.tvhome.AppAdapter;
import java.util.*;
public final class TileUpdates {
    private static boolean same(AppAdapter.Entry a,AppAdapter.Entry b){
        return a.kind==b.kind && Objects.equals(a.name,b.name)&&Objects.equals(a.label,b.label)&&Objects.equals(a.material,b.material)
            && a.iconColor==b.iconColor && a.centeredIcon==b.centeredIcon && a.seedAccent==b.seedAccent
            && ImageCache.token(a.banner).equals(ImageCache.token(b.banner))&&ImageCache.token(a.icon).equals(ImageCache.token(b.icon));
    }
    public static void apply(MainActivity a,AppAdapter snapshot,List entries,List cards,int sequence){
        AppAdapter live=a.adapter;boolean layout=live.docked!=snapshot.docked || live.emptySlots!=snapshot.emptySlots;
        FolderTiles.publish(live,snapshot);
        live.leanbackPackages=snapshot.leanbackPackages;live.docked=snapshot.docked;live.emptySlots=snapshot.emptySlots;a.cards=cards;
        int edits=ListDelta.apply(live.items,(List<AppAdapter.Entry>)entries,row->row.component.flattenToString(),TileUpdates::same,new ListDelta.Sink(){
            public void inserted(int at){live.tileInserted(at);}public void removed(int at){live.tileRemoved(at);}
            public void moved(int from,int to){live.tileMoved(from,to);}public void changed(int at){live.tileChanged(at);}
        });
        if(layout)for(int i=0;i<live.items.size();i++)live.tileChanged(i);
        if(edits>0||layout){a.heroArtCache.clear();a.heroArtKey=null;}
        a.packageSequence=sequence;
    }
}
