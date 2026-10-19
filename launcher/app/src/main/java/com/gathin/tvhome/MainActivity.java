package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private String liveTvPkg;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    private GradientDrawable ambientGlow;
    private int ambientColor;
    private boolean heroShown;
    private View btnMenu;
    /** Network name (or Ethernet / Offline), shown on the Wi-Fi row of the top menu. */
    private String wifiLabel = "";
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    private boolean glassShown;
    /** Grid scroll still to come that the glass target already accounts for (see onTileFocused). */
    private int pendingScrollY;
    /** Adapter position of the tile being moved (tile menu → Move), or -1. */
    private int movingPos = -1;
    /** The OK press that ended a move must not also open the app on release. */
    private boolean swallowConfirmUp;
    private PopupWindow tileMenu, topMenu;
    private ValueAnimator wiggle;
    private ArtSlideshow art;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    /** Waking from standby counts as a fresh look at the home screen: credit the painting again. */
    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            art.requestCaption();
        }
    };
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        COLUMNS = Math.max(1, getResources().getInteger(R.integer.columns));
        liveTvPkg = getString(R.string.live_tv_package).trim();

        root = findViewById(R.id.root);
        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        heroName = findViewById(R.id.heroName);
        ambientGlow = new GradientDrawable();
        ambientGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambientGlow.setGradientCenter(0.32f, 0.42f);
        ambient.setBackground(ambientGlow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                ambientGlow.setGradientRadius((r - l) * 0.62f));
        btnMenu = findViewById(R.id.btnMenu);
        art = new ArtSlideshow(this, findViewById(R.id.artLayer), findViewById(R.id.artA), findViewById(R.id.artB),
                findViewById(R.id.artScrim), findViewById(R.id.artCaption));

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.addItemDecoration(new DockDecoration(COLUMNS, density));
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS);
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            int dockRow = (int) ((145 + 6 + 12) * density);   // item height plus DockDecoration offsets
            int want = Math.max(0, h - dockRow - (int) (30 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
        });

        // Keep the glass glued to its tile while the grid scrolls. Scrolling that onTileFocused already
        // aimed the glass for is skipped; any other scrolling (not started there, or cut short) carries it.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                int expected = 0;
                if (pendingScrollY != 0 && Integer.signum(dy) == Integer.signum(pendingScrollY)) {
                    expected = Math.abs(dy) <= Math.abs(pendingScrollY) ? dy : pendingScrollY;
                }
                pendingScrollY -= expected;
                int extra = dy - expected;
                if (extra == 0) return;
                glassTargetY -= extra;
                if (springY.isRunning()) springY.animateToFinalPosition(glassTargetY);
                else glass.setTranslationY(glassTargetY);
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                pendingScrollY = 0;
                // The list may have stopped short (its end): settle the glass on the tile's real spot.
                View item = rv.getFocusedChild();
                View card = item != null ? item.findViewById(R.id.card) : null;
                if (!glassShown || card == null || !card.isFocused()) return;
                float y = glassRect(card)[1];
                if (Math.abs(y - glassTargetY) > 0.5f) {
                    glassTargetY = y;
                    springY.animateToFinalPosition(y);
                }
            }
        });

        // Smooth glide: critically damped springs on the highlight's position, so it never overshoots
        // the tile and bounces back. Springs (not fixed-duration animators) keep it fluid when the
        // target changes mid-flight from fast D-pad presses.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(420f).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        }

        btnMenu.setOnClickListener(v -> showTopMenu());
        // Up, Left and Right stay on the button: Android's focus finder would otherwise jump to a tile far
        // below when there is nothing in that direction. Down goes to the grid.
        btnMenu.setOnKeyListener((v, keyCode, event) -> event.getAction() == KeyEvent.ACTION_DOWN
                && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT));

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        });

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    /** The home screen while it's in front, for GearKeyService; null otherwise. Main thread only. */
    private static WeakReference<MainActivity> front = new WeakReference<>(null);

    static MainActivity inFront() {
        return front.get();
    }

    @Override
    protected void onResume() {
        super.onResume();
        front = new WeakReference<>(this);
        glassShown = false;
        glass.setAlpha(0f);
        heroShown = false;
        heroName.setAlpha(0f);
        ambient.setAlpha(0f);
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        adapter.reload();
        grid.scheduleLayoutAnimation();
        updateWifiLabel();

        // Return to the app we left from, or the same slot if that app is gone. Always refocus: after
        // reload() the view that holds focus may no longer show the app it had.
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (front.get() == this) front = new WeakReference<>(null);
        if (movingPos >= 0) endMove();
        art.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        art.shutdown();
        unregisterReceiver(screenOn);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.unregisterNetworkCallback(networkCallback);
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    /** Glass bounds {x, y, w, h, tileTop} in root coordinates for a tile where it is now (layout, not scale). */
    private float[] glassRect(View card) {
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        return new float[]{r.exactCenterX() - w / 2f, r.exactCenterY() - h / 2f, w, h, r.top};
    }

    /** How far the grid must scroll so rows [top, bottom] (grid coordinates) are on screen; RecyclerView's own rule. */
    private int scrollToReveal(int top, int bottom) {
        int parentTop = grid.getPaddingTop();
        int parentBottom = grid.getHeight() - grid.getPaddingBottom();
        int offTop = Math.min(0, top - parentTop);
        int offBottom = Math.max(0, bottom - parentBottom);
        return offTop != 0 ? offTop : Math.min(top - parentTop, offBottom);
    }

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        View item = (View) card.getParent();
        float pad = 6f * density;
        // Decide the scroll first and aim the glass at where the tile will end up. Chasing the tile while
        // the grid scrolls is what made vertical moves overshoot and snap back.
        int dy;
        if (position < COLUMNS) {
            // Dock: park it at the bottom of the screen.
            dy = item.getTop() - (int) (6 * density) - grid.getPaddingTop();
        } else {
            // Just enough so the whole glass (not only the tile) is visible.
            dy = scrollToReveal(item.getTop() - 2 * (int) pad, item.getBottom() + (int) pad);
        }
        if (Math.abs(dy) <= 1) dy = 0;
        pendingScrollY = dy;
        if (dy != 0) grid.smoothScrollBy(0, dy);

        float[] g = glassRect(card);
        float w = g[2], h = g[3];
        float x = g[0];
        float y = g[1] - dy;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(16f * density * AppAdapter.FOCUS_SCALE + pad);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
        if (position < COLUMNS) showHero(position, g[4] - dy);   // and the top shelf for this app
        else hideHero();
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());

        if (!heroShown) {
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(600).start();
            art.start();   // optional; covers the glow while it has an image
            heroShown = true;
        } else if (changed) {
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            if (color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        } else {
            heroName.setY(y);
        }
    }

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        ambientGlow.setColors(new int[]{0x5C000000 | c, 0x26000000 | c, 0x00000000 | c});
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        heroName.animate().alpha(0f).setDuration(160).start();
        ambient.animate().alpha(0f).setDuration(300).start();
        art.stop();
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileKey(int position, View card, KeyEvent event) {
        int code = event.getKeyCode();
        boolean confirm = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (confirm && swallowConfirmUp) {
                swallowConfirmUp = false;
                return true;
            }
            return movingPos >= 0;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

        if (movingPos >= 0) {
            // Move mode: arrows move the tile, OK / Back / Menu finish.
            if (confirm || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
                if (event.getRepeatCount() == 0) {
                    endMove();
                    swallowConfirmUp = confirm;
                }
                return true;
            }
            int to;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) to = position - 1;
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) to = position + 1;
            else if (code == KeyEvent.KEYCODE_DPAD_UP) to = position - COLUMNS;
            else if (code == KeyEvent.KEYCODE_DPAD_DOWN) to = position + COLUMNS;
            else return true;
            to = Math.max(0, Math.min(to, adapter.getItemCount() - 1));
            if (to != position) moveTile(position, to);
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            onTileMenu(position, card);
            return true;
        }
        // Holding OK: remotes auto-repeat the key, so open the menu on the first repeat as well as from
        // the view's long-press timer (whichever comes first; onTileMenu ignores the second).
        if (confirm && event.getRepeatCount() > 0) {
            if (event.getRepeatCount() == 1) onTileMenu(position, card);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            return onTileVerticalKey(position, code);
        }
        return false;
    }

    // ---- tile menu: move, hide, background art --------------------------------------------------

    @Override
    public void onTileMenu(int position, View card) {
        if (movingPos >= 0 || (tileMenu != null && tileMenu.isShowing())) return;
        String label = adapter.labelAt(position);
        LinearLayout rows = newMenu();
        PopupWindow popup = popupFor(rows);
        tileMenu = popup;
        addInputRow(rows, popup, R.drawable.ic_move, getString(R.string.menu_move), -1, () -> startMove(position));
        addInputRow(rows, popup, R.drawable.ic_hide, getString(R.string.menu_hide), -1, () -> {
            adapter.hide(position);
            Toast.makeText(this, getString(R.string.hidden_toast, label), Toast.LENGTH_LONG).show();
            grid.post(() -> focusTile(Math.min(position, adapter.getItemCount() - 1)));
        });
        int hiddenCount = adapter.hiddenApps().size();
        if (hiddenCount > 0) {
            addInputRow(rows, popup, R.drawable.ic_show, getString(R.string.menu_hidden_apps, hiddenCount), -1,
                    () -> grid.post(() -> showHiddenApps(card)));
        }
        addSeparator(rows);
        boolean artOn = art.isEnabled();
        addInputRow(rows, popup, R.drawable.ic_art, getString(artOn ? R.string.menu_art_on : R.string.menu_art_off), -1, () -> {
            art.setEnabled(!artOn);
            if (!artOn && heroShown) art.start();
        });
        addInputRow(rows, popup, R.drawable.ic_info, getString(R.string.menu_app_info), -1,
                () -> adapter.openAppInfo(position));
        showMenu(popup, rows, card);
    }

    private void showHiddenApps(View anchor) {
        LinearLayout rows = newMenu();
        PopupWindow popup = popupFor(rows);
        for (AppAdapter.Entry e : adapter.hiddenApps()) {
            final String pkg = e.pkg, label = e.label;
            addInputRow(rows, popup, R.drawable.ic_show, label, -1, () -> {
                adapter.unhide(pkg);
                Toast.makeText(this, getString(R.string.unhidden_toast, label), Toast.LENGTH_SHORT).show();
                grid.post(() -> focusTile(lastFocused));
            });
        }
        if (rows.getChildCount() > 0) showMenu(popup, rows, anchor.isAttachedToWindow() ? anchor : grid);
    }

    private LinearLayout newMenu() {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        return panel.findViewById(R.id.rows);
    }

    private PopupWindow popupFor(LinearLayout rows) {
        PopupWindow popup = new PopupWindow((View) rows.getParent(), (int) (240 * density),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        return popup;
    }

    /**
     * Shows the menu under the tile, or above it when it wouldn't fit (the dock sits at the bottom), with the
     * Inputs menu's unfold. Placed by hand: showAsDropDown doesn't flip a wrap_content popup it hasn't measured.
     */
    private void showMenu(PopupWindow popup, LinearLayout rows, View anchor) {
        View panel = (View) rows.getParent();
        panel.measure(View.MeasureSpec.makeMeasureSpec(popup.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = panel.getMeasuredHeight();
        int gap = (int) (10 * density);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        // A focused tile is drawn scaled up; keep clear of its scaled edges.
        int grow = anchor.getId() == R.id.card ? (int) (anchor.getHeight() * (AppAdapter.FOCUS_SCALE - 1f) / 2f) : 0;
        int below = at[1] + anchor.getHeight() + grow + gap;
        boolean up = below + h > root.getHeight() - gap;
        int y = up ? at[1] - grow - gap - h : below;
        // Left-aligned with the anchor, or right-aligned when the anchor sits in the right half (top menu).
        boolean alignEnd = at[0] + anchor.getWidth() / 2 > root.getWidth() / 2;
        int x = alignEnd ? at[0] + anchor.getWidth() - popup.getWidth() : at[0];
        x = Math.max(gap, Math.min(x, root.getWidth() - popup.getWidth() - gap));
        panel.setAlpha(0f);
        panel.setPivotY(up ? h : 0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY((up ? 8 : -8) * density);
        popup.showAtLocation(root, Gravity.TOP | Gravity.START, x, Math.max(gap, y));
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private void startMove(int position) {
        movingPos = position;
        glass.setMoving(true);
        // tvOS style: the other tiles slide out of the way (an item animator only while moving; the grid
        // otherwise has none) and the tile being moved wiggles together with its glass.
        DefaultItemAnimator slide = new DefaultItemAnimator();
        slide.setSupportsChangeAnimations(false);   // label rebinds after a move must not cross-fade
        slide.setMoveDuration(280);
        grid.setItemAnimator(slide);
        wiggle = ValueAnimator.ofFloat(-0.5f, 0.5f);   // a gentle sway, not a jitter
        wiggle.setDuration(260);
        wiggle.setRepeatMode(ValueAnimator.REVERSE);
        wiggle.setRepeatCount(ValueAnimator.INFINITE);
        wiggle.addUpdateListener(a -> {
            float deg = (float) a.getAnimatedValue();
            View c = focusedCard();
            if (c != null) c.setRotation(deg);
            glass.setRotation(deg);
        });
        wiggle.start();
        Toast.makeText(this, getString(R.string.move_hint, adapter.labelAt(position)), Toast.LENGTH_LONG).show();
    }

    private View focusedCard() {
        View item = grid.getFocusedChild();
        return item != null ? item.findViewById(R.id.card) : null;
    }

    private void moveTile(int from, int to) {
        adapter.move(from, to);
        movingPos = to;
        // After the move is laid out: keep focus on the moved tile and bring the glass (and dock shelf) along.
        // A fixed-size RecyclerView applies moves in an animation callback, not a layout pass, so queue
        // behind it there; a plain post() runs too early and the glass stays on the old slot.
        grid.postOnAnimation(() -> {
            RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(to);
            if (vh == null) {
                focusTile(to);
                return;
            }
            View c = vh.itemView.findViewById(R.id.card);
            if (c.isFocused()) onTileFocused(to, c);
            else c.requestFocus();
        });
    }

    private void endMove() {
        movingPos = -1;
        glass.setMoving(false);
        if (wiggle != null) wiggle.cancel();
        wiggle = null;
        View c = focusedCard();
        if (c != null) c.animate().rotation(0f).setDuration(120).start();
        glass.animate().rotation(0f).setDuration(120).start();
        // Let a slide that's still running finish before dropping the animator (dropping it jumps to the end).
        grid.postDelayed(() -> {
            if (movingPos < 0) grid.setItemAnimator(null);
        }, 400);
        adapter.saveOrder();
    }

    private boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            if (caps == null) {
                text = getString(R.string.offline);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                text = getString(R.string.ethernet);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        wifiLabel = text;
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = btnMenu;
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact, hanging right below the menu button with its right edge aligned to it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (tunerId == null || info.getId().compareTo(tunerId) < 0)) {
                    tunerId = info.getId();
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase().contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel")));
            }
        }
        Intent liveTv = liveTvPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, panel.getMeasuredWidth()));

        // Drop in: fade and unfold from the top edge.
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), Gravity.END);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    /** The remote's gear button (GearKeyService): open the top menu, or close it if it's already open. */
    void toggleTopMenu() {
        if (topMenu != null && topMenu.isShowing()) topMenu.dismiss();
        else showTopMenu();
    }

    /** The menu under the top-right button: Inputs (opens the inputs list), Wi-Fi with the network name, Settings. */
    private void showTopMenu() {
        updateWifiLabel();
        LinearLayout rows = newMenu();
        PopupWindow popup = popupFor(rows);
        topMenu = popup;
        addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1,
                () -> btnMenu.post(this::showInputs));
        addInputRow(rows, popup, R.drawable.ic_wifi, getString(R.string.wifi), -1,
                () -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        TextView network = rows.getChildAt(rows.getChildCount() - 1).findViewById(R.id.status);
        network.setText(wifiLabel);
        network.setVisibility(View.VISIBLE);
        addInputRow(rows, popup, R.drawable.ic_settings, getString(R.string.settings), -1,
                () -> open(new Intent(Settings.ACTION_SETTINGS)));
        showMenu(popup, rows, btnMenu);
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }
}
