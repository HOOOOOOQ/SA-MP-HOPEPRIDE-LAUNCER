package com.hopepride.samp.launcher.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manifest of the installed game data: what must exist, how big it is, and what
 * its SHA-256 is.
 *
 * The manifest is optional. Without one the launcher still verifies the essential
 * file list; with one it can answer "which single file is broken" instead of
 * "download 700 MB again", which is what the repair flow needs.
 *
 * Expected JSON (served next to the game data, or shipped in the APK assets):
 *
 * {
 *   "data_version": "3",
 *   "archive": { "size": 734003200, "sha256": "..." },
 *   "files": [
 *     { "path": "texdb/player/player.dxt.tmb", "size": 1234, "sha256": "..." },
 *     { "path": "data/gta.dat", "size": 100 }
 *   ]
 * }
 *
 * Pure Java so the parsing rules are unit tested without a device.
 */
public final class DataManifest {

    public static final class Entry {
        public final String path;
        public final long size;
        public final String sha256;

        public Entry(String path, long size, String sha256) {
            this.path = normalizePath(path);
            this.size = size;
            this.sha256 = DataIntegrity.normalizeSha256(sha256);
        }

        @Override
        public String toString() {
            return path + " (" + size + " B)";
        }
    }

    public final String dataVersion;
    public final long archiveSize;
    public final String archiveSha256;
    public final List<Entry> files;

    private DataManifest(String dataVersion, long archiveSize, String archiveSha256, List<Entry> files) {
        this.dataVersion = dataVersion;
        this.archiveSize = archiveSize;
        this.archiveSha256 = archiveSha256;
        this.files = Collections.unmodifiableList(files);
    }

    /** Empty manifest: nothing is known, everything is "unverified". */
    public static DataManifest empty() {
        return new DataManifest("", 0, null, new ArrayList<Entry>());
    }

    public boolean isEmpty() {
        return files.isEmpty();
    }

    /**
     * Parses a manifest. Unknown or malformed entries are skipped instead of
     * failing the whole document: a single bad line must not block an install.
     */
    public static DataManifest parse(String json) throws Exception {
        if (json == null || json.trim().isEmpty()) return empty();

        JSONObject root = new JSONObject(json);

        String version = root.optString("data_version", "");
        long archiveSize = 0;
        String archiveSha = null;
        JSONObject archive = root.optJSONObject("archive");
        if (archive != null) {
            archiveSize = archive.optLong("size", 0);
            archiveSha = DataIntegrity.normalizeSha256(archive.optString("sha256", null));
        }

        List<Entry> entries = new ArrayList<>();
        JSONArray files = root.optJSONArray("files");
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject item = files.optJSONObject(i);
                if (item == null) continue;
                String path = item.optString("path", "");
                if (path.isEmpty()) continue;
                // Refuse a manifest entry that the archive policy would refuse:
                // it can never be satisfied and would make repair loop forever.
                if (!ArchivePolicy.judge(path, "").accept) continue;
                entries.add(new Entry(path, item.optLong("size", 0),
                        item.optString("sha256", null)));
            }
        }

        return new DataManifest(version, archiveSize, archiveSha, entries);
    }

    /** Status for every manifest entry, in manifest order. */
    public List<DataIntegrity.FileStatus> verify(java.io.File root) {
        List<DataIntegrity.FileStatus> statuses = new ArrayList<>(files.size());
        for (Entry entry : files) {
            statuses.add(DataIntegrity.check(root, entry.path, entry.size, entry.sha256));
        }
        return statuses;
    }

    /** Only the entries that need action, which is what repair downloads. */
    public List<DataIntegrity.FileStatus> problems(java.io.File root) {
        List<DataIntegrity.FileStatus> problems = new ArrayList<>();
        for (DataIntegrity.FileStatus status : verify(root)) {
            if (!status.isHealthy() && status.needsDownload()) problems.add(status);
        }
        return problems;
    }

    static String normalizePath(String path) {
        if (path == null) return "";
        String normalized = ArchivePolicy.normalize(path);
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        return normalized;
    }
}
