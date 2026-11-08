package com.hopepride.samp.game.ui;

/**
 * What the game loading screen is allowed to display.
 *
 * Two rules drive this class:
 *
 *   1. A stage is only marked done when the native side reported it. The values
 *      mirror GameStartupStage.h; nothing here advances on a timer.
 *   2. Progress is derived from completed stages, so the bar cannot reach 100%
 *      while the game is still loading, and cannot move backwards.
 *
 * Pure Java so the ordering rules are unit tested without a device.
 */
public final class StartupStageModel {

    public static final int STAGE_UNKNOWN = 0;
    public static final int STAGE_DATA_CHECK = 1;
    public static final int STAGE_CONFIG = 2;
    public static final int STAGE_GAME_INIT = 3;
    public static final int STAGE_RENDERWARE = 4;
    public static final int STAGE_TEXTURE_DB = 5;
    public static final int STAGE_TEXTURES = 6;
    public static final int STAGE_CONNECT = 7;
    public static final int STAGE_READY = 8;

    private static final String[] LABELS = {
            "Menyiapkan...",
            "Memeriksa data game",
            "Memuat konfigurasi",
            "Menyiapkan game",
            "Menyiapkan RenderWare",
            "Memuat texture database",
            "Menyiapkan tekstur",
            "Menghubungkan ke server",
            "Masuk ke game"
    };

    /** Stage reached so far. Only ever increases. */
    private int stage = STAGE_UNKNOWN;

    public int stage() {
        return stage;
    }

    /**
     * Applies a stage reported by the native layer.
     *
     * @return true when the value advanced, so the caller knows to animate
     */
    public boolean update(int reported) {
        if (reported <= STAGE_UNKNOWN || reported > STAGE_READY) return false;
        if (reported <= stage) return false;
        stage = reported;
        return true;
    }

    /** Label for the current stage. Never claims a stage that did not happen. */
    public String label() {
        return LABELS[stage];
    }

    /** Label of the step being worked on right now, or null when finished. */
    public String activeLabel() {
        if (stage >= STAGE_READY) return null;
        return LABELS[stage];
    }

    /**
     * Progress in 0..100 derived from stages, not from time.
     * STAGE_READY is the only value that reports 100.
     */
    public int percent() {
        if (stage <= STAGE_UNKNOWN) return 0;
        return Math.min(100, (stage * 100) / STAGE_READY);
    }

    public boolean finished() {
        return stage >= STAGE_READY;
    }

    /** Steps already completed, for the checklist UI. */
    public int completedSteps() {
        return Math.max(0, stage - 1);
    }

    public int totalSteps() {
        return STAGE_READY - 1;
    }

    public void reset() {
        stage = STAGE_UNKNOWN;
    }

    public static String labelFor(int value) {
        if (value < 0 || value >= LABELS.length) return LABELS[0];
        return LABELS[value];
    }
}
