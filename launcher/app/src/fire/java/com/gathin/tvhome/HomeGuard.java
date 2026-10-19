/*
 * Adapted from dumbtv's launcher/src/tv/dumbtv/home/Guard.java
 * (https://github.com/humblemuzzu/dumbtv, commit 38bc6d2) for this launcher's fire flavour.
 *
 * MIT License
 *
 * Copyright (c) 2026 dumbtv contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fire TV only: makes HOME open this launcher instead of Amazon's.
 *
 * Fire OS keeps HOME bound to Amazon's launcher (com.amazon.tv.launcher, which stays enabled), ignores
 * {@code cmd package set-home-activity}, and handles the HOME key before any accessibility key filter
 * sees it, so GearKeyService's approach can't work here. This service waits for Amazon's home window
 * and replaces it. It asks for window-state changes only and reads no screen content.
 *
 * A plain startActivity right after a HOME press is held back by Android's five-second app-switch delay.
 * An alarm-fired PendingIntent is sent by system_server, which is exempt, so {@link #launchHome} goes
 * through an alarm. That alarm only fires at once with the global {@code alarm_manager_constants}
 * {@code min_futurity=0}, which install-fire.ps1 sets and restore-fire.ps1 removes.
 *
 * Amazon's home is covered with a black overlay until ours has drawn, so it never shows. Fire OS 8 adds a
 * step dumbtv's Fire OS 7 didn't need: Android 11 lets that alarm bring our activity to the front only
 * while this app has a window on screen, so the alarm is set once the overlay has drawn.
 *
 * The Appstore is a tab of Amazon's home on Fire OS 8, opened through com.amazon.venezia, so Amazon's home
 * appearing right after the Appstore's window is left on screen. HOME pressed there changes nothing this
 * service can see, so while the store is open it filters the Back key alone: Back on the store brings our
 * home back. Any other app, or our home, ends that.
 *
 * Holding HOME still opens Fire OS's quick menu: that is another window, which this service leaves alone.
 */
public final class HomeGuard extends AccessibilityService {
    private static final String TAG = "HomeGuard";
    private static final String AMAZON = "com.amazon.tv.launcher";
    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    private static final String STORE = "com.amazon.venezia";
    /** The Appstore's entry opens Amazon's home this soon after its own window (about 0.1 s). */
    private static final long STORE_MS = 2000;
    /** Uncover anyway after this long, in case our home never draws (a cold start takes about 2 s). */
    private static final long COVER_MAX_MS = 4000;
    /** Launch anyway after this long if the overlay's frame never reports back. */
    private static final long LAUNCH_MAX_MS = 150;
    /** If our home hasn't resumed this long after the launch, launch once more (the cover is up by then). */
    private static final long RETRY_MS = 1200;
    /** Amazon's home can report its window more than once per HOME press. */
    private static final long REPEAT_MS = 1000;
    /** Once a frame is handed to the display, give it this long to reach the screen. */
    private static final long SETTLE_MS = 50;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable uncover = this::uncover;
    private final Runnable launchNow = this::launchNow;
    private final Runnable retry = () -> launchHome(this);
    private View cover;
    /** Event time (uptime) of the Amazon home window that last triggered a launch. */
    private long lastLaunch;
    /** Uptime of our home's last resume; Amazon events from before it are stale. */
    private long homeResumedAt;
    /** Uptimes of this takeover's steps, for the log line: Amazon's window, launch, our resume. */
    private long seenAt, launchedAt, resumedAt;
    private Application.ActivityLifecycleCallbacks homeWatch;
    /** Event time of the Appstore's last window; whether the store is open; whether Amazon's home is in front. */
    private long storeSeenAt = Long.MIN_VALUE / 2;
    private boolean inStore, onAmazonHome;

    static void launchHome(Context c) {
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        alarms.setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime(), launch(c));
    }

    /** Drops a queued launch, so it cannot pull the user back after they opened an app. */
    static void cancelLaunch(Context c) {
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        alarms.cancel(launch(c));
    }

    private static PendingIntent launch(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClass(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                ActivityOptions.makeCustomAnimation(c, 0, 0).toBundle());
    }

    @Override
    protected void onServiceConnected() {
        // The overlay window exists from the start; a GONE root keeps it without a surface, so it costs
        // nothing while an app plays.
        View view = new View(this);
        view.setBackgroundColor(Color.BLACK);
        view.setVisibility(View.GONE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        lp.windowAnimations = 0;
        ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(view, lp);
        cover = view;

        // Our home runs in this process, so its lifecycle says when it is back. The launcher itself
        // needs no Fire code: this watch is the only link.
        homeWatch = new HomeWatch();
        getApplication().registerActivityLifecycleCallbacks(homeWatch);
        Log.i(TAG, "connected");
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(uncover);
        handler.removeCallbacks(launchNow);
        handler.removeCallbacks(retry);
        if (homeWatch != null) getApplication().unregisterActivityLifecycleCallbacks(homeWatch);
        if (cover != null) ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(cover);
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName(), cls = e.getClassName();
        if (pkg == null) return;
        long at = e.getEventTime();
        if (!AMAZON.contentEquals(pkg)) {
            if ("com.android.systemui".contentEquals(pkg) || "android".contentEquals(pkg)) return;
            if (getPackageName().contentEquals(pkg)) return;
            onAmazonHome = false;
            // Another app took the screen: it wins over a launch still on its way.
            handler.removeCallbacks(launchNow);
            handler.removeCallbacks(retry);
            cancelLaunch(this);
            uncover();
            // The Appstore's own pages keep the store open; anything else closes it.
            if (STORE.contentEquals(pkg)) storeSeenAt = at;
            else leaveStore();
            return;
        }
        // Amazon's settings screens and quick menu live in the same package and stay.
        onAmazonHome = cls != null && AMAZON_HOME.contentEquals(cls);
        if (!onAmazonHome) return;
        if (at - storeSeenAt < STORE_MS) enterStore();
        if (inStore) return;
        // Events queue up while our home is busy drawing, so judge them by when they were sent.
        if (at < homeResumedAt || at - lastLaunch < REPEAT_MS) return;
        lastLaunch = at;
        takeOver();
    }

    /** Covers Amazon's home and brings ours back. */
    private void takeOver() {
        seenAt = SystemClock.uptimeMillis();
        boolean covered = cover != null && cover.getVisibility() == View.VISIBLE;
        cover();
        if (covered || cover == null) {
            launchNow();
        } else {
            // Launch once the overlay is on screen (see the class comment).
            cover.getViewTreeObserver().registerFrameCommitCallback(() -> handler.postDelayed(launchNow, SETTLE_MS));
            handler.postDelayed(launchNow, LAUNCH_MAX_MS);
        }
    }

    /** Back on Amazon's home while the Appstore is open: our home. Other keys pass. */
    @Override
    protected boolean onKeyEvent(KeyEvent k) {
        if (!inStore || !onAmazonHome || k.getKeyCode() != KeyEvent.KEYCODE_BACK) return false;
        if (k.getAction() == KeyEvent.ACTION_UP) {
            handler.post(() -> {
                leaveStore();
                lastLaunch = SystemClock.uptimeMillis();
                takeOver();
            });
        }
        return true;
    }

    private void enterStore() {
        if (inStore) return;
        inStore = true;
        filterKeys(true);
    }

    private void leaveStore() {
        if (!inStore) return;
        inStore = false;
        filterKeys(false);
    }

    /** Key events reach onKeyEvent only while the store is open. */
    private void filterKeys(boolean on) {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        if (on) info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        else info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);
    }

    private void launchNow() {
        handler.removeCallbacks(launchNow);
        launchedAt = SystemClock.uptimeMillis();
        launchHome(this);
        handler.removeCallbacks(retry);
        handler.postDelayed(retry, RETRY_MS);
    }

    private void cover() {
        if (cover == null) return;
        cover.setVisibility(View.VISIBLE);
        handler.removeCallbacks(uncover);
        handler.postDelayed(uncover, COVER_MAX_MS);
    }

    private void uncover() {
        handler.removeCallbacks(uncover);
        if (cover == null || cover.getVisibility() != View.VISIBLE) return;
        cover.setVisibility(View.GONE);
        long now = SystemClock.uptimeMillis();
        Log.i(TAG, "HOME: Amazon's home covered " + (now - seenAt) + " ms; launched after " + (launchedAt - seenAt)
                + " ms, ours resumed after " + (resumedAt > seenAt ? (resumedAt - seenAt) + " ms" : "-"));
    }

    /** Our home resumed: drop any queued launch, and uncover once it has drawn a frame. */
    private void homeResumed(Activity home) {
        homeResumedAt = resumedAt = SystemClock.uptimeMillis();
        leaveStore();
        handler.removeCallbacks(launchNow);
        handler.removeCallbacks(retry);
        cancelLaunch(this);
        if (cover == null || cover.getVisibility() != View.VISIBLE) return;
        View decor = home.getWindow().getDecorView();
        // post: right after onResume the window may not be attached yet.
        decor.post(() -> {
            decor.getViewTreeObserver().registerFrameCommitCallback(() -> handler.postDelayed(uncover, SETTLE_MS));
            decor.invalidate();
        });
    }

    @Override
    public void onInterrupt() {
    }

    private final class HomeWatch implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityResumed(Activity a) {
            if (a instanceof MainActivity) homeResumed(a);
        }

        @Override
        public void onActivityCreated(Activity a, Bundle state) {
        }

        @Override
        public void onActivityStarted(Activity a) {
        }

        @Override
        public void onActivityPaused(Activity a) {
        }

        @Override
        public void onActivityStopped(Activity a) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity a, Bundle state) {
        }

        @Override
        public void onActivityDestroyed(Activity a) {
        }
    }
}
