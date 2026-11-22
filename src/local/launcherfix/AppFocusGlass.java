package local.launcherfix;
import android.graphics.*;import android.graphics.drawable.Drawable;import android.graphics.drawable.ColorDrawable;import android.view.View;import java.lang.ref.WeakReference;import java.util.WeakHashMap;

/** Borderless focus wash; native lens/tint is bypassed for registered focused cards. */
public final class AppFocusGlass {
 private static final WeakHashMap<View,WeakReference<State>> STATES=new WeakHashMap<>();
 private static final WeakHashMap<View,Highlight> MARKERS=new WeakHashMap<>();
 private AppFocusGlass(){}
 static State get(View view){WeakReference<State> reference=STATES.get(view);return reference==null?null:reference.get();}
 static State state(View view){State state=get(view);if(state==null){state=new State(view);STATES.put(view,new WeakReference<>(state));view.addOnAttachStateChangeListener(state);}return state;}
 public static void bind(View view){if(view==null)return;State state=state(view);if(view.getBackground()!=state.outline)state.base=view.getBackground();if(view.isFocused())view.setBackground(state.outline);}
 public static void focus(View view,boolean focused){if(view==null)return;State state=focused?state(view):get(view);if(state==null)return;if(focused){if(view.getBackground()!=state.outline)state.base=view.getBackground();view.setBackground(state.outline);}else if(view.getBackground()==state.outline)view.setBackground(state.base);}
 public static boolean skipFill(View view){return view!=null&&view.isFocused()&&get(view)!=null;}
 public static void drawMarker(View view,Canvas canvas){Highlight highlight=MARKERS.get(view);float density=view.getResources().getDisplayMetrics().density;if(highlight==null){highlight=new Highlight(density);MARKERS.put(view,highlight);}int inset=Math.round(6*density);highlight.setBounds(inset,inset,Math.max(inset,view.getWidth()-inset),Math.max(inset,view.getHeight()-inset));highlight.draw(canvas);}
 private static final class State implements View.OnAttachStateChangeListener {
  Drawable base;final Drawable outline;
  State(View view){base=view.getBackground();outline=new ColorDrawable(Color.TRANSPARENT);}
  public void onViewAttachedToWindow(View view){if(view.isFocused())focus(view,true);}
  public void onViewDetachedFromWindow(View view){if(view.getBackground()==outline)view.setBackground(base);base=null;STATES.remove(view);view.removeOnAttachStateChangeListener(this);}
 }
 private static final class Highlight extends Drawable {
  final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);final RectF rect=new RectF();final float density;
  Highlight(float density){this.density=density;}
  protected void onBoundsChange(Rect bounds){paint.setShader(new LinearGradient(0,bounds.top,0,Math.max(bounds.top+1,bounds.bottom),0x20ffffff,0x0cffffff,Shader.TileMode.CLAMP));}
  public void draw(Canvas canvas){rect.set(getBounds());float radius=Math.min(16*density,rect.width()*16f/180f);canvas.drawRoundRect(rect,radius,radius,paint);}
  public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
 }
}
