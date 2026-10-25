package io.freemytv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Where tools/homeassistant.py writes the Home Assistant settings (address, token, entities):
 * `adb exec-in content write --uri content://io.freemytv.ha/config`, and `content delete` takes them away. Only adb's
 * shell (or root) may: the token never goes through the screen, the APK or Prefs.
 */
public class HaInbox extends ContentProvider {
    /** adb's shell (Process.SHELL_UID, which the SDK only names from Android 10). */
    private static final int SHELL_UID = 2000;

    @Override public boolean onCreate() {
        return true;
    }

    private static void checkCaller(Uri uri) throws FileNotFoundException {
        int caller = Binder.getCallingUid();
        if (caller != SHELL_UID && caller != 0) throw new SecurityException("only adb sends the Home Assistant settings");
        if (!"config".equals(uri.getLastPathSegment())) throw new FileNotFoundException("content://io.freemytv.ha/config");
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        checkCaller(uri);
        if (!mode.contains("w")) throw new FileNotFoundException("write only");
        File dir = getContext().getFilesDir();
        // Written under a hidden name and renamed once whole: a send cut short leaves the old settings. Whole means it
        // parses: through `adb exec-in` the close comes as "Remote side is dead" even when everything arrived.
        File part = new File(dir, "." + HomeAssistant.FILE), done = new File(dir, HomeAssistant.FILE);
        try {
            return ParcelFileDescriptor.open(part, ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE, new Handler(Looper.getMainLooper()), e -> {
                        boolean ok = whole(part) && part.renameTo(done);
                        if (!ok) part.delete();
                        AppLog.w("homeassistant: settings " + (ok ? "received" : "not received"));
                    });
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    private static boolean whole(File f) {
        try {
            new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        try {
            checkCaller(uri);
        } catch (FileNotFoundException e) {
            return 0;
        }
        return new File(getContext().getFilesDir(), HomeAssistant.FILE).delete() ? 1 : 0;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
