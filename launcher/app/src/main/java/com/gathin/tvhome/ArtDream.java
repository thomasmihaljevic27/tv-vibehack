package com.gathin.tvhome;

import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.View;

import java.util.Random;

/**
 * Screensaver in the spirit of Google TV's ambient mode: the launcher's background art full screen, a new
 * painting every 90 seconds with its slow drift, the title for a few seconds, and a soft clock in the corner
 * that shifts a little every minute so nothing burns in.
 *
 * Picking it as the screensaver is its own opt-in to the art (it shows paintings even with Background art off
 * on the home screen); offline it cycles the paintings already cached. Not in the fire build: Fire OS
 * force-stops a screensaver's app when it ends, which would stop HomeGuard too.
 */
public class ArtDream extends DreamService {

    private static final long SLIDE_MS = 90_000L;
    private static final long NUDGE_MS = 60_000L;
    /** How far (dp) the clock may wander from its corner. */
    private static final float NUDGE_DP = 14f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private ArtSlideshow art;
    private View clock;
    private float density;

    private final Runnable nudge = new Runnable() {
        @Override
        public void run() {
            float r = NUDGE_DP * density;
            clock.animate()
                    .translationX((random.nextFloat() * 2 - 1) * r)
                    .translationY((random.nextFloat() * 2 - 1) * r)
                    .setDuration(4000).start();
            handler.postDelayed(this, NUDGE_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(true);
        setContentView(R.layout.dream_art);
        density = getResources().getDisplayMetrics().density;
        clock = findViewById(R.id.dreamClock);
        View scrim = findViewById(R.id.artScrim);
        art = new ArtSlideshow(this, findViewById(R.id.artLayer), findViewById(R.id.artA), findViewById(R.id.artB),
                scrim, findViewById(R.id.artCaption), SLIDE_MS, true);
        // Lighter than the home screen's scrim: only the bottom darkens, under the clock and the title.
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xB3000000, 0x40000000, 0x00000000, 0x00000000}));
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        art.requestCaption();
        art.start();
        clock.setAlpha(0f);
        clock.animate().alpha(1f).setDuration(1500).start();
        handler.postDelayed(nudge, NUDGE_MS);
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacks(nudge);
        art.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacks(nudge);
        if (art != null) art.shutdown();
        super.onDetachedFromWindow();
    }
}
