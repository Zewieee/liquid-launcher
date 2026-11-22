package local.launcherfix;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Multi-source direct fallback for public MediaBrowserService players when the shell bridge is absent.
 * Connects up to 4 prioritized music players concurrently, surfaces all active sources in the
 * source picker, honors manual source selection, and cools down refused non-player services.
 */
final class GenericMediaBrowser {
    private static final String MEDIA_BROWSER_ACTION = "android.media.browse.MediaBrowserService";
    private static final String BIND_MEDIA_BROWSER = "android.permission.BIND_MEDIA_BROWSER_SERVICE";
    private static final long CONNECT_TIMEOUT_MS = 4500L;
    private static final long FAIL_COOLDOWN_MS = 300_000L;
    private static final int MAX_CANDIDATES = 4;

    private static volatile String lastConnectedPackage = "";
    private static final Map<String, Long> FAILED_UNTIL = Collections.synchronizedMap(new HashMap<>());

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private final ArrayList<Slot> slots = new ArrayList<>();
    private final Map<String, String> sessionIdsByComponent = new HashMap<>();
    private volatile List<Entry> liveEntries = Collections.emptyList();
    private long generation;
    private boolean wanted;

    private static final class Entry {
        final MediaController controller;
        final String id, packageName, serviceName, label;
        MediaController.Callback callback;
        Entry(MediaController controller, String id, String packageName, String serviceName, String label) {
            this.controller = controller; this.id = id;
            this.packageName = packageName; this.serviceName = serviceName; this.label = label;
        }
    }

    private static final class Slot {
        final String key;
        final ServiceInfo service;
        MediaBrowser browser;
        Entry entry;
        Runnable timeout;
        boolean connecting;
        Slot(String key, ServiceInfo service) { this.key = key; this.service = service; }
    }

    GenericMediaBrowser(Context applicationContext, Runnable changed) {
        context = applicationContext.getApplicationContext();
        this.changed = changed;
    }

    void start() {
        requireMain();
        wanted = true;
        if (!slots.isEmpty()) return;
        List<ServiceInfo> services = discover(context);
        if (services.isEmpty()) {
            changed.run();
            return;
        }
        final long attempt = ++generation;
        for (ServiceInfo service : services) {
            connectSlot(service, attempt);
        }
        if (slots.isEmpty()) changed.run();
    }

    void stop() {
        requireMain();
        wanted = false;
        generation++;
        disconnectAll();
    }

    boolean isConnecting() {
        requireMain();
        if (!wanted || !liveEntries.isEmpty()) return false;
        for (Slot slot : slots) {
            if (slot.connecting) return true;
        }
        return false;
    }

    boolean owns(String id) {
        if (id == null || id.isEmpty()) return false;
        List<Entry> current = liveEntries;
        for (int i = 0; i < current.size(); i++) {
            if (id.equals(current.get(i).id)) return true;
        }
        return false;
    }

    SpotifyMetadata.Response read(String knownRevision, int maxSide) {
        return read(knownRevision, maxSide, "");
    }

    SpotifyMetadata.Response read(String knownRevision, int maxSide, String preferredId) {
        List<Entry> entries = liveEntries;
        if (entries.isEmpty()) return null;
        ArrayList<Entry> ready = new ArrayList<>(entries.size());
        ArrayList<MediaMetadata> metas = new ArrayList<>(entries.size());
        ArrayList<PlaybackState> states = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            try {
                MediaMetadata m = e.controller.getMetadata();
                PlaybackState s = e.controller.getPlaybackState();
                if (m == null || s == null) continue;
                CharSequence t = m.getText(MediaMetadata.METADATA_KEY_TITLE);
                if (t == null || t.length() == 0) t = m.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
                if (t == null || t.length() == 0) continue;
                ready.add(e);
                metas.add(m);
                states.add(s);
            } catch (Exception ignored) {}
        }
        if (ready.isEmpty()) return null;

        int n = ready.size();
        String[] ids = new String[n];
        int[] stateCodes = new int[n];
        SpotifyMetadata.Source[] choices = new SpotifyMetadata.Source[n];
        for (int i = 0; i < n; i++) {
            Entry e = ready.get(i);
            ids[i] = e.id;
            stateCodes[i] = states.get(i).getState();
            choices[i] = new SpotifyMetadata.Source(e.id, e.packageName, e.label, stateCodes[i]);
        }
        int chosenIdx = MediaSources.choose(ids, stateCodes, preferredId == null ? "" : preferredId);
        if (chosenIdx < 0 || chosenIdx >= n) return null;
        Entry chosen = ready.get(chosenIdx);
        try {
            SpotifyMetadata.Response value = MochyMediaBrowser.snapshot(
                metas.get(chosenIdx), states.get(chosenIdx), chosen.id,
                knownRevision, maxSide, chosen.packageName, chosen.label);
            if (value != null) {
                value.sources = choices;
                lastConnectedPackage = chosen.packageName;
                FAILED_UNTIL.remove(componentKey(chosen.packageName, chosen.serviceName));
                return value;
            }
        } catch (Exception ignored) {}
        return null;
    }

    boolean control(String id, int key) {
        if (id == null || id.isEmpty()) return false;
        Entry entry = null;
        List<Entry> entries = liveEntries;
        for (int i = 0; i < entries.size(); i++) {
            if (id.equals(entries.get(i).id)) { entry = entries.get(i); break; }
        }
        if (entry == null) return false;
        try {
            PlaybackState state = entry.controller.getPlaybackState();
            if (!MochyMediaBrowser.canControl(entry.id, id, state, key)) return false;
            MediaController.TransportControls controls = entry.controller.getTransportControls();
            if (key == 87) controls.skipToNext();
            else if (key == 88) controls.skipToPrevious();
            else if (key == 89 || key == 90) {
                MediaMetadata meta = entry.controller.getMetadata();
                long duration = meta == null ? 0L : Math.max(0L, meta.getLong(MediaMetadata.METADATA_KEY_DURATION));
                long target = MediaSources.seekTarget(state.getPosition(), state.getLastPositionUpdateTime(),
                    state.getPlaybackSpeed(), state.getState(), duration, SystemClock.elapsedRealtime(), key);
                if (target < 0L) return false;
                controls.seekTo(target);
            }
            else if (state.getState() == PlaybackState.STATE_PLAYING) controls.pause();
            else controls.play();
            return true;
        } catch (Exception ignored) { return false; }
    }

    private void connectSlot(ServiceInfo service, final long attempt) {
        final String key = componentKey(service.packageName, service.name);
        final Slot slot = new Slot(key, service);
        slot.connecting = true;
        try {
            ComponentName component = new ComponentName(service.packageName, service.name);
            slot.browser = new MediaBrowser(context, component, new MediaBrowser.ConnectionCallback() {
                @Override public void onConnected() {
                    if (!valid(attempt) || !slots.contains(slot)) return;
                    slot.connecting = false;
                    if (slot.timeout != null) { main.removeCallbacks(slot.timeout); slot.timeout = null; }
                    try {
                        MediaController controller = new MediaController(context, slot.browser.getSessionToken());
                        if (!service.packageName.equals(controller.getPackageName())) throw new IllegalStateException("package mismatch");
                        String id = sessionIdsByComponent.get(key);
                        if (id == null || !MediaSources.validId(id)) {
                            id = UUID.randomUUID().toString().replace("-", "");
                            sessionIdsByComponent.put(key, id);
                        }
                        String label = appLabel(context, service.packageName);
                        final Entry entry = new Entry(controller, id, service.packageName, service.name, label);
                        entry.callback = new MediaController.Callback() {
                            @Override public void onMetadataChanged(MediaMetadata value) { if (valid(attempt)) changed.run(); }
                            @Override public void onPlaybackStateChanged(PlaybackState value) { if (valid(attempt)) changed.run(); }
                            @Override public void onSessionDestroyed() { if (valid(attempt) && slot.entry == entry) dropSlot(slot, false); }
                        };
                        controller.registerCallback(entry.callback, main);
                        slot.entry = entry;
                        FAILED_UNTIL.remove(key);
                        if (controller.getMetadata() != null) lastConnectedPackage = service.packageName;
                        publishEntries();
                        changed.run();
                    } catch (Exception unavailable) {
                        dropSlot(slot, true);
                    }
                }
                @Override public void onConnectionSuspended() {
                    if (valid(attempt) && slots.contains(slot)) dropSlot(slot, false);
                }
                @Override public void onConnectionFailed() {
                    if (valid(attempt) && slots.contains(slot)) dropSlot(slot, true);
                }
            }, new Bundle());
            slot.timeout = () -> {
                if (valid(attempt) && slots.contains(slot) && slot.entry == null) {
                    dropSlot(slot, true);
                }
            };
            slots.add(slot);
            main.postDelayed(slot.timeout, CONNECT_TIMEOUT_MS);
            slot.browser.connect();
        } catch (Exception unavailable) {
            markFailed(key);
            cleanupSlot(slot);
            slots.remove(slot);
        }
    }

    private void dropSlot(Slot slot, boolean coolDown) {
        if (coolDown) markFailed(slot.key);
        cleanupSlot(slot);
        slots.remove(slot);
        publishEntries();
        changed.run();
    }

    private void cleanupSlot(Slot slot) {
        slot.connecting = false;
        if (slot.timeout != null) { main.removeCallbacks(slot.timeout); slot.timeout = null; }
        Entry entry = slot.entry;
        slot.entry = null;
        if (entry != null) {
            try { entry.controller.unregisterCallback(entry.callback); } catch (Exception ignored) {}
        }
        MediaBrowser old = slot.browser;
        slot.browser = null;
        if (old != null) try { old.disconnect(); } catch (Exception ignored) {}
    }

    private void disconnectAll() {
        for (int i = 0; i < slots.size(); i++) cleanupSlot(slots.get(i));
        slots.clear();
        liveEntries = Collections.emptyList();
    }

    private void publishEntries() {
        ArrayList<Entry> next = new ArrayList<>(slots.size());
        for (int i = 0; i < slots.size(); i++) {
            Entry e = slots.get(i).entry;
            if (e != null) next.add(e);
        }
        liveEntries = next.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(next);
    }

    private static void markFailed(String key) {
        if (key == null || key.isEmpty()) return;
        FAILED_UNTIL.put(key, SystemClock.elapsedRealtime() + FAIL_COOLDOWN_MS);
    }

    private static String componentKey(String pkg, String cls) {
        return (pkg == null ? "" : pkg) + "/" + (cls == null ? "" : cls);
    }

    private boolean valid(long attempt) { return wanted && generation == attempt; }

    static int packagePriority(String pkg, String lastPkg, List<String> recent, boolean coolingDown) {
        if (coolingDown) return 4000;
        if (pkg != null && pkg.equals(lastPkg)) return 0;
        if (recent != null && pkg != null) {
            int idx = recent.indexOf(pkg);
            if (idx >= 0) return 100 + idx;
        }
        if (looksLikeMediaPlayer(pkg)) return 500;
        return 1000;
    }

    static boolean looksLikeMediaPlayer(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        String lower = pkg.toLowerCase(Locale.ROOT);
        if (lower.contains("file.explorer") || lower.contains("filemanager") || lower.contains("files")) return false;
        return lower.contains("music") || lower.contains("bitchord") || lower.contains("audio")
            || lower.contains("player") || lower.contains("spotify") || lower.contains("vlc")
            || lower.contains("kodi") || lower.contains("plex") || lower.contains("jellyfin")
            || lower.contains("symfonium") || lower.contains("musicolet") || lower.contains("poweramp")
            || lower.contains("tidal") || lower.contains("deezer") || lower.contains("soundcloud")
            || lower.contains("foobar") || lower.contains("aimp") || lower.contains("ytm");
    }

    private static List<ServiceInfo> discover(Context context) {
        ArrayList<ServiceInfo> fresh = new ArrayList<>();
        ArrayList<ServiceInfo> cooled = new ArrayList<>();
        long now = SystemClock.elapsedRealtime();
        Intent query = new Intent(MEDIA_BROWSER_ACTION);
        List<ResolveInfo> found = context.getPackageManager().queryIntentServices(query, PackageManager.MATCH_ALL);
        for (ResolveInfo item : found) {
            ServiceInfo service = item.serviceInfo;
            if (service == null || !service.exported || !MediaSources.validPackage(service.packageName)) continue;
            if (context.getPackageName().equals(service.packageName) || MochyMediaBrowser.PACKAGE.equals(service.packageName)) continue;
            if (service.permission != null && !BIND_MEDIA_BROWSER.equals(service.permission)) continue;
            Long blocked = FAILED_UNTIL.get(componentKey(service.packageName, service.name));
            if (blocked != null && now < blocked) cooled.add(service);
            else fresh.add(service);
        }
        final List<String> recent = RecentApps.list(context);
        final String last = lastConnectedPackage;
        final Set<String> cooledSet = new HashSet<>();
        for (ServiceInfo s : cooled) cooledSet.add(componentKey(s.packageName, s.name));
        List<ServiceInfo> chosen = !fresh.isEmpty() ? fresh : cooled;
        chosen.sort(Comparator
            .comparingInt((ServiceInfo s) -> packagePriority(s.packageName, last, recent, cooledSet.contains(componentKey(s.packageName, s.name))))
            .thenComparing(s -> s.packageName)
            .thenComparing(s -> s.name));
        return chosen.size() > MAX_CANDIDATES ? new ArrayList<>(chosen.subList(0, MAX_CANDIDATES)) : chosen;
    }

    private static String appLabel(Context context, String packageName) {
        try {
            CharSequence value = context.getPackageManager().getApplicationLabel(
                context.getPackageManager().getApplicationInfo(packageName, 0));
            if (value != null && value.length() > 0) return value.subSequence(0, Math.min(80, value.length())).toString();
        } catch (Exception ignored) {}
        return packageName.substring(0, Math.min(80, packageName.length()));
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Browser lifecycle requires main thread");
    }
}
