package org.caremonitor.demo;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * Quiet, finite native motion. All mutating methods must run on the UI thread.
 * Call enter only for explicit navigation, not during polling or re-rendering.
 * fadeText does nothing when the displayed value has not changed. It never
 * animates the number itself, so an intermediate value cannot look like a reading.
 */
public final class CareMotion {
    private CareMotion() {}
    private static final WeakHashMap<View, WeakReference<Transition>> RUNNING = new WeakHashMap<>();

    /** App and Android reduced-motion preferences, and battery saver, take precedence. */
    public static boolean allowed(Context context) {
        try {
            if (context.getSharedPreferences("caremonitor_appearance", Context.MODE_PRIVATE)
                    .getBoolean("reduce_motion", false)) return false;
            if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) return false;
            if (Settings.Global.getFloat(context.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) <= 0f) return false;
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return power == null || !power.isPowerSaveMode();
        } catch (RuntimeException inaccessiblePreference) {
            // Static content is the safest fallback if the device hides preferences.
            return false;
        }
    }

    /** Replace a changed label immediately, then gently restore full opacity in 150 ms. */
    public static void fadeText(TextView view, CharSequence value) {
        uiThread();
        CharSequence next = value == null ? "" : value;
        if (TextUtils.equals(view.getText(), next)) return;
        cancel(view);
        view.setText(next);
        if (eligible(view)) new Transition(view, 150L, false).start();
    }

    /** One small entrance for a user-initiated page change; no repeat or auto-scroll. */
    public static void enter(View view) {
        uiThread();
        cancel(view);
        if (eligible(view)) new Transition(view, 180L, true).start();
    }

    /** Stop pending motion and leave the actual content fully visible. */
    public static void cancel(View view) {
        uiThread();
        WeakReference<Transition> reference = RUNNING.remove(view);
        Transition transition = reference == null ? null : reference.get();
        if (transition != null) transition.finish();
        view.setAlpha(1f);
        view.setTranslationY(0f);
    }

    private static boolean eligible(View view) {
        return view.isAttachedToWindow() && view.isShown()
                && view.getWindowVisibility() == View.VISIBLE && view.hasWindowFocus()
                && allowed(view.getContext());
    }

    private static void uiThread() {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("CareMotion must be updated on the UI thread");
    }

    private static final class Transition implements View.OnAttachStateChangeListener,
            ViewTreeObserver.OnWindowFocusChangeListener, ViewTreeObserver.OnPreDrawListener {
        private final View view;
        private final ValueAnimator animator;
        private final boolean entrance;
        private boolean finished;
        private ViewTreeObserver observer;

        Transition(View view, long duration, boolean entrance) {
            this.view = view;
            this.entrance = entrance;
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(duration);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(value -> {
                if (!eligible(view)) { finish(); return; }
                float progress = (float) value.getAnimatedValue();
                view.setAlpha((entrance ? .88f : .68f) + (entrance ? .12f : .32f) * progress);
                if (entrance) view.setTranslationY((1f - progress)
                        * 6f * view.getResources().getDisplayMetrics().density);
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) { finish(); }
                @Override public void onAnimationCancel(Animator animation) { finish(); }
            });
        }

        void start() {
            RUNNING.put(view, new WeakReference<>(this));
            view.addOnAttachStateChangeListener(this);
            observer = view.getViewTreeObserver();
            observer.addOnWindowFocusChangeListener(this);
            observer.addOnPreDrawListener(this);
            animator.start();
        }

        void finish() {
            if (finished) return;
            finished = true;
            animator.cancel();
            animator.removeAllUpdateListeners();
            animator.removeAllListeners();
            view.removeOnAttachStateChangeListener(this);
            if (observer != null && observer.isAlive()) {
                observer.removeOnWindowFocusChangeListener(this);
                observer.removeOnPreDrawListener(this);
            }
            WeakReference<Transition> reference = RUNNING.get(view);
            if (reference != null && reference.get() == this) RUNNING.remove(view);
            view.setAlpha(1f);
            view.setTranslationY(0f);
        }

        @Override public void onViewAttachedToWindow(View view) {}
        @Override public void onViewDetachedFromWindow(View view) { finish(); }
        @Override public void onWindowFocusChanged(boolean focused) { if (!focused) finish(); }
        @Override public boolean onPreDraw() {
            if (!eligible(view)) finish();
            return true;
        }
    }
}
