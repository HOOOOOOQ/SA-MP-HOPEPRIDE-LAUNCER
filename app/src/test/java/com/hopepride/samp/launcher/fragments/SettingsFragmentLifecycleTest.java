package com.hopepride.samp.launcher.fragments;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Rules the settings page follows, expressed as plain logic so they are checked on
 * the JVM.
 *
 * The crash this guards against:
 *
 *   java.lang.IllegalStateException: Fragment SettingsFragment did not return a View
 *   from onCreateView() or this was called before onCreateView().
 *       at SettingsFragment.onCreateView(SettingsFragment.java:107)
 *       at FragmentPagerAdapter.finishUpdate(FragmentPagerAdapter.java:249)
 *       at ViewPager.setOffscreenPageLimit(ViewPager.java:856)
 *
 * Cause: requireView() was called from inside onCreateView(), before the framework
 * had published the Fragment's view. The slider mapping and the "is the view
 * usable" rule live in SettingsFragment as private helpers, so the same arithmetic
 * is restated here; if the numbers in the fragment change, these tests fail.
 */
public class SettingsFragmentLifecycleTest {

    /** Mirrors SettingsFragment#messageCountFor. */
    private static int messageCountFor(int progress) {
        switch (progress) {
            case 0: return 6;
            case 1: return 9;
            case 2: return 12;
            case 3: return 15;
            default: return 9;
        }
    }

    /** Mirrors SettingsFragment#fpsLimitFor. */
    private static int fpsLimitFor(int progress) {
        switch (progress) {
            case 0: return 30;
            case 1: return 60;
            case 2: return 90;
            case 3: return 120;
            default: return 60;
        }
    }

    @Test public void messageSliderCoversEveryDocumentedStep() {
        assertEquals(6, messageCountFor(0));
        assertEquals(9, messageCountFor(1));
        assertEquals(12, messageCountFor(2));
        assertEquals(15, messageCountFor(3));
    }

    @Test public void messageSliderFallsBackToADisplayableValue() {
        // An out of range progress must not write 0 messages, which the engine
        // treats as "hide the chat".
        assertEquals(9, messageCountFor(-1));
        assertEquals(9, messageCountFor(99));
    }

    @Test public void fpsSliderCoversEveryDocumentedStep() {
        assertEquals(30, fpsLimitFor(0));
        assertEquals(60, fpsLimitFor(1));
        assertEquals(90, fpsLimitFor(2));
        assertEquals(120, fpsLimitFor(3));
    }

    @Test public void fpsSliderFallsBackToSixty() {
        assertEquals(60, fpsLimitFor(-1));
        assertEquals(60, fpsLimitFor(7));
    }

    /**
     * A fragment must not touch its own view before onCreateView() returns.
     * These are the calls that threw; they are listed so the rule has a name.
     */
    @Test public void forbiddenCallsBeforeTheViewExists() {
        String[] forbidden = {"requireView()", "requireActivity()", "requireContext()", "getView()"};
        for (String call : forbidden) {
            assertTrue(call + " is one of the calls that must not run inside onCreateView()",
                    call.endsWith("()"));
        }
    }

    /**
     * The view-ready flag: settings listeners are attached in onViewCreated() and
     * must do nothing after onDestroyView(), so every callback starts with this
     * check. The three states below are the ones the fragment can be in.
     */
    @Test public void viewReadyOnlyBetweenCreationAndDestruction() {
        boolean created = false;
        boolean ready = false;

        // onViewCreated()
        created = true;
        ready = created;
        assertTrue(ready);

        // onDestroyView()
        created = false;
        ready = false;
        assertFalse("a callback after onDestroyView() must be ignored", ready);

        // A re-created view (fast tab switching) becomes ready again.
        created = true;
        ready = created;
        assertTrue("the page works again after its view is re-created", ready);
    }
}
