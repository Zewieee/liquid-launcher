package com.gathin.tvhome;
import android.content.*;
import android.graphics.drawable.Drawable;
import android.content.res.Resources;
public abstract class IconPacks {
    public static final class Data {public String packageName;public long updated;public Resources resources;}
    public static Drawable forApp(Context c,String pkg,ComponentName component){return null;}
    public static Drawable drawable(Data data,String name){return null;}
}
