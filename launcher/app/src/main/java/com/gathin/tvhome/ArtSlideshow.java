package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional background art behind the top shelf: public-domain landscape paintings from the Art Institute of
 * Chicago's open API, cross-faded every 10 minutes with a barely visible drift, in the spirit of Google TV's ambient
 * mode. Off unless switched on (config.xml background_art, or Home Screen Options in the top menu); while off
 * the launcher never touches the network. Images are cached, so it keeps cycling what it has when offline.
 */
final class ArtSlideshow {

    // Public-domain paintings (artwork_type_id 1) matching "landscape"; parse() keeps pure scenery. Brackets are pre-encoded.
    private static final String SEARCH = "https://api.artic.edu/api/v1/artworks/search?q=landscape"
            + "&query%5Bbool%5D%5Bmust%5D%5B%5D%5Bterm%5D%5Bis_public_domain%5D=true"
            + "&query%5Bbool%5D%5Bmust%5D%5B%5D%5Bterm%5D%5Bartwork_type_id%5D=1"
            + "&fields=id,title,artist_title,image_id,thumbnail,subject_titles,term_titles&limit=100&page=";
    private static final String IMAGE = "https://www.artic.edu/iiif/2/%s/full/1686,/0/default.jpg";
    private static final String USER_AGENT = "tvhome-launcher (tv-vibehack)";
    private static final String PREF = "art_enabled";
    /** Time each painting stays up, counted only while it's on screen. */
    private static final long SLIDE_MS = 10L * 60 * 1000;
    /** Retry delay when nothing could be loaded at all (first run offline). */
    private static final long RETRY_MS = 60_000L;
    private static final long FADE_MS = 1_600L;
    /** How long the painting's title and artist stay up after it appears. */
    private static final long CAPTION_MS = 10_000L;
    private static final long LIST_MAX_AGE_MS = 24L * 60 * 60 * 1000;
    private static final int PAGES = 10;            // pick one of the first 1000 results per day
    private static final int CACHE_FILES = 30;
    /** End scale of the drift over a whole SLIDE_MS: about 0.06 px of movement per second, below notice. */
    private static final float DRIFT = 1.04f;
    /** The drift is stepped, not animated per frame: a 10-minute animator would redraw the screen nonstop. */
    private static final long DRIFT_STEP_MS = 1_000L;

    private static final class Art {
        final String imageId, title, artist;

        Art(String imageId, String title, String artist) {
            this.imageId = imageId;
            this.title = title;
            this.artist = artist;
        }

        String caption() {
            return artist.isEmpty() ? title : title + "  ·  " + artist;
        }
    }

    private final Context ctx;
    private final View layer;
    private final TextView caption;
    private final SharedPreferences prefs;
    private final File dir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final List<Art> queue = new ArrayList<>();   // io thread only
    private ImageView front, back;
    private boolean running, loading, hasImage;
    /** Time left on the current painting; kept across stop()/start() so leaving the dock doesn't reset it. */
    private long remainingMs = SLIDE_MS;
    private long dueAt;
    private float driftProgress;   // 0..1 over SLIDE_MS of on-screen time
    /**
     * Show the credit the next time the art is on screen. Set for a new painting and when the TV wakes
     * (requestCaption), not when coming back from an app, so the credit doesn't nag on every return.
     */
    private boolean captionPending;
    private final Runnable hideCaption = this::fadeCaptionOut;
    private final Runnable next = () -> {
        dueAt = 0;
        loadNext();
    };
    private final Runnable driftStep = new Runnable() {
        @Override
        public void run() {
            if (!running || driftProgress >= 1f) return;
            driftProgress = Math.min(1f, driftProgress + DRIFT_STEP_MS / (float) SLIDE_MS);
            float s = 1f + (DRIFT - 1f) * driftProgress;
            front.setScaleX(s);
            front.setScaleY(s);
            main.postDelayed(this, DRIFT_STEP_MS);
        }
    };

    ArtSlideshow(Context ctx, View layer, ImageView a, ImageView b, View scrim, TextView caption) {
        this.ctx = ctx;
        this.layer = layer;
        this.front = a;
        this.back = b;
        this.caption = caption;
        this.prefs = ctx.getSharedPreferences("layout", Context.MODE_PRIVATE);
        this.dir = new File(ctx.getCacheDir(), "art-v3");
        scrim.setBackground(scrim());
        scrim.setOutlineProvider(null);   // it sits above the images in Z; no shadow
        scrim.setTranslationZ(2f);
        caption.setTranslationZ(2f);
    }

    boolean isEnabled() {
        return prefs.getBoolean(PREF, ctx.getResources().getBoolean(R.bool.background_art));
    }

    void setEnabled(boolean on) {
        prefs.edit().putBoolean(PREF, on).apply();
        if (!on) stop();
    }

    /** Show the art (the dock is focused). Does nothing while disabled. */
    void start() {
        if (running || !isEnabled()) return;
        running = true;
        if (hasImage) {
            reveal();
            schedule(remainingMs);
            main.postDelayed(driftStep, DRIFT_STEP_MS);
        } else {
            loadNext();
        }
    }

    /** Hide the art (focus left the dock, or the launcher was paused). The current image and its time left are kept. */
    void stop() {
        running = false;
        if (dueAt > 0) remainingMs = Math.max(0, dueAt - SystemClock.uptimeMillis());
        dueAt = 0;
        main.removeCallbacks(next);
        main.removeCallbacks(driftStep);
        // A credit still showing goes with the art; it isn't shown again when the art comes back.
        main.removeCallbacks(hideCaption);
        caption.animate().cancel();
        caption.setAlpha(0f);
        layer.animate().alpha(0f).setDuration(300).withEndAction(() -> {
            if (!running) layer.setVisibility(View.GONE);
        }).start();
    }

    void shutdown() {
        stop();
        io.shutdownNow();
    }

    private void schedule(long ms) {
        main.removeCallbacks(next);
        dueAt = SystemClock.uptimeMillis() + ms;
        main.postDelayed(next, ms);
    }

    private void fadeCaptionOut() {
        caption.animate().alpha(0f).setDuration(800).start();
    }

    /** The TV woke from standby: credit the painting once more, the next time it's shown. */
    void requestCaption() {
        captionPending = true;
    }

    private void reveal() {
        layer.setVisibility(View.VISIBLE);
        layer.animate().alpha(1f).setDuration(700).withEndAction(null).start();
        if (captionPending && caption.length() > 0) {
            captionPending = false;
            main.removeCallbacks(hideCaption);
            caption.animate().alpha(1f).setDuration(600).start();
            main.postDelayed(hideCaption, CAPTION_MS);
        }
    }

    private void loadNext() {
        if (!running || loading) return;
        loading = true;
        final int width = layer.getWidth() > 0 ? layer.getWidth() : 1920;
        io.execute(() -> {
            Art art = null;
            Bitmap bmp = null;
            try {
                art = nextArt();
                if (art != null) bmp = decode(image(art), width);
            } catch (Exception ignored) {
                // offline or the API changed: fall back to the cache below
            }
            if (bmp == null) {
                File f = randomCached();
                if (f != null) {
                    bmp = decode(f, width);
                    art = cachedCaption(f);
                }
            }
            final Art shown = art;
            final Bitmap result = bmp;
            main.post(() -> {
                loading = false;
                if (result != null) show(result, shown);
                else if (running) schedule(hasImage ? SLIDE_MS : RETRY_MS);
            });
        });
    }

    /** Cross-fade the new image in on top of the old one, with a slow zoom from a random point. */
    private void show(Bitmap bmp, Art art) {
        final ImageView incoming = back;
        final ImageView outgoing = front;
        front = incoming;
        back = outgoing;

        incoming.setImageBitmap(bmp);
        incoming.setTranslationZ(1f);
        outgoing.setTranslationZ(0f);
        incoming.setAlpha(0f);
        incoming.setScaleX(1f);
        incoming.setScaleY(1f);
        int w = layer.getWidth() > 0 ? layer.getWidth() : 1920;
        int h = layer.getHeight() > 0 ? layer.getHeight() : 1080;
        incoming.setPivotX(w * (0.25f + random.nextFloat() * 0.5f));
        incoming.setPivotY(h * (0.25f + random.nextFloat() * 0.5f));
        incoming.animate().alpha(1f).setDuration(FADE_MS)
                .withEndAction(() -> outgoing.setImageDrawable(null))   // free the old bitmap
                .start();
        driftProgress = 0f;
        main.removeCallbacks(driftStep);
        if (running) main.postDelayed(driftStep, DRIFT_STEP_MS);

        main.removeCallbacks(hideCaption);
        caption.animate().cancel();
        caption.setAlpha(0f);
        caption.setText(art == null ? "" : art.caption());
        captionPending = true;   // reveal() fades it in for CAPTION_MS

        hasImage = true;
        remainingMs = SLIDE_MS;
        if (running) {
            reveal();
            schedule(SLIDE_MS);
        }
    }

    // ---- io thread -------------------------------------------------------------------------------

    private Art nextArt() throws Exception {
        if (queue.isEmpty()) {
            List<Art> list = list();
            Collections.shuffle(list, random);
            queue.addAll(list);
        }
        return queue.isEmpty() ? null : queue.remove(queue.size() - 1);
    }

    /** Today's list of paintings: refreshed once a day from a random results page, else the cached one. */
    private List<Art> list() throws Exception {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        dropOldCache();
        File cached = new File(dir, "list.json");
        boolean fresh = cached.exists() && System.currentTimeMillis() - cached.lastModified() < LIST_MAX_AGE_MS;
        if (!fresh) {
            try {
                byte[] body = get(SEARCH + (1 + random.nextInt(PAGES)));
                List<Art> parsed = parse(new String(body, StandardCharsets.UTF_8));
                if (!parsed.isEmpty()) {
                    write(cached, body);
                    return parsed;
                }
            } catch (Exception e) {
                if (!cached.exists()) throw e;
            }
        }
        return cached.exists() ? parse(new String(read(cached), StandardCharsets.UTF_8)) : new ArrayList<>();
    }

    /**
     * Earlier versions cached paintings that only had a landscape behind the figures (cache/art); they must
     * not come back when offline.
     */
    private void dropOldCache() {
        File old = new File(dir.getParentFile(), "art");
        File[] files = old.listFiles();
        if (files == null) return;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        old.delete();
    }

    /** Genre tags of pure scenery. A figure painting with scenery behind it is tagged "landscape" instead. */
    private static final String[] GENRES = {"landscapes", "seascapes", "marine", "cityscapes"};

    /**
     * Subjects that don't belong on a living-room screen at idle, matched against each word of the title and
     * the museum's tags (some works carry only "landscapes"): whole words, and words beginning with an entry of five letters or more ("war" is not "warm",
     * "saint" is "saints").
     */
    private static final String[] AVOID = {"nude", "nudes", "bather", "battle", "war", "wars", "military",
            "soldier", "death", "dead", "crucifixion", "martyr", "massacre", "violence", "execution", "hunting",
            "religio", "biblical", "christ", "jesus", "virgin", "madonna", "saint", "angel", "mytholog", "god",
            "gods", "goddess", "portrait", "famil", "child", "infant", "baby", "babies", "cupid", "putto", "putti",
            "women", "everyday", "capture", "siege", "attack", "wreck", "shipwreck", "burning"};

    private static boolean avoided(String tag) {
        for (String word : tag.split("[^a-z]+")) {
            for (String w : AVOID) {
                if (word.equals(w) || (w.length() >= 5 && word.startsWith(w))) return true;
            }
        }
        return false;
    }

    /**
     * Keeps wide works (portraits look poor cropped to 16:9) whose genre the museum tags as landscape,
     * seascape or cityscape, like Google TV's ambient art, minus any work with a subject in AVOID. Only as
     * good as the museum's tags.
     */
    private static List<Art> parse(String json) throws Exception {
        List<Art> out = new ArrayList<>();
        JSONArray data = new JSONObject(json).getJSONArray("data");
        for (int i = 0; i < data.length(); i++) {
            JSONObject o = data.getJSONObject(i);
            String id = o.optString("image_id", "");
            JSONObject t = o.optJSONObject("thumbnail");
            if (id.isEmpty() || "null".equals(id) || t == null) continue;
            double w = t.optDouble("width", 0), h = t.optDouble("height", 0);
            if (h <= 0 || w / h < 1.3) continue;
            List<String> tags = new ArrayList<>();
            for (String field : new String[]{"subject_titles", "term_titles"}) {
                JSONArray a = o.optJSONArray(field);
                for (int k = 0; a != null && k < a.length(); k++) tags.add(a.optString(k).toLowerCase(Locale.ROOT));
            }
            boolean scenery = false, avoid = false;
            for (String tag : tags) {
                for (String g : GENRES) scenery |= tag.equals(g);
                avoid |= avoided(tag);
            }
            String title = o.optString("title", "");
            if (!scenery || avoid || avoided(title.toLowerCase(Locale.ROOT))) continue;
            String artist = o.optString("artist_title", "");
            out.add(new Art(id, title, "null".equals(artist) ? "" : artist));
        }
        return out;
    }

    private File image(Art art) throws Exception {
        File jpg = new File(dir, art.imageId + ".jpg");
        if (!jpg.exists()) {
            File tmp = new File(dir, art.imageId + ".part");
            write(tmp, get(String.format(Locale.ROOT, IMAGE, art.imageId)));
            if (!tmp.renameTo(jpg)) throw new IllegalStateException("rename failed");
            write(new File(dir, art.imageId + ".txt"),
                    (art.title + "\n" + art.artist).getBytes(StandardCharsets.UTF_8));
            trimCache();
        }
        //noinspection ResultOfMethodCallIgnored
        jpg.setLastModified(System.currentTimeMillis());
        return jpg;
    }

    private void trimCache() {
        File[] jpgs = dir.listFiles((d, n) -> n.endsWith(".jpg"));
        if (jpgs == null || jpgs.length <= CACHE_FILES) return;
        Arrays.sort(jpgs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < jpgs.length - CACHE_FILES; i++) {
            String id = jpgs[i].getName().replace(".jpg", "");
            //noinspection ResultOfMethodCallIgnored
            jpgs[i].delete();
            //noinspection ResultOfMethodCallIgnored
            new File(dir, id + ".txt").delete();
        }
    }

    private File randomCached() {
        File[] jpgs = dir.listFiles((d, n) -> n.endsWith(".jpg"));
        return jpgs == null || jpgs.length == 0 ? null : jpgs[random.nextInt(jpgs.length)];
    }

    private Art cachedCaption(File jpg) {
        String id = jpg.getName().replace(".jpg", "");
        try {
            String[] lines = new String(read(new File(dir, id + ".txt")), StandardCharsets.UTF_8).split("\n", 2);
            return new Art(id, lines[0], lines.length > 1 ? lines[1] : "");
        } catch (Exception e) {
            return null;
        }
    }

    /** Decodes at roughly screen width; a broken file is deleted so it isn't picked again. */
    private static Bitmap decode(File f, int targetWidth) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= targetWidth) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeFile(f.getPath(), o);
        //noinspection ResultOfMethodCallIgnored
        if (bmp == null) f.delete();
        return bmp;
    }

    private static byte[] get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("AIC-User-Agent", USER_AGENT);   // the API asks callers to identify themselves
        c.setRequestProperty("User-Agent", USER_AGENT);
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            }
        } finally {
            c.disconnect();
        }
    }

    private static byte[] read(File f) throws Exception {
        try (InputStream in = new java.io.FileInputStream(f)) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static void write(File f, byte[] data) throws Exception {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    /** Darkens the top (clock), the left (app name) and the bottom (dock) so text stays legible on any painting. */
    private static Drawable scrim() {
        GradientDrawable vertical = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xA6000000, 0x1F000000, 0x33000000, 0xA6000000, 0xF2000000});
        GradientDrawable left = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x99000000, 0x33000000, 0x00000000});
        return new LayerDrawable(new Drawable[]{vertical, left});
    }
}
