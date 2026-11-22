package com.gathin.tvhome;
import android.app.Activity;import android.content.Context;import java.io.File;
public final class PaperTransition {
 public static String shaderBody(String effect){return null;}
 public static String shaderPrep(String effect){return null;}
 public static String chosen(Context context){return null;}
 public static void prepare(Activity activity,File oldFile,File newFile,String effect,Runnable ready){}
 public static void start(Activity activity){}
 public static Runnable playFrames(Activity activity,android.graphics.Bitmap from,android.graphics.Bitmap to,String effect,Runnable finish){return null;}
 public static boolean preparedFor(Runnable owner){return false;}
 public static void discardFor(Runnable owner){}
}
