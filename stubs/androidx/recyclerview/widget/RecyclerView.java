package androidx.recyclerview.widget;
import android.content.Context;
import android.view.ViewGroup;
public class RecyclerView extends ViewGroup {
    public RecyclerView(Context context){super(context);}
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){}
    public void scrollToPosition(int position){}
    public void setItemViewCacheSize(int size){}
    public static abstract class ViewHolder {
        public final android.view.View itemView;
        public ViewHolder(android.view.View itemView){this.itemView=itemView;}
        public final int getBindingAdapterPosition(){return -1;}
    }
}
