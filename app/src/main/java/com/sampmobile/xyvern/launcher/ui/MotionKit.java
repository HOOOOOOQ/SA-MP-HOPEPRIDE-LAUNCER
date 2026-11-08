package com.hopepride.samp.launcher.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;

import java.util.WeakHashMap;

/**
 * The single entry point every launcher animation goes through.
 *
 * Why a helper instead of calling view.animate() directly everywhere:
 *
 *   * one place reads the "kurangi animasi" preference, so no component can
 *     forget it and produce motion for a user who asked not to have any;
 *   * the same interpolators are reused, which is what makes separate screens
 *     feel like one app;
 *   * view state is reset when a run is cancelled, so a fast tab switch cannot
 *     leave a card stuck at 0.96 scale or half transparent;
 *   * press feedback is implemented once, with the ripple kept intact.
 *
 * Only API 26+ is targeted by the app, so no compatibility shims are needed.
 */
public final class MotionKit {

    public static final String UI_PREFS = "hopepride_ui";
    public static final String KEY_REDUCED_MOTION = "reduce_motion";

    /** Standard deceleration: quick start, soft landing. */
    private static final TimeInterpolator ENTER = new DecelerateInterpolator(1.6f);
    /** Slight overshoot for interactive confirmation (press release). */
    private static final TimeInterpolator SPRING = new OvershootInterpolator(0.9f);
    /** Material 3 emphasised curve, for page level transitions. */
    private static final TimeInterpolator EMPHASIS =
            new PathInterpolator(0.2f, 0f, 0f, 1f);

    private static Boolean sReducedMotion;
    private static SharedPreferences sPrefs;

    private MotionKit() { }

    // ------------------------------------------------------------ settings --

    public static void init(Context context) {
        if (context == null) return;
        sPrefs = context.getApplicationContext()
                .getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE);
        sReducedMotion = sPrefs.getBoolean(KEY_REDUCED_MOTION, false);
    }

    /** True when the user asked for less motion (accessibility / battery). */
    public static boolean reducedMotion(Context context) {
        if (sReducedMotion == null) init(context);
        if (sReducedMotion == null) return false;
        return sReducedMotion;
    }

    public static void setReducedMotion(Context context, boolean reduced) {
        init(context);
        sReducedMotion = reduced;
        if (sPrefs != null) sPrefs.edit().putBoolean(KEY_REDUCED_MOTION, reduced).apply();
    }

    public static boolean shouldAnimate(Context context) {
        return !reducedMotion(context);
    }

    /** Duration adjusted for the preference: 0 when motion is off. */
    public static long duration(Context context, long baseMs) {
        return reducedMotion(context) ? 0L : baseMs;
    }

    // ---------------------------------------------------------- interpolators

    public static TimeInterpolator enterInterpolator() { return ENTER; }
    public static TimeInterpolator springInterpolator() { return SPRING; }
    public static TimeInterpolator emphasisInterpolator() { return EMPHASIS; }

    // -------------------------------------------------------------- entrances

    /**
     * Fade + slide + optional scale entrance, staggered by index.
     * The view is left at its final state, so a cancelled run never leaves it
     * invisible.
     */
    public static void enter(View view, int index, long baseDuration, float startScale) {
        if (view == null) return;
        final Context context = view.getContext();
        if (!shouldAnimate(context)) {
            reset(view);
            view.setAlpha(1f);
            return;
        }

        view.setAlpha(0f);
        view.setTranslationY(dp(context, MotionSpec.SLIDE_DP));
        if (startScale != 1f) {
            view.setScaleX(startScale);
            view.setScaleY(startScale);
        }

        view.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(MotionSpec.staggerDelay(index))
                .setDuration(baseDuration <= 0 ? MotionSpec.DURATION_MEDIUM : baseDuration)
                .setInterpolator(ENTER)
                .start();
    }

    /** Default entrance for a list of children. */
    public static void staggerChildren(ViewGroup group) {
        if (group == null) return;
        final Context context = group.getContext();
        if (!shouldAnimate(context)) {
            for (int i = 0; i < group.getChildCount(); i++) reset(group.getChildAt(i));
            return;
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            enter(group.getChildAt(i), i, MotionSpec.DURATION_MEDIUM, MotionSpec.ENTRANCE_SCALE);
        }
    }

    /** Restores a view to its resting state and cancels any running animation. */
    public static void reset(View view) {
        if (view == null) return;
        view.animate().cancel();
        view.setAlpha(1f);
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setRotation(0f);
    }

    // --------------------------------------------------------------- feedback

    /**
     * Adds press feedback: a small shrink on touch down, spring back on up,
     * without consuming the touch so click listeners and ripples still work.
     */
    public static void attachPressFeedback(final View view) {
        if (view == null) return;
        final Context context = view.getContext();
        if (!shouldAnimate(context)) return;
        if (PRESS_ATTACHED.containsKey(view)) return;
        PRESS_ATTACHED.put(view, Boolean.TRUE);

        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(MotionSpec.PRESS_SCALE).scaleY(MotionSpec.PRESS_SCALE)
                            .setDuration(MotionSpec.DURATION_INSTANT)
                            .setInterpolator(ENTER).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(MotionSpec.DURATION_FAST)
                            .setInterpolator(SPRING).start();
                    break;
                default:
                    break;
            }
            return false;   // never swallow the event
        });
    }

    private static final WeakHashMap<View, Boolean> PRESS_ATTACHED = new WeakHashMap<>();

    /** Pulse used to draw attention to a status change (resets itself). */
    public static void pulse(View view) {
        if (view == null) return;
        if (!shouldAnimate(view.getContext())) return;
        view.animate().cancel();
        view.animate()
                .scaleX(1.06f).scaleY(1.06f)
                .setDuration(MotionSpec.DURATION_FAST)
                .setInterpolator(ENTER)
                .withEndAction(() -> view.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(MotionSpec.DURATION_BASE)
                        .setInterpolator(SPRING)
                        .start())
                .start();
    }

    /** Cross-fade one view out and another in, used by the dashboard tabs. */
    public static void crossFade(final View from, final View to, long durationMs) {
        if (to == null) return;
        final Context context = to.getContext();
        if (!shouldAnimate(context)) {
            if (from != null) from.setVisibility(View.GONE);
            to.setVisibility(View.VISIBLE);
            to.setAlpha(1f);
            return;
        }
        if (from != null && from != to) {
            from.animate().alpha(0f).setDuration(durationMs / 2).start();
        }
        to.setAlpha(0f);
        to.setVisibility(View.VISIBLE);
        to.animate().alpha(1f).setDuration(durationMs).setInterpolator(ENTER).start();
    }

    /** Animates a progress bar to a real value, never bouncing backwards. */
    public static void animateProgress(final android.widget.ProgressBar bar, int target) {
        if (bar == null) return;
        final Context context = bar.getContext();
        if (!shouldAnimate(context)) {
            bar.setProgress(target);
            return;
        }
        final int current = bar.getProgress();
        if (target <= current) {
            bar.setProgress(target);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofInt(current, target);
        animator.setDuration(MotionSpec.DURATION_MEDIUM);
        animator.setInterpolator(ENTER);
        animator.addUpdateListener(a -> bar.setProgress((Integer) a.getAnimatedValue()));
        animator.start();
    }

    /**
     * One-shot number count-up, used for the dashboard statistics. Respects the
     * preference by setting the final value immediately.
     */
    public static void animateCount(final android.widget.TextView view, int from, int to) {
        if (view == null) return;
        final Context context = view.getContext();
        if (!shouldAnimate(context)) {
            view.setText(String.valueOf(to));
            return;
        }
        ValueAnimator animator = ValueAnimator.ofInt(from, to);
        animator.setDuration(MotionSpec.DURATION_SLOW);
        animator.setInterpolator(ENTER);
        animator.addUpdateListener(a -> view.setText(String.valueOf(a.getAnimatedValue())));
        animator.start();
    }

    /** Adds a listener that runs after an animation ends, cancelling safely. */
    public static void onEnd(Animator animator, final Runnable action) {
        if (animator == null || action == null) return;
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { action.run(); }
        });
    }

    public static int dp(Context context, float value) {
        if (context == null) return Math.round(value);
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
