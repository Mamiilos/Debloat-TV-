package io.freemytv;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.Arrays;

/**
 * Fire TV: the Home button opens Free My TV. Amazon keeps the Home role to its own launcher (priority 950), so when
 * Amazon's home screen comes to the front, Free My TV's opens over it (what Home on Fire does). It runs in
 * RemoteKeyService (:keys) on window changes only, and needs that service on: Free My TV turns it on by itself once
 * WRITE_SECURE_SETTINGS was granted over adb (pm grant io.freemytv android.permission.WRITE_SECURE_SETTINGS).
 */
final class FireHome {
    private FireHome() {}

    private static final String LAUNCHER = "com.amazon.tv.launcher";
    private static final String HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";

    /** A window came to the front: Amazon's home screen hands over to ours (singleTask: a second time is a reset). */
    static void window(Context c, CharSequence pkg, CharSequence cls) {
        if (!HomeMode.AMAZON || pkg == null || cls == null) return;
        if (!LAUNCHER.contentEquals(pkg) || !HOME.contentEquals(cls)) return;
        try {
            c.startActivity(new Intent(c, FireHomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            AppLog.w("Fire TV Home: " + e.getMessage());
        }
    }

    /**
     * The service started. Right after the TV starts, Amazon's home screen is already in front, with no window change
     * to come: ours opens over it. Later, a service restart (the system stopped :keys) must not open the home screen
     * over the app being watched.
     */
    static void connected(Context c) {
        if (!HomeMode.AMAZON || SystemClock.elapsedRealtime() > 3 * 60_000L) return;
        window(c, LAUNCHER, HOME);
    }

    /**
     * Turns RemoteKeyService on next to the services already on: Android drops it when this app is updated, so the
     * home screen checks it each time it opens. Without the permission, nothing (the Home button stays Amazon's).
     */
    static void ensureService(Context c) {
        if (!HomeMode.AMAZON || c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                != PackageManager.PERMISSION_GRANTED) return;
        String me = c.getPackageName() + "/" + RemoteKeyService.class.getName();
        ContentResolver cr = c.getContentResolver();
        try {
            String on = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (on != null && Arrays.asList(on.split(":")).contains(me)) return;
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    on == null || on.isEmpty() || on.equals("null") ? me : on + ":" + me);
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            AppLog.w("Fire TV Home: key service on");
        } catch (RuntimeException e) {
            AppLog.w("Fire TV Home: " + e.getMessage());
        }
    }
}
