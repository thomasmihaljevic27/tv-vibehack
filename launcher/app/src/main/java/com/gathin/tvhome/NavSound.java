package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;

/**
 * A soft, airy "boop" when focus moves, like the navigation sound on Sony TVs: res/raw/nav_move.wav is a 400 Hz
 * near-pure sine with a slow swell and a short, dark reverb, synthesised to match measurements of that sound (it is
 * not sampled). On by default; switched in Home screen options. SoundPool keeps it low-latency and cheap.
 */
final class NavSound {

    private static final String PREF = "nav_sounds";
    /** Holding an arrow repeats about every 50 ms; sounding on every repeat turns into a buzz. */
    private static final long MIN_GAP_MS = 45L;

    private final SharedPreferences prefs;
    private final SoundPool pool;
    private final int moveSound;
    private boolean loaded;
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
        pool.setOnLoadCompleteListener((p, id, status) -> loaded = status == 0);
        moveSound = pool.load(ctx, R.raw.nav_move, 1);
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
        if (!loaded || !isEnabled() || now < quietUntil || now - last < MIN_GAP_MS) return;
        last = now;
        pool.play(moveSound, 1f, 1f, 1, 0, 1f);
    }

    void release() {
        pool.release();
    }
}
