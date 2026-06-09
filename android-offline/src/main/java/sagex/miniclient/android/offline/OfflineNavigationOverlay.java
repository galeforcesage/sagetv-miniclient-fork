/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package sagex.miniclient.android.offline;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.SystemClock;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.view.inputmethod.InputMethodManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process-wide on-screen soft-remote overlay for the offline shell.
 *
 * Lifecycle:
 *   - OFF when the app launches.
 *   - User activates it on any offline screen via:
 *       * a left-edge-to-middle swipe (touchscreens), OR
 *       * a long-press of DPAD_CENTER / ENTER (remotes).
 *   - Once activated the flag is sticky across activities — every offline
 *     activity re-attaches the overlay automatically.
 *   - User dismisses it (and clears the app-wide flag) by pressing the
 *     GREEN button (KEYCODE_PROG_GREEN).
 *
 * The overlay inflates {@link R.layout#offline_soft_remote} and anchors it
 * bottom-left over the host activity's content view. Each button on the
 * overlay synthesizes Android KeyEvents back into the host activity so DPAD
 * navigation, BACK, MENU, and player transport keys all work.
 */
public final class OfflineNavigationOverlay {

    private static final Logger log = LoggerFactory.getLogger(OfflineNavigationOverlay.class);

    // Sticky app-wide flag. Default OFF — user activates via a left-edge
    // swipe (touchscreens) or a long-press of DPAD_CENTER / ENTER (remotes).
    // Once activated the flag persists across activities until the user
    // dismisses it with GREEN (KEYCODE_PROG_GREEN).
    private static volatile boolean enabled = false;

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    private final Activity activity;
    private boolean gestureInstalled;
    private View overlayView;
    // Show the transport (play/pause/ff/rew/skip) row on every offline screen
    // by default — matches the online client's NavigationFragment layout the
    // user expects. Player activity can override via setShowPlayerRow().
    private boolean showPlayerRow = false;
    /**
     * Optional client-supplied view (e.g. the offline timebar) that should
     * be rendered inside the popup's bottom dock instead of in its own
     * window layer. Owned by the caller; we only reparent it.
     */
    private View timebarChild;
    /**
     * Where {@link #timebarChild} lived before we yanked it into the
     * popup. We restore the view back to this parent (with its original
     * layout params) when the overlay detaches, so callers that show the
     * timebar outside the popup (e.g. on a hardware-remote FF/REW) still
     * see it in its original spot.
     */
    private ViewGroup timebarOriginalParent;
    private android.view.ViewGroup.LayoutParams timebarOriginalLp;
    private int timebarOriginalIndex = -1;
    private int timebarOriginalVisibility = -1;

    public OfflineNavigationOverlay(Activity activity) {
        this.activity = activity;
    }

    /**
     * Install gesture/key handling on the activity. If the overlay is already
     * enabled (sticky flag), attach the soft-remote view immediately. Safe to
     * call once from {@code onCreate} after {@code setContentView}.
     *
     * The {@code ignoredFirstFocusTarget} parameter is kept for source-level
     * compatibility with earlier wiring; the overlay no longer requires it.
     */
    public void install(View ignoredFirstFocusTarget) {
        installGestureDetectorOnce();
        if (enabled) attachOverlay();
    }

    /** Player activity wants the transport row visible. */
    public void setShowPlayerRow(boolean show) {
        this.showPlayerRow = show;
        if (overlayView != null) {
            View row = overlayView.findViewById(R.id.offline_remote_player_row);
            if (row != null) row.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Hand the soft remote a host view (e.g. the offline timebar) that
     * should live <em>inside</em> the popup instead of floating on its own
     * window layer. The view is reparented into
     * {@code R.id.offline_remote_timebar_host} whenever the overlay is
     * attached and detached again before the host is destroyed.
     */
    public void setTimebarView(View child) {
        if (this.timebarChild == child) return;
        // Detach previous child from whatever host we put it in (popup or
        // its remembered original parent). Don't restore to its original
        // parent here — the caller is replacing the reference outright.
        if (this.timebarChild != null) {
            ViewGroup parent = (ViewGroup) this.timebarChild.getParent();
            if (parent != null) parent.removeView(this.timebarChild);
        }
        this.timebarChild = child;
        this.timebarOriginalParent = null;
        this.timebarOriginalLp = null;
        this.timebarOriginalIndex = -1;
        if (child != null) {
            ViewGroup p = (ViewGroup) child.getParent();
            if (p != null) {
                this.timebarOriginalParent = p;
                this.timebarOriginalLp = child.getLayoutParams();
                this.timebarOriginalIndex = p.indexOfChild(child);
            }
        }
        attachTimebarChildToHost();
    }

    private void attachTimebarChildToHost() {
        if (overlayView == null || timebarChild == null) return;
        ViewGroup host = overlayView.findViewById(R.id.offline_remote_timebar_host);
        if (host == null) return;
        // Position the host so the bar sits ~20% of the screen height up
        // from the bottom, well clear of the transport row below it.
        // Doing this at runtime (rather than via a fixed dp value in the
        // XML) keeps the offset proportional across phone / fold / TV.
        positionTimebarHost(host);
        ViewGroup currentParent = (ViewGroup) timebarChild.getParent();
        if (currentParent == host) {
            // Already attached — just re-assert visibility in case the
            // brief-show hide runnable fired while popup was up.
            timebarChild.setVisibility(View.VISIBLE);
            return;
        }
        // Remember where it came from on the very first reparent so that
        // detachOverlay() can put it back exactly where the activity put it.
        if (currentParent != null && currentParent != host
                && timebarOriginalParent == null) {
            timebarOriginalParent = currentParent;
            timebarOriginalLp = timebarChild.getLayoutParams();
            timebarOriginalIndex = currentParent.indexOfChild(timebarChild);
            timebarOriginalVisibility = timebarChild.getVisibility();
        }
        if (currentParent != null) currentParent.removeView(timebarChild);
        host.addView(timebarChild, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        // While the popup is up the timebar is part of it — keep it
        // visible so users can see playhead + comskip bands without
        // having to hit FF/REW first.
        timebarChild.setVisibility(View.VISIBLE);
        if (activity instanceof OfflinePlaybackActivity) {
            ((OfflinePlaybackActivity) activity).refreshOfflineTimeBar();
        }
    }

    private void positionTimebarHost(ViewGroup host) {
        android.view.ViewGroup.LayoutParams raw = host.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams hostLp = (FrameLayout.LayoutParams) raw;
        // Popup root is MATCH_PARENT so display metrics is the right
        // reference here. We just lift the bar by 20% of screen height so
        // it floats above the transport row.
        int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
        int desiredBottom = (int) (screenHeight * 0.20f);
        if (hostLp.bottomMargin != desiredBottom) {
            hostLp.bottomMargin = desiredBottom;
            host.setLayoutParams(hostLp);
        }
    }

    private void restoreTimebarChildToOriginalParent() {
        if (timebarChild == null) return;
        ViewGroup currentParent = (ViewGroup) timebarChild.getParent();
        if (currentParent != null) currentParent.removeView(timebarChild);
        // Restore the timebar's pre-popup visibility — typically GONE so
        // it doesn't sit on the player. The activity's existing
        // showOfflineTimeBarBriefly() path will flash it back on for
        // hardware-remote FF/REW.
        if (timebarOriginalVisibility >= 0) {
            timebarChild.setVisibility(timebarOriginalVisibility);
        }
        if (timebarOriginalParent == null) return;
        int idx = timebarOriginalIndex;
        if (idx < 0 || idx > timebarOriginalParent.getChildCount()) {
            idx = timebarOriginalParent.getChildCount();
        }
        if (timebarOriginalLp != null) {
            timebarOriginalParent.addView(timebarChild, idx, timebarOriginalLp);
        } else {
            timebarOriginalParent.addView(timebarChild, idx);
        }
    }

    /** Re-attach / detach when the activity resumes. */
    public void onResume() {
        if (enabled) attachOverlay();
        else detachOverlay();
    }

    /** Returns true if the event was consumed. */
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER) {
            // Required so onKeyLongPress() fires.
            event.startTracking();
            return false; // let default behaviour continue
        }
        if (keyCode == KeyEvent.KEYCODE_PROG_GREEN) {
            enabled = false;
            detachOverlay();
            return true;
        }
        return false;
    }

    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER) {
            activate();
            return true;
        }
        return false;
    }

    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return false;
    }

    private void installGestureDetectorOnce() {
        if (gestureInstalled) return;
        final View root = activity.findViewById(android.R.id.content);
        if (root == null) return;
        gestureInstalled = true;
        final GestureDetector gd = new GestureDetector(activity,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent e) { return true; }
                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float vx, float vy) {
                        if (e1 == null || e2 == null) return false;
                        float w = root.getWidth();
                        if (w <= 0) return false;
                        float dx = e2.getX() - e1.getX();
                        float dy = Math.abs(e2.getY() - e1.getY());
                        // Left-edge swipe rightward — accept if it starts in
                        // the left 20% of the screen, travels at least 80dp
                        // and is mostly horizontal.
                        boolean fromLeftEdge = e1.getX() < w * 0.20f;
                        boolean horizontal = dx > 80f * activity.getResources().getDisplayMetrics().density
                                && dx > dy;
                        if (fromLeftEdge && horizontal) {
                            activate();
                            return true;
                        }
                        return false;
                    }
                });
        // Wrap the activity's Window.Callback so the gesture detector sees
        // EVERY touch — child views (lists/buttons) consume events before the
        // content-view's OnTouchListener fires, which is why menu screens
        // never saw the swipe. dispatchTouchEvent runs first, regardless of
        // who consumes the event downstream.
        final Window window = activity.getWindow();
        final Window.Callback wrapped = window.getCallback();
        window.setCallback(new GestureCallbackWrapper(wrapped, gd));
    }

    private static final class GestureCallbackWrapper implements Window.Callback {
        private final Window.Callback delegate;
        private final GestureDetector gd;

        GestureCallbackWrapper(Window.Callback delegate, GestureDetector gd) {
            this.delegate = delegate;
            this.gd = gd;
        }

        @Override public boolean dispatchKeyEvent(KeyEvent event) { return delegate.dispatchKeyEvent(event); }
        @Override public boolean dispatchKeyShortcutEvent(KeyEvent event) { return delegate.dispatchKeyShortcutEvent(event); }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            try { gd.onTouchEvent(event); } catch (Throwable ignored) {}
            return delegate.dispatchTouchEvent(event);
        }
        @Override public boolean dispatchTrackballEvent(MotionEvent event) { return delegate.dispatchTrackballEvent(event); }
        @Override public boolean dispatchGenericMotionEvent(MotionEvent event) { return delegate.dispatchGenericMotionEvent(event); }
        @Override public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) { return delegate.dispatchPopulateAccessibilityEvent(event); }
        @Override public View onCreatePanelView(int featureId) { return delegate.onCreatePanelView(featureId); }
        @Override public boolean onCreatePanelMenu(int featureId, Menu menu) { return delegate.onCreatePanelMenu(featureId, menu); }
        @Override public boolean onPreparePanel(int featureId, View view, Menu menu) { return delegate.onPreparePanel(featureId, view, menu); }
        @Override public boolean onMenuOpened(int featureId, Menu menu) { return delegate.onMenuOpened(featureId, menu); }
        @Override public boolean onMenuItemSelected(int featureId, MenuItem item) { return delegate.onMenuItemSelected(featureId, item); }
        @Override public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) { delegate.onWindowAttributesChanged(attrs); }
        @Override public void onContentChanged() { delegate.onContentChanged(); }
        @Override public void onWindowFocusChanged(boolean hasFocus) { delegate.onWindowFocusChanged(hasFocus); }
        @Override public void onAttachedToWindow() { delegate.onAttachedToWindow(); }
        @Override public void onDetachedFromWindow() { delegate.onDetachedFromWindow(); }
        @Override public void onPanelClosed(int featureId, Menu menu) { delegate.onPanelClosed(featureId, menu); }
        @Override public boolean onSearchRequested() { return delegate.onSearchRequested(); }
        @Override public boolean onSearchRequested(SearchEvent searchEvent) { return delegate.onSearchRequested(searchEvent); }
        @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) { return delegate.onWindowStartingActionMode(callback); }
        @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) { return delegate.onWindowStartingActionMode(callback, type); }
        @Override public void onActionModeStarted(ActionMode mode) { delegate.onActionModeStarted(mode); }
        @Override public void onActionModeFinished(ActionMode mode) { delegate.onActionModeFinished(mode); }
    }

    /** Activate the soft remote (also called by host activities for swipe/tap shortcuts). */
    public void activate() {
        log.info("soft_remote_activate cls={}", activity.getClass().getSimpleName());
        enabled = true;
        attachOverlay();
    }

    /** Toggle visibility — used by player tap. */
    public void toggle() {
        if (enabled) {
            enabled = false;
            detachOverlay();
        } else {
            activate();
        }
    }

    private void attachOverlay() {
        if (overlayView != null) {
            overlayView.setVisibility(View.VISIBLE);
            return;
        }
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        View v = LayoutInflater.from(activity).inflate(R.layout.offline_soft_remote, content, false);
        // Cover the whole window so the inner rows' layout_gravity values
        // (top-left, top-right, bottom-center) actually distribute the
        // buttons across the screen. The root FrameLayout is non-clickable
        // / non-focusable, so empty regions pass touches through to the
        // underlying player view; only the ImageButtons themselves consume
        // events.
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        v.setLayoutParams(lp);
        wireButtons(v);
        content.addView(v);
        overlayView = v;
        attachTimebarChildToHost();
        if (showPlayerRow) {
            View row = v.findViewById(R.id.offline_remote_player_row);
            if (row != null) row.setVisibility(View.VISIBLE);
        } else {
            View row = v.findViewById(R.id.offline_remote_player_row);
            if (row != null) row.setVisibility(View.GONE);
        }
    }

    private void detachOverlay() {
        if (overlayView == null) return;
        // Restore the timebar to its original parent (typically the
        // activity content root) so it can keep flashing on hardware-remote
        // FF/REW after the popup closes — see setTimebarView()/
        // attachTimebarChildToHost() for where the original parent is
        // captured.
        if (timebarChild != null) {
            restoreTimebarChildToOriginalParent();
        }
        ViewGroup parent = (ViewGroup) overlayView.getParent();
        if (parent != null) parent.removeView(overlayView);
        overlayView = null;
    }

    private int dp(int v) {
        return (int) (v * activity.getResources().getDisplayMetrics().density);
    }

    private void wireButtons(View v) {
        bindKey(v, R.id.offline_remote_menu, KeyEvent.KEYCODE_MENU);
        bindKey(v, R.id.offline_remote_info, KeyEvent.KEYCODE_INFO);
        bindKey(v, R.id.offline_remote_back, KeyEvent.KEYCODE_BACK);
        bindKey(v, R.id.offline_remote_help, KeyEvent.KEYCODE_HELP);
        bindKey(v, R.id.offline_remote_skip_back, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        bindKey(v, R.id.offline_remote_rew, KeyEvent.KEYCODE_MEDIA_REWIND);
        bindKey(v, R.id.offline_remote_stop, KeyEvent.KEYCODE_MEDIA_STOP);
        bindKey(v, R.id.offline_remote_pause, KeyEvent.KEYCODE_MEDIA_PAUSE);
        bindKey(v, R.id.offline_remote_play, KeyEvent.KEYCODE_MEDIA_PLAY);
        bindKey(v, R.id.offline_remote_ff, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
        bindKey(v, R.id.offline_remote_skip_forward, KeyEvent.KEYCODE_MEDIA_NEXT);

        View home = v.findViewById(R.id.offline_remote_home);
        if (home != null) {
            home.setOnClickListener(view -> {
                Intent i = new Intent(activity, OfflineHomeActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                activity.startActivity(i);
            });
        }

        View rotate = v.findViewById(R.id.offline_remote_rotate);
        if (rotate != null) {
            rotate.setOnClickListener(view -> {
                int current = activity.getRequestedOrientation();
                if (current == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        || current == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                } else {
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                }
            });
        }

        View close = v.findViewById(R.id.offline_remote_close);
        if (close != null) {
            close.setOnClickListener(view -> {
                enabled = false;
                detachOverlay();
                Intent launch = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(launch);
                }
            });
        }

        View enter = v.findViewById(R.id.offline_remote_enter);
        if (enter != null) {
            enter.setOnClickListener(view -> {
                enabled = false;
                detachOverlay();
            });
        }

        View aspect = v.findViewById(R.id.offline_remote_aspect);
        if (aspect != null) {
            aspect.setOnClickListener(view ->
                    OfflinePlaybackActivity.dispatchLocalAction(
                            OfflinePlaybackActivity.LocalAction.CYCLE_RESIZE));
        }
    }

    private void bindKey(View root, int viewId, int keyCode) {
        View b = root.findViewById(viewId);
        if (b == null) return;
        b.setOnClickListener(view -> {
            log.info("soft_remote_click viewId={} keyCode={}", viewId, keyCode);
            dispatchKey(keyCode);
        });
    }

    private void dispatchKey(int keyCode) {
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
        KeyEvent up   = new KeyEvent(now, now, KeyEvent.ACTION_UP,   keyCode, 0);
        // Try the host activity FIRST so media keys aren't swallowed by
        // child views (e.g. StyledPlayerView maps MEDIA_FF/REW to
        // seekToNext/Previous and lands at position 0 in a single-item
        // playlist). If the activity declines, fall back to the normal
        // dispatch chain so the focused view can handle it (e.g. DPAD
        // navigation in menus).
        if (!activity.onKeyDown(keyCode, down)) {
            activity.dispatchKeyEvent(down);
            activity.dispatchKeyEvent(up);
        } else {
            activity.onKeyUp(keyCode, up);
        }
    }
}
