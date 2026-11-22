package local.launcherfix;

/** Small, bounded motion that always comes back to the original layout and crop. */
final class DepthMotionMath {
    static final long DURATION_MS = 320L;
    private DepthMotionMath() {}

    static int axis(int previous, int current, int columns, boolean singleRow) {
        if (previous < 0 || current < 0 || previous == current) return 0;
        return singleRow || previous / Math.max(1, columns) == current / Math.max(1, columns) ? 1 : 2;
    }

    static float offset(float from, float target, float progress) {
        if (progress <= 0f) return from;
        if (progress >= 1f) return 0f;
        if (progress < 0.34f) return from + (target - from) * ease(progress / 0.34f);
        return target * (1f - ease((progress - 0.34f) / 0.66f));
    }

    static float coverScale(float x, float y, int width, int height) {
        if (width <= 0 || height <= 0) return 1f;
        return 1f + 2.1f * Math.max(Math.abs(x) / width, Math.abs(y) / height);
    }

    private static float ease(float x) {
        return x * x * x * (x * (x * 6f - 15f) + 10f);
    }
}
