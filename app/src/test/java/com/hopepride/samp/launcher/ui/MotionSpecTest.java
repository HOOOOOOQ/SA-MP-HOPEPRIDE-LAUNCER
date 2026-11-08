package com.hopepride.samp.launcher.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MotionSpecTest {

    @Test public void staggerDelayIsZeroForFirstItem() {
        assertEquals(0L, MotionSpec.staggerDelay(0));
        assertEquals(0L, MotionSpec.staggerDelay(-3));
    }

    @Test public void staggerGrowsByStepThenCaps() {
        assertEquals(MotionSpec.STAGGER_STEP, MotionSpec.staggerDelay(1));
        assertEquals(MotionSpec.STAGGER_STEP * 2, MotionSpec.staggerDelay(2));
        assertEquals(MotionSpec.STAGGER_MAX_TOTAL, MotionSpec.staggerDelay(1000));
    }

    @Test public void reducedMotionRemovesDuration() {
        assertEquals(0L, MotionSpec.scaledDuration(MotionSpec.DURATION_HERO, true));
        assertEquals(MotionSpec.DURATION_HERO, MotionSpec.scaledDuration(MotionSpec.DURATION_HERO, false));
        assertEquals(0L, MotionSpec.scaledDuration(0, false));
        assertEquals(0L, MotionSpec.scaledDuration(-5, false));
    }

    @Test public void shouldAnimateMirrorsReducedMotion() {
        assertTrue(MotionSpec.shouldAnimate(false));
        assertFalse(MotionSpec.shouldAnimate(true));
    }

    @Test public void listEntranceDurationIsBounded() {
        assertEquals(0L, MotionSpec.listEntranceDuration(0));
        assertEquals(MotionSpec.DURATION_BASE, MotionSpec.listEntranceDuration(1));
        long many = MotionSpec.listEntranceDuration(500);
        assertEquals(MotionSpec.STAGGER_MAX_TOTAL + MotionSpec.DURATION_BASE, many);
    }

    @Test public void durationsAreOrderedFromFastToSlow() {
        assertTrue(MotionSpec.DURATION_INSTANT < MotionSpec.DURATION_FAST);
        assertTrue(MotionSpec.DURATION_FAST < MotionSpec.DURATION_BASE);
        assertTrue(MotionSpec.DURATION_BASE < MotionSpec.DURATION_MEDIUM);
        assertTrue(MotionSpec.DURATION_MEDIUM < MotionSpec.DURATION_SLOW);
        assertTrue(MotionSpec.DURATION_SLOW < MotionSpec.DURATION_HERO);
    }

    @Test public void pressScaleIsASubtleShrink() {
        assertTrue("press feedback shrinks", MotionSpec.PRESS_SCALE < 1f);
        assertTrue("press feedback stays gentle", MotionSpec.PRESS_SCALE > 0.9f);
        assertTrue("entrance starts smaller than a press", MotionSpec.ENTRANCE_SCALE < 1f);
        assertTrue("an entrance is deeper than a press, so the two read differently",
                MotionSpec.ENTRANCE_SCALE < MotionSpec.PRESS_SCALE);
    }
}
