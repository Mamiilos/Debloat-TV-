package io.freemytv;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/**
 * The screensaver as an activity, for firmware that has no dream manager at all: Xiaomi's Chinese build (MIUI TV)
 * leaves out the "dreams" system service, so `screensaver_components` is never read and {@link Screensaver} can never
 * start. There the home screen opens this one when the TV says it has gone idle (HomeActivity). Any key closes it.
 */
public class ScreensaverActivity extends Activity {
    private DreamView view;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Watchdog.install(this);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new DreamView(this);
        setContentView(view);
        getWindow().setBackgroundDrawable(null);
    }

    /** The TV calls its screensaver off again the same way it asked for it: on anything else that wakes the screen. */
    private static final String EXIT_SAVER = "com.duokan.intent.action.exit.screensaver";
    private final BroadcastReceiver exit = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            finish();
        }
    };
    private boolean listening;

    @Override protected void onStart() {
        super.onStart();
        if (!listening) {
            registerReceiver(exit, new IntentFilter(EXIT_SAVER));
            listening = true;
        }
        if (view != null) view.start();
    }

    @Override protected void onStop() {
        if (listening) {
            unregisterReceiver(exit);
            listening = false;
        }
        if (view != null) view.stop();
        super.onStop();
    }

    /** Any key gives the TV back, as the dream does; the key itself is swallowed so it doesn't reach the home screen. */
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }
}
