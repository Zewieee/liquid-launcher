package local.launcherfix;
import android.view.View;
import com.gathin.tvhome.LiquidGlassRoot;
public final class GlassPolicy {
    private GlassPolicy(){}
    public static void pulse(LiquidGlassRoot root,float value){
        float quantized=value<=0.02f?0f:Math.round(value*6f)/6f;
        if(Math.abs(root.pulse-quantized)<0.07f && quantized!=0f)return;
        root.pulse=quantized;boolean changed=false;
        for(LiquidGlassRoot.Surface surface:root.surfaces){
            View owner=surface.owner;if(owner==null)continue;
            float next=(owner.isFocused()||surface.content)?quantized:0;
            if(Math.abs(surface.press-next)>.075f || (next==0 && surface.press!=0)){
                surface.dirty=true;owner.invalidate();changed=true;
            }
        }
        if(changed)root.invalidate();
    }
}
