package local.launcherfix;

import android.os.Looper;
import android.os.SystemClock;
import java.lang.reflect.Field;

public final class DockResumeCheck {
    private static int checks;

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private static void validWindow(DockStats.Sampler sampler) {
        sampler.appTime = Math.max(0L, SystemClock.uptimeMillis() - 1000L);
        sampler.appCpu = android.os.Process.getElapsedCpuTime();
    }

    private static void normalSample(DockStats.Sampler sampler, String name) {
        validWindow(sampler);
        int value = sampler.cpu();
        check(value >= 0 && value <= 100, name + " produces a bounded CPU percentage");
        check(!sampler.cpuWarmup, name + " is not warmup");
    }

    public static void main(String[] args) throws Exception {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        Field source = DockStats.class.getDeclaredField("cpuSource");
        source.setAccessible(true);
        int originalSource = source.getInt(null);
        try {
            source.setInt(null, 2);
            check(DockStats.cpuSource() == 2, "force the real app CPU source without relying on /proc permissions");

            DockStats.Sampler sampler = new DockStats.Sampler();
            check(sampler.appTime == -1L && sampler.appCpu == -1L, "new sampler has no baseline");
            check(sampler.cpu() == -1, "first successful read has no percentage yet");
            check(sampler.cpuWarmup, "first successful read is marked warmup");
            check(sampler.appTime >= 0L && sampler.appCpu >= 0L, "warmup stores a real baseline");
            check(DockStats.cpuSource() == 2, "app CPU source remains selected");

            normalSample(sampler, "second read");
            check(sampler.appTime >= 0L && sampler.appCpu >= 0L, "normal read updates the baseline");

            sampler.appTime = Long.MAX_VALUE;
            sampler.appCpu = android.os.Process.getElapsedCpuTime();
            check(sampler.cpu() == -1, "future baseline rejects a nonpositive elapsed window");
            check(!sampler.cpuWarmup, "invalid elapsed window is unavailable rather than warmup");
            check(sampler.appTime >= 0L && sampler.appTime < Long.MAX_VALUE,
                "invalid elapsed window replaces the bad time baseline");
            check(sampler.appCpu >= 0L, "invalid elapsed window keeps a successful current CPU baseline");
            normalSample(sampler, "recovery after invalid elapsed window");

            sampler.appTime = Math.max(0L, SystemClock.uptimeMillis() - 1000L);
            sampler.appCpu = Long.MAX_VALUE;
            check(sampler.cpu() == -1, "decreasing CPU counter is rejected");
            check(!sampler.cpuWarmup, "counter regression is unavailable rather than warmup");
            check(sampler.appCpu >= 0L && sampler.appCpu < Long.MAX_VALUE,
                "counter regression replaces the bad CPU baseline");
            normalSample(sampler, "recovery after counter regression");

            sampler.appTime = sampler.appCpu = -1L;
            check(sampler.cpu() == -1, "reset sampler first establishes a replacement baseline");
            check(sampler.cpuWarmup, "replacement baseline is warmup even after a previous invalid result");
            normalSample(sampler, "read after replacement baseline");

            DockStats.Sampler resumed = new DockStats.Sampler();
            check(resumed.cpu() == -1, "a focus-resume sampler also needs a fresh baseline");
            check(resumed.cpuWarmup, "focus-resume baseline can retain the previously displayed CPU");
            normalSample(resumed, "focus-resume second read");

            System.out.println("DockResumeCheck PASS " + checks + " checks");
        } finally {
            source.setInt(null, originalSource);
        }
    }
}
