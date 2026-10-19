package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;

import java.util.HashSet;
import java.util.Set;

/**
 * The launcher's UI sounds, in the style of Sony's TV menus, each synthesised to match measurements of that sound
 * (none is sampled):
 * - move (res/raw/nav_move.wav): a soft, airy "boop" when focus moves; a 400 Hz near-pure sine with a slow swell
 *   and a short, dark reverb.
 * - select (nav_select.wav): opening an app or a screen; a short 580 Hz blip with 500 Hz beating under it.
 * - home (nav_home.wav): coming back to the home screen; a long, low bloom at 248 Hz with 300 Hz above.
 * On by default; switched in Home screen options. SoundPool keeps them low-latency and cheap.
 */
final class NavSound {

    private static final String PREF = "nav_sounds";
    /** Holding an arrow repeats about every 50 ms; sounding on every repeat turns into a buzz. */
    private static final long MIN_GAP_MS = 45L;

    private final SharedPreferences prefs;
    private final SoundPool pool;
    private final int moveSound, selectSound, homeSound;
    private final Set<Integer> loaded = new HashSet<>();
    private long quietUntil, last;

    NavSound(Context ctx) {
        prefs = ctx.getSharedPreferences("layout", Context.MODE_PRIVATE);
        pool = new SoundPool.Builder()
                .setMaxStreams(3)   // the reverb tail outlasts a quick press; let a few overlap
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        pool.setOnLoadCompleteListener((p, id, status) -> {
            if (status == 0) loaded.add(id);
        });
        moveSound = pool.load(ctx, R.raw.nav_move, 1);
        selectSound = pool.load(ctx, R.raw.nav_select, 1);
        homeSound = pool.load(ctx, R.raw.nav_home, 1);
    }

    boolean isEnabled() {
        return prefs.getBoolean(PREF, true);
    }

    void setEnabled(boolean on) {
        prefs.edit().putBoolean(PREF, on).apply();
    }

    /** Stay silent for a moment: focus the launcher restores by itself (back from an app) isn't a move. */
    void quiet(long ms) {
        quietUntil = SystemClock.uptimeMillis() + ms;
    }

    /** A focus move the viewer made. */
    void move() {
        long now = SystemClock.uptimeMillis();
        if (now < quietUntil || now - last < MIN_GAP_MS) return;
        last = now;
        play(moveSound);
    }

    /** An app or a screen is being opened. */
    void select() {
        play(selectSound);
    }

    /** Back on the home screen from somewhere else. */
    void home() {
        play(homeSound);
    }

    private void play(int id) {
        if (isEnabled() && loaded.contains(id)) pool.play(id, 1f, 1f, 1, 0, 1f);
    }

    void release() {
        pool.release();
    }
}
