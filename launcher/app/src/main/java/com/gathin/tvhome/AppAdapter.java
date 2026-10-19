package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites, fixed order),
 * the rest is alphabetical, until tiles are moved from the tile menu on the TV: then that saved order wins.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Every key event on a tile (down and up). Return true when handled. */
        boolean onTileKey(int position, View card, KeyEvent event);

        /** Long-press OK on a tile. */
        void onTileMenu(int position, View card);
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
        }
    }

    static final float FOCUS_SCALE = 1.10f;

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** Dock order (res/values/config.xml, dock_apps). */
    private final List<String> favourites;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    /** Also list apps with only a phone launcher entry (res/values/device.xml, phone_launcher_apps). */
    private final boolean phoneLauncherApps;
    /** Apps whose icon becomes a banner (res/values/device.xml, icon_banner_apps). */
    private final Set<String> iconBanners;
    /** Hidden from the tile menu on the TV (packages), and the tile order set there (keys, dock first). */
    private final SharedPreferences prefs;
    private final Set<String> userHidden;
    private List<String> savedOrder;
    private final List<Entry> items = new ArrayList<>();
    private final List<Entry> hiddenItems = new ArrayList<>();

    AppAdapter(Context ctx, TileListener listener, int dockSize) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps));
        this.hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        this.phoneLauncherApps = ctx.getResources().getBoolean(R.bool.phone_launcher_apps);
        this.iconBanners = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.icon_banner_apps)));
        this.prefs = ctx.getSharedPreferences("layout", Context.MODE_PRIVATE);
        this.userHidden = new HashSet<>(prefs.getStringSet("hidden", Collections.emptySet()));
        String order = prefs.getString("order", "");
        this.savedOrder = order.isEmpty() ? new ArrayList<>() : Arrays.asList(order.split("\n"));
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    void reload() {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        if (phoneLauncherApps) found = withPhoneLauncherApps(pm, found);
        List<Entry> fresh = new ArrayList<>();
        hiddenItems.clear();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Drawable icon = ri.loadIcon(pm);
            if (banner == null && icon != null && iconBanners.contains(pkg)) banner = iconBanner(icon);
            String label = String.valueOf(ri.loadLabel(pm));
            Entry e = new Entry(label, new ComponentName(pkg, ri.activityInfo.name), banner, icon);
            (userHidden.contains(pkg) ? hiddenItems : fresh).add(e);
        }
        Collections.sort(fresh, (a, b) -> {
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        // Once tiles have been moved on the TV, that order wins; apps installed since go at the end.
        if (!savedOrder.isEmpty()) {
            Collections.sort(fresh, (a, b) -> Integer.compare(orderIndex(a), orderIndex(b)));
        }
        Collections.sort(hiddenItems, (a, b) -> a.label.compareToIgnoreCase(b.label));
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    /**
     * The TV apps plus apps that declare only a phone launcher entry (on Fire TV: the Appstore, Silk), one
     * activity per app. They rarely have a banner, so they get the icon tile.
     */
    private static List<ResolveInfo> withPhoneLauncherApps(PackageManager pm, List<ResolveInfo> tv) {
        List<ResolveInfo> all = new ArrayList<>(tv);
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : tv) {
            if (ri.activityInfo != null) seen.add(ri.activityInfo.packageName);
        }
        Intent phone = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(phone, 0)) {
            if (ri.activityInfo != null && seen.add(ri.activityInfo.packageName)) all.add(ri);
        }
        return all;
    }

    /** A 16:9 banner from an app icon: the icon centred on its own edge colour, like a flat TV banner. */
    private Drawable iconBanner(Drawable icon) {
        int w = 320, h = 180, size = 132;
        Drawable d = icon.getConstantState() != null ? icon.getConstantState().newDrawable().mutate() : icon;
        Bitmap probe = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, size, size);
        d.draw(new Canvas(probe));
        int edge = probe.getPixel(size / 16, size / 2);
        probe.recycle();
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.alpha(edge) < 128 ? 0xFF1C1C1E : edge);
        d.setBounds((w - size) / 2, (h - size) / 2, (w + size) / 2, (h + size) / 2);
        d.draw(c);
        return new BitmapDrawable(ctx.getResources(), bmp);
    }

    private int orderIndex(Entry e) {
        int i = savedOrder.indexOf(e.component.flattenToShortString());
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /** Moves a tile (shifting the others) and rebinds the range, since the dock hides labels by position. */
    void move(int from, int to) {
        items.add(to, items.remove(from));
        notifyItemMoved(from, to);
        notifyItemRangeChanged(Math.min(from, to), Math.abs(from - to) + 1);
    }

    void saveOrder() {
        List<String> keys = new ArrayList<>();
        for (Entry e : items) keys.add(e.component.flattenToShortString());
        savedOrder = keys;
        prefs.edit().putString("order", String.join("\n", keys)).apply();
    }

    void hide(int position) {
        if (position < 0 || position >= items.size()) return;
        saveOrder();   // keep everyone else where they are
        userHidden.add(items.get(position).pkg);
        prefs.edit().putStringSet("hidden", new HashSet<>(userHidden)).apply();
        reload();
    }

    void unhide(String pkg) {
        userHidden.remove(pkg);
        prefs.edit().putStringSet("hidden", new HashSet<>(userHidden)).apply();
        reload();
    }

    /** Apps hidden from the tile menu, alphabetical. */
    List<Entry> hiddenApps() {
        return hiddenItems;
    }

    /** Whether the app at a position can be uninstalled (not part of the system image). */
    boolean canUninstall(int position) {
        if (position < 0 || position >= items.size()) return false;
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(items.get(position).pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Asks the system to uninstall the app at a position; it shows its own confirmation. */
    void uninstall(int position) {
        if (position < 0 || position >= items.size()) return;
        Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + items.get(position).pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    void openAppInfo(int position) {
        if (position < 0 || position >= items.size()) return;
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + items.get(position).pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    /** Tile width at 4 columns or fewer (item_app.xml), and the gap kept between tiles when more fit a row. */
    private static final int MAX_CARD_DP = 180, GAP_DP = 26;
    /** item_app.xml: vertical padding (8 + 8), space between card and name (7) and the name line (22). */
    private static final int ITEM_EXTRA_DP = 45;

    /** Card {width, height} in px for a grid this wide: as large as fits the columns, 16:9 like a TV banner. */
    static int[] cardSize(int gridWidth, int columns, float density) {
        int w = (int) (MAX_CARD_DP * density);
        if (gridWidth > 0) w = Math.min(w, gridWidth / columns - (int) (GAP_DP * density));
        return new int[]{w, Math.round(w * 101f / 180f)};
    }

    /** Height of one grid row (an item_app) in px, for a card of this height. */
    static int itemHeight(int cardHeight, float density) {
        return cardHeight + (int) (ITEM_EXTRA_DP * density);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        // Size the card (and its name line) to the column width: 180dp tiles don't fit five across.
        float density = parent.getResources().getDisplayMetrics().density;
        int[] size = cardSize(parent.getWidth(), dockSize, density);
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams cp = card.getLayoutParams();
        cp.width = size[0];
        cp.height = size[1];
        card.setLayoutParams(cp);
        View name = v.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams np = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        np.width = size[0];
        np.topMargin = size[1] + (int) (7 * density);
        name.setLayoutParams(np);
        return new Holder(v, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.setText(e.label);
        h.name.setVisibility(position < dockSize ? View.INVISIBLE : View.VISIBLE);   // the dock has no labels
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(focused ? 1f : 0f);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (listener != null && pos != RecyclerView.NO_POSITION) listener.onTileMenu(pos, v);
            return true;
        });
    }

    private void launch(Entry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).component.flattenToShortString().hashCode();
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).component.flattenToShortString() : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) return i;
        }
        return -1;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            final float radius = density * 16f;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileKey(pos, view, event);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 240 : 180)
                        .setInterpolator(new DecelerateInterpolator(1.5f))
                        .start();
                name.animate().alpha(focused ? 1f : 0f)
                        .translationY(focused ? density * 4f : -density * 6f)
                        .setDuration(220).start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
