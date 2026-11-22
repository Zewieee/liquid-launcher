package local.launcherfix;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;

/** Only the user-started ADB helper can publish a reader; no files or notifications are exposed. */
public final class SpotifyBridgeProvider extends ContentProvider {
    private static volatile IBinder reader;
    private final Binder lifetime = new Binder();
    static IBinder reader() {
        IBinder value = reader;
        return value != null && value.isBinderAlive() ? value : null;
    }
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String argument, Bundle extras) {
        if (Binder.getCallingUid() != 2000) throw new SecurityException("Only authorized ADB shell can connect");
        if (!"connect".equals(method) || extras == null || extras.getBinder("reader") == null)
            throw new IllegalArgumentException("Missing media reader");
        reader = extras.getBinder("reader");
        Bundle result = new Bundle(); result.putBinder("owner", lifetime); return result;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
