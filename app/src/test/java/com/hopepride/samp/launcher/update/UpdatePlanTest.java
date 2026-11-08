package com.hopepride.samp.launcher.update;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UpdatePlanTest {

    @Test public void healthyDataWithMarkerNeedsNothing() {
        UpdatePlan plan = UpdatePlan.decide(100, 0, 0, 700_000_000L, true, 0);
        assertEquals(UpdatePlan.Action.NOTHING, plan.action);
        assertFalse(plan.needsNetwork());
    }

    @Test public void healthyDataWithoutMarkerOnlyRewritesTheMarker() {
        UpdatePlan plan = UpdatePlan.decide(100, 0, 0, 700_000_000L, false, 0);
        assertEquals(UpdatePlan.Action.REBUILD_MANIFEST, plan.action);
        assertFalse("tidak perlu unduh ulang bila hanya penanda hilang", plan.needsNetwork());
    }

    @Test public void fewBrokenFilesArePatchedInsteadOfRedownloadingEverything() {
        UpdatePlan plan = UpdatePlan.decide(100, 3, 5_000_000L, 700_000_000L, true, 2_000_000_000L);
        assertEquals(UpdatePlan.Action.PATCH_FILES, plan.action);
        assertEquals(3, plan.filesToFetch);
        assertEquals(5_000_000L, plan.bytesToFetch);
        assertTrue(plan.needsNetwork());
    }

    @Test public void manyBrokenFilesFallBackToTheFullArchive() {
        // Repair would fetch the archive size or more, so the archive wins.
        UpdatePlan plan = UpdatePlan.decide(100, 90, 690_000_000L, 700_000_000L, true, 2_000_000_000L);
        assertEquals(UpdatePlan.Action.FULL_ARCHIVE, plan.action);
    }

    @Test public void allFilesBrokenMeansFullArchive() {
        UpdatePlan plan = UpdatePlan.decide(100, 100, 10_000_000L, 700_000_000L, true, 2_000_000_000L);
        assertEquals(UpdatePlan.Action.FULL_ARCHIVE, plan.action);
    }

    @Test public void blocksWhenStorageIsTooSmallForThePatch() {
        UpdatePlan plan = UpdatePlan.decide(100, 2, 50_000_000L, 700_000_000L, true, 10_000_000L);
        assertEquals(UpdatePlan.Action.BLOCKED_NO_SPACE, plan.action);
        assertFalse(plan.needsNetwork());
    }

    @Test public void blocksWhenStorageIsTooSmallForTheArchive() {
        UpdatePlan plan = UpdatePlan.decide(100, 100, 10_000_000L, 700_000_000L, true, 100L);
        assertEquals(UpdatePlan.Action.BLOCKED_NO_SPACE, plan.action);
    }

    @Test public void unknownManifestFallsBackToTheArchive() {
        UpdatePlan plan = UpdatePlan.decide(0, 0, 0, 700_000_000L, false, 0);
        assertEquals(UpdatePlan.Action.FULL_ARCHIVE, plan.action);
        assertTrue(plan.reason.contains("manifest"));
    }

    @Test public void unknownSizesDoNotBlockAValidPatch() {
        UpdatePlan plan = UpdatePlan.decide(100, 1, 0, 0, true, 0);
        assertEquals(UpdatePlan.Action.PATCH_FILES, plan.action);
    }
}
