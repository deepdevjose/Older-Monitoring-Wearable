package org.caremonitor.demo;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

/**
 * A decorative connection indicator, never a health judgement or simulated ECG.
 * Pair it with a visible, accessible status label. All setters require the UI thread.
 * LIVE means the caller verified that a real connection AND a fresh device reading
 * exist. WAITING is connected without fresh data; OFFLINE means no connection.
 * Only entering LIVE produces two quiet rings. Further readings do not restart it.
 * Detach, background, hidden state, battery saver and reduced motion stop motion.
 */
public final class CareStatusView extends View {
    public enum State { OFFLINE, CONNECTING, WAITING, LIVE }
    private static final int BLUE = 0xFF0066CC;
    private static final int MUTED = 0xFF767680;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private State state = State.OFFLINE;
    private ValueAnimator pulse;
    private float phase;
    private boolean active = true;

    public CareStatusView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
        setClickable(false);
        setSaveEnabled(false);
    }

    public State getState() { return state; }

    /** Repeating an identical state never restarts the animation. */
    public void setState(State next) {
        uiThread();
        if (next == null) next = State.OFFLINE;
        if (state == next) return;
        stopPulse();
        state = next;
        invalidate();
        if (next == State.LIVE && eligible()) startPulse();
    }

    /** Activity onStart/onStop can explicitly gate motion, including multiwindow. */
    public void setActive(boolean value) {
        uiThread();
        active = value;
        if (!value) stopPulse();
        // Returning to a page does not replay connection motion.
    }

    /** Exposed for lifecycle verification; never represents connection status. */
    public boolean isAnimating() { return pulse != null && pulse.isRunning(); }

    private boolean eligible() {
        return active && isAttachedToWindow() && isShown() && hasWindowFocus()
                && getWindowVisibility() == VISIBLE && CareMotion.allowed(getContext());
    }

    private void startPulse() {
        pulse = ValueAnimator.ofFloat(0f, 1f);
        pulse.setDuration(1400L);
        pulse.setRepeatCount(1);
        pulse.setInterpolator(new AccelerateDecelerateInterpolator());
        pulse.addUpdateListener(animation -> {
            if (state != State.LIVE || !eligible()) { stopPulse(); return; }
            phase = (float) animation.getAnimatedValue();
            invalidate();
        });
        pulse.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { stopPulse(); }
        });
        pulse.start();
    }

    private void stopPulse() {
        ValueAnimator previous = pulse;
        pulse = null;
        phase = 0f;
        if (previous != null) {
            previous.removeAllListeners();
            previous.removeAllUpdateListeners();
            previous.cancel();
        }
        invalidate();
    }

    @Override protected void onMeasure(int width, int height) {
        int size = Math.round(40f * getResources().getDisplayMetrics().density);
        setMeasuredDimension(resolveSize(size + getPaddingLeft() + getPaddingRight(), width),
                resolveSize(size + getPaddingTop() + getPaddingBottom(), height));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (pulse != null && !eligible()) stopPulse();
        float availableWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        float availableHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        float radius = Math.max(0f, Math.min(availableWidth, availableHeight) * .5f);
        float cx = getPaddingLeft() + availableWidth * .5f;
        float cy = getPaddingTop() + availableHeight * .5f;
        int color = state == State.LIVE ? BLUE : MUTED;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(state == State.LIVE ? 0xFFEAF2FF : 0xFFF2F2F7);
        canvas.drawCircle(cx, cy, radius * .95f, paint);
        if (pulse != null) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, radius * .065f));
            paint.setColor(BLUE);
            paint.setAlpha(Math.round(52f * (1f - phase)));
            canvas.drawCircle(cx, cy, radius * (.38f + .45f * phase), paint);
            paint.setAlpha(255);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, radius * .1f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(color);
        if (state == State.LIVE) {
            // Simple radio mark: a dot and two brackets indicate a data connection.
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, radius * .13f, paint);
            paint.setStyle(Paint.Style.STROKE);
            bounds.set(cx - radius * .42f, cy - radius * .42f,
                    cx + radius * .42f, cy + radius * .42f);
            canvas.drawArc(bounds, -48f, 96f, false, paint);
            canvas.drawArc(bounds, 132f, 96f, false, paint);
        } else if (state == State.OFFLINE) {
            bounds.set(cx - radius * .35f, cy - radius * .35f,
                    cx + radius * .35f, cy + radius * .35f);
            canvas.drawCircle(cx, cy, radius * .35f, paint);
            canvas.drawLine(cx - radius * .34f, cy + radius * .34f,
                    cx + radius * .34f, cy - radius * .34f, paint);
        } else {
            // Static clock covers both connecting and waiting, without implying live data.
            canvas.drawCircle(cx, cy, radius * .38f, paint);
            canvas.drawLine(cx, cy, cx, cy - radius * .21f, paint);
            canvas.drawLine(cx, cy, cx + radius * .17f, cy + radius * .07f, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    @Override protected void onDetachedFromWindow() { stopPulse(); super.onDetachedFromWindow(); }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) stopPulse();
    }
    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (visibility != VISIBLE) stopPulse();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) stopPulse();
    }

    private static void uiThread() {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("CareStatusView must be updated on the UI thread");
    }
}
