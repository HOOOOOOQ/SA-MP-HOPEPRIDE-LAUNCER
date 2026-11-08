package com.hopepride.samp.launcher.update;

/**
 * Decides what a data-update run has to do, so "repair" never means "download the
 * whole archive again".
 *
 * Inputs are counts and flags from the verifier; the decision is pure logic:
 *
 *   * everything healthy                      -> NOTHING
 *   * only a few files bad, archive available -> PATCH_FILES (per-file repair)
 *   * the archive itself is missing/corrupt   -> FULL_ARCHIVE
 *   * data present but a manifest file missing -> REBUILD_MANIFEST
 *
 * Pure Java so the table is unit tested.
 */
public final class UpdatePlan {

    public enum Action {
        /** Nothing to do. */
        NOTHING,
        /** Re-download only the files that failed verification. */
        PATCH_FILES,
        /** Re-download and re-extract the full archive. */
        FULL_ARCHIVE,
        /** Install finished but the manifest/marker is missing; just rewrite it. */
        REBUILD_MANIFEST,
        /** Storage cannot hold what the update needs. */
        BLOCKED_NO_SPACE
    }

    public final Action action;
    public final int filesToFetch;
    public final long bytesToFetch;
    public final String reason;

    private UpdatePlan(Action action, int filesToFetch, long bytesToFetch, String reason) {
        this.action = action;
        this.filesToFetch = filesToFetch;
        this.bytesToFetch = bytesToFetch;
        this.reason = reason == null ? "" : reason;
    }

    /**
     * @param totalFiles       manifest entries checked
     * @param brokenFiles      entries that need action
     * @param brokenBytes      sum of expected sizes of the broken entries
     * @param archiveBytes     size of the full archive (0 when unknown)
     * @param markerPresent    the .data_ready marker exists
     * @param availableBytes   free space at the destination (0 when unknown)
     */
    public static UpdatePlan decide(int totalFiles, int brokenFiles, long brokenBytes,
                                    long archiveBytes, boolean markerPresent,
                                    long availableBytes) {
        if (totalFiles <= 0) {
            return new UpdatePlan(Action.FULL_ARCHIVE, 0, archiveBytes,
                    "manifest belum tersedia, paket lengkap diperlukan");
        }

        if (brokenFiles <= 0) {
            if (!markerPresent) {
                return new UpdatePlan(Action.REBUILD_MANIFEST, 0, 0,
                        "semua berkas utuh, hanya penanda instalasi yang hilang");
            }
            return new UpdatePlan(Action.NOTHING, 0, 0, "data sudah lengkap");
        }

        // Repairing individual files is only worthwhile when it saves a real
        // amount of traffic. Fetching 98% of the archive as individual files is
        // slower than one request and buys nothing, so the threshold is 80%.
        boolean patchIsCheaper = archiveBytes <= 0 || brokenBytes <= 0
                || brokenBytes <= (archiveBytes / 10) * 8;

        if (patchIsCheaper && brokenFiles < totalFiles) {
            if (availableBytes > 0 && brokenBytes > 0 && brokenBytes > availableBytes) {
                return new UpdatePlan(Action.BLOCKED_NO_SPACE, brokenFiles, brokenBytes,
                        "ruang penyimpanan tidak cukup untuk perbaikan");
            }
            return new UpdatePlan(Action.PATCH_FILES, brokenFiles, brokenBytes,
                    brokenFiles + " berkas perlu diunduh ulang");
        }

        if (availableBytes > 0 && archiveBytes > 0 && archiveBytes > availableBytes) {
            return new UpdatePlan(Action.BLOCKED_NO_SPACE, 0, archiveBytes,
                    "ruang penyimpanan tidak cukup untuk paket lengkap");
        }
        return new UpdatePlan(Action.FULL_ARCHIVE, 0, archiveBytes,
                "terlalu banyak berkas rusak, paket lengkap lebih efisien");
    }

    public boolean needsNetwork() {
        return action == Action.PATCH_FILES || action == Action.FULL_ARCHIVE;
    }

    @Override
    public String toString() {
        return action + " (" + reason + ")";
    }
}
