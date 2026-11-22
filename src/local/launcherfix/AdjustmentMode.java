package local.launcherfix;
import android.view.*;import android.widget.TextView;import java.util.function.BooleanSupplier;

/** Arrows navigate until OK explicitly activates the focused value tile. */
public final class AdjustmentMode implements View.OnKeyListener,View.OnClickListener,View.OnFocusChangeListener,View.OnAttachStateChangeListener {
 public interface Nudge{boolean apply(int key,KeyEvent event);}
 final View tile;final TextView status;final boolean vi;final Nudge nudge;final BooleanSupplier available;final Runnable fallback;boolean editing;
 public AdjustmentMode(View tile,TextView status,boolean vi,Nudge nudge,BooleanSupplier available,Runnable fallback){this.tile=tile;this.status=status;this.vi=vi;this.nudge=nudge;this.available=available;this.fallback=fallback;tile.setOnClickListener(this);tile.setOnKeyListener(this);tile.setOnFocusChangeListener(this);tile.addOnAttachStateChangeListener(this);}
 void setEditing(boolean value){editing=value;tile.setActivated(value);status.setText(value?(vi?"◀/▶ chỉnh · OK xong":"◀/▶ adjust · OK done"):(vi?"Điều khiển nhanh":"Quick controls"));if(value)tile.announceForAccessibility(status.getText());}
 public void onClick(View view){if(!editing&&!available.getAsBoolean()){fallback.run();return;}setEditing(!editing);}
 public boolean onKey(View view,int key,KeyEvent event){
  if(!editing)return false;
  if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){if(event.getAction()==KeyEvent.ACTION_DOWN&&!nudge.apply(key,event)){setEditing(false);fallback.run();}return true;}
  if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN)setEditing(false);
  return false;
 }
 public void onFocusChange(View view,boolean focused){if(!focused&&editing)setEditing(false);}
 public void onViewAttachedToWindow(View view){}
 public void onViewDetachedFromWindow(View view){if(editing)setEditing(false);}
}
