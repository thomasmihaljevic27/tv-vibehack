package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Keeps the remote's gear button working once the stock Google TV launcher is disabled.
 *
 * On Google TV remotes the gear sends KEYCODE_NOTIFICATION, which the system turns into "toggle the
 * notification handler panel": Google TV's dashboard, which lives in launcherx. With launcherx disabled
 * nothing answers, and the system only hands that panel to system apps, so this launcher can't simply
 * claim it. An accessibility service may filter key events instead: on the home screen the gear toggles
 * the launcher's top menu, anywhere else it opens the TV's Settings.
 *
 * It reads nothing but these two keys. Enabled over adb by build-and-install (secure setting
 * enabled_accessibility_services), or in Settings → Accessibility.
 */
public class GearKeyService extends AccessibilityService {

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_NOTIFICATION && code != KeyEvent.KEYCODE_SETTINGS) return false;
        // Act on release only, but swallow both halves so nothing else half-handles the press.
        if (event.getAction() == KeyEvent.ACTION_UP) main.post(this::onGear);
        return true;
    }

    private void onGear() {
        MainActivity home = MainActivity.inFront();
        if (home != null) {
            home.toggleTopMenu();
            return;
        }
        Intent settings = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(settings);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Key filtering only; no events are requested.
    }

    @Override
    public void onInterrupt() {
    }
}
