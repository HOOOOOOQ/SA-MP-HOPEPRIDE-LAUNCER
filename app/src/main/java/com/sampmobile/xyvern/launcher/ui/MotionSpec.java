package com.hopepride.samp.launcher.ui;

/**
 * Central motion token set for the whole launcher.
 *
 * Every animation in the app reads its duration and delay from here instead of
 * picking its own numbers, which is what makes the UI feel like one system rather
 * than a collection of unrelated fades. Values are plain longs so the rules can
 * be unit tested without an Android runtime; the Android layer only converts them
 * to interpolators and animators.
 *
 * Timing is intentionally short. A premium feel comes from consistent, springy,
 * well-staggered motion, not from long animations the user has to wait for.
 */
public final class MotionSpec {

    private MotionSpec() { }

    // ---------------------------------------------------------- durations (ms)

    /** One tap of feedback (press scale, ripple). */
    public static final long DURATION_INSTANT = 90L;
    /** Small component state change (switch, checkbox, chip). */
    public static final long DURATION_FAST = 140L;
    /** Default for most transitions. */
    public static final long DURATION_BASE = 220L;
    /** Card entrance, dialog, sheet. */
    public static final long DURATION_MEDIUM = 320L;
    /** Page transition, shared element travel. */
    public static final long DURATION_SLOW = 420L;
    /** Splash logo and background reveal. */
    public static final long DURATION_HERO = 620L;

    // ---------------------------------------------------------- delays (ms)

    /** Gap between staggered siblings. */
    public static final long STAGGER_STEP = 45L;
    /** Cap so a long list does not animate for seconds. */
    public static final long STAGGER_MAX_TOTAL = 360L;

    // ---------------------------------------------------------- geometry

    /** Pressed scale for buttons and cards. */
    /**
     * Press feedback is the gentlest scale in the system: a card that shrinks as
     * far as an entrance looks like it is collapsing rather than responding.
     */
    public static final float PRESS_SCALE = 0.97f;
    /** Entrance scale for cards (grows to 1f). */
    /** Entrance starts here and settles at 1.0; always deeper than a press. */
    public static final float ENTRANCE_SCALE = 0.94f;
    /** Parallax travel for the dashboard hero, in dp. */
    public static final float PARALLAX_DP = 28f;
    /** Vertical travel for entrance slides, in dp. */
    public static final float SLIDE_DP = 18f;

    /**
     * Stagger delay for item `index`.
     *
     * Capped at STAGGER_MAX_TOTAL so a 40 item list still finishes quickly, and
     * never negative.
     */
    public static long staggerDelay(int index) {
        if (index <= 0) return 0L;
        long delay = index * STAGGER_STEP;
        return Math.min(delay, STAGGER_MAX_TOTAL);
    }

    /**
     * Fraction of the motion that should remain when the user asked for reduced
     * motion. Zero, not "a little", because the point of the preference is to
     * stop movement.
     */
    public static long scaledDuration(long durationMs, boolean reducedMotion) {
        if (durationMs <= 0) return 0L;
        return reducedMotion ? 0L : durationMs;
    }

    /**
     * True when an animator may run. Used as the single guard so no component can
     * forget to check the preference.
     */
    public static boolean shouldAnimate(boolean reducedMotion) {
        return !reducedMotion;
    }

    /** Duration for a list entrance, bounded by the stagger cap. */
    public static long listEntranceDuration(int itemCount) {
        if (itemCount <= 0) return 0L;
        long last = staggerDelay(itemCount - 1);
        return last + DURATION_BASE;
    }
}
