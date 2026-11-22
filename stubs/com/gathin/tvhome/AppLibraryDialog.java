package com.gathin.tvhome;
import android.app.Activity;
import android.widget.PopupWindow;
import java.util.HashSet;
public final class AppLibraryDialog {
    public Activity activity;
    public PopupWindow popup;
    public PopupWindow actionPopup;
    public HashSet tvPackages;
    public boolean closed;
    public void close() {}
    public void dismissActions() {}
}
