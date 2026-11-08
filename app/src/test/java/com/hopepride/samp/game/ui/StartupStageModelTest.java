package com.hopepride.samp.game.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StartupStageModelTest {

    @Test public void startsAtZeroAndClaimsNothing() {
        StartupStageModel model = new StartupStageModel();
        assertEquals(0, model.percent());
        assertEquals(StartupStageModel.STAGE_UNKNOWN, model.stage());
        assertFalse(model.finished());
        assertNotNull(model.label());
    }

    @Test public void neverMovesBackwards() {
        StartupStageModel model = new StartupStageModel();
        assertTrue(model.update(StartupStageModel.STAGE_RENDERWARE));
        assertFalse("stage lama tidak boleh diterima lagi",
                model.update(StartupStageModel.STAGE_GAME_INIT));
        assertEquals(StartupStageModel.STAGE_RENDERWARE, model.stage());
    }

    @Test public void ignoresOutOfRangeValues() {
        StartupStageModel model = new StartupStageModel();
        assertFalse(model.update(-1));
        assertFalse(model.update(0));
        assertFalse(model.update(StartupStageModel.STAGE_READY + 1));
        assertEquals(0, model.stage());
    }

    @Test public void onlyReportsHundredPercentWhenReallyReady() {
        StartupStageModel model = new StartupStageModel();
        model.update(StartupStageModel.STAGE_TEXTURE_DB);
        assertTrue("belum boleh 100% sebelum siap", model.percent() < 100);
        model.update(StartupStageModel.STAGE_READY);
        assertEquals(100, model.percent());
        assertTrue(model.finished());
        assertNull(model.activeLabel());
    }

    @Test public void percentIsMonotonic() {
        StartupStageModel model = new StartupStageModel();
        int previous = 0;
        for (int stage = 1; stage <= StartupStageModel.STAGE_READY; stage++) {
            model.update(stage);
            assertTrue("progress tidak boleh turun", model.percent() >= previous);
            previous = model.percent();
        }
    }

    @Test public void labelsDescribeTheReportedStage() {
        assertEquals("Memeriksa data game", StartupStageModel.labelFor(StartupStageModel.STAGE_DATA_CHECK));
        assertEquals("Menyiapkan RenderWare", StartupStageModel.labelFor(StartupStageModel.STAGE_RENDERWARE));
        assertEquals("Memuat texture database", StartupStageModel.labelFor(StartupStageModel.STAGE_TEXTURE_DB));
        assertEquals("Menghubungkan ke server", StartupStageModel.labelFor(StartupStageModel.STAGE_CONNECT));
        assertEquals("Masuk ke game", StartupStageModel.labelFor(StartupStageModel.STAGE_READY));
    }

    @Test public void completedStepsNeverExceedTotal() {
        StartupStageModel model = new StartupStageModel();
        for (int stage = 1; stage <= StartupStageModel.STAGE_READY; stage++) {
            model.update(stage);
            assertTrue(model.completedSteps() <= model.totalSteps());
        }
    }
}
