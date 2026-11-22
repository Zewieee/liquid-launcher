package local.launcherfix;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Toast;
import java.lang.reflect.Method;

/** Matches this TV firmware's native two-choice Speakers shortcut. */
final class AudioOutput {
    static final String SETTING = "sound_output_switch";
    static final int SPEAKER = 7, SPDIF = 6;
    private static final int DEVICE_OUT_SPDIF = 0x80000;

    private AudioOutput() {}

    static int current(Context context) {
        try {
            int value = Settings.Global.getInt(context.getContentResolver(), SETTING, -1);
            return value == SPEAKER || value == SPDIF ? value : -1;
        } catch (RuntimeException unavailable) { return -1; }
    }

    private static Method wiredMethod() throws NoSuchMethodException {
        return AudioManager.class.getMethod("setWiredDeviceConnectionState",
            int.class, int.class, String.class, String.class);
    }

    static void show(Activity activity, View anchor, float dp, boolean vi) {
        int selected = current(activity);
        try {
            wiredMethod();
            if (selected < 0 || activity.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                    != PackageManager.PERMISSION_GRANTED) {
                openSettings(activity, vi);
                return;
            }
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            openSettings(activity, vi);
            return;
        }
        CrystalSoundEngine.showQuickSelectDialog(activity, dp, anchor,
            vi ? "Đầu ra âm thanh" : "Audio output",
            new String[]{"speaker", "spdif"}, selected == SPDIF ? "spdif" : "speaker",
            key -> "spdif".equals(key) ? "S/PDIF" : (vi ? "Loa TV" : "TV Speakers"),
            null, key -> {
                int target = "speaker".equals(key) ? SPEAKER : "spdif".equals(key) ? SPDIF : -1;
                if (apply(activity, target)) {
                    CrystalSoundEngine.playSelect(activity);
                    Toast.makeText(activity, target == SPDIF ? "S/PDIF" : (vi ? "Loa TV" : "TV Speakers"),
                        Toast.LENGTH_SHORT).show();
                } else {
                    openSettings(activity, vi);
                }
            }, true);
    }

    static boolean apply(Context context, int target) {
        if (target != SPEAKER && target != SPDIF) return false;
        int previous = current(context);
        if (previous < 0) return false;
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return false;
        Method wired;
        try { wired = wiredMethod(); }
        catch (ReflectiveOperationException | RuntimeException unavailable) { return false; }
        try {
            if (!Settings.Global.putInt(context.getContentResolver(), SETTING, target)) return false;
            wired.invoke(audio, DEVICE_OUT_SPDIF, target == SPDIF ? 1 : 0, "", "Spdif");
            if (current(context) != target) throw new IllegalStateException("Audio output readback changed");
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            try {
                Settings.Global.putInt(context.getContentResolver(), SETTING, previous);
                wired.invoke(audio, DEVICE_OUT_SPDIF, previous == SPDIF ? 1 : 0, "", "Spdif");
            } catch (ReflectiveOperationException | RuntimeException restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            Log.w("LiquidAudioOutput", "Cannot change audio output", failure);
            return false;
        }
    }

    private static void openSettings(Activity activity, boolean vi) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings"));
            intent.putExtra("SUB_TYPE", "SPEAKER_SRC");
            activity.startActivity(intent);
        } catch (RuntimeException unavailable) {
            try { activity.startActivity(new Intent(Settings.ACTION_SOUND_SETTINGS)); }
            catch (RuntimeException missing) {
                Toast.makeText(activity, vi ? "Không mở được cài đặt âm thanh" : "Cannot open audio settings",
                    Toast.LENGTH_SHORT).show();
            }
        }
    }
}
