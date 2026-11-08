package com.hopepride.samp.launcher.data;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DataManifestTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static final String ABC_SHA256 =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private static void write(File root, String name, String content) throws Exception {
        File file = new File(root, name);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) assertTrue(parent.mkdirs());
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test public void parsesVersionArchiveAndFiles() throws Exception {
        String json = "{"
                + "\"data_version\":\"3\","
                + "\"archive\":{\"size\":1234,\"sha256\":\"" + ABC_SHA256 + "\"},"
                + "\"files\":["
                + "  {\"path\":\"data/gta.dat\",\"size\":3,\"sha256\":\"" + ABC_SHA256 + "\"},"
                + "  {\"path\":\"texdb/player/player.dxt.tmb\",\"size\":10}"
                + "]}";

        DataManifest manifest = DataManifest.parse(json);

        assertEquals("3", manifest.dataVersion);
        assertEquals(1234, manifest.archiveSize);
        assertEquals(ABC_SHA256, manifest.archiveSha256);
        assertEquals(2, manifest.files.size());
        assertEquals("data/gta.dat", manifest.files.get(0).path);
        assertEquals(3, manifest.files.get(0).size);
        assertNull(manifest.files.get(1).sha256);
    }

    @Test public void ignoresEntriesTheArchivePolicyWouldRefuse() {
        String json = "{\"files\":["
                + "{\"path\":\"../evil.txt\"},"
                + "{\"path\":\"payload.so\"},"
                + "{\"path\":\"data/ok.dat\"}"
                + "]}";

        DataManifest manifest;
        try {
            manifest = DataManifest.parse(json);
        } catch (Exception error) {
            throw new AssertionError(error);
        }

        assertEquals(1, manifest.files.size());
        assertEquals("data/ok.dat", manifest.files.get(0).path);
    }

    @Test public void emptyInputGivesEmptyManifest() throws Exception {
        assertTrue(DataManifest.parse(null).isEmpty());
        assertTrue(DataManifest.parse("").isEmpty());
        assertTrue(DataManifest.empty().isEmpty());
    }

    @Test public void verifiesInstalledFilesAndFindsProblems() throws Exception {
        write(folder.getRoot(), "data/gta.dat", "abc");
        write(folder.getRoot(), "data/gta_int.dat", "xyz");

        String json = "{\"files\":["
                + "{\"path\":\"data/gta.dat\",\"size\":3,\"sha256\":\"" + ABC_SHA256 + "\"},"
                + "{\"path\":\"data/gta_int.dat\",\"size\":3,\"sha256\":\"" + ABC_SHA256 + "\"},"
                + "{\"path\":\"texdb/missing.tmb\",\"size\":5}"
                + "]}";

        DataManifest manifest = DataManifest.parse(json);
        List<DataIntegrity.FileStatus> statuses = manifest.verify(folder.getRoot());
        assertEquals(3, statuses.size());
        assertEquals(DataIntegrity.State.OK, statuses.get(0).state);
        assertEquals(DataIntegrity.State.CORRUPT, statuses.get(1).state);
        assertEquals(DataIntegrity.State.MISSING, statuses.get(2).state);

        List<DataIntegrity.FileStatus> problems = manifest.problems(folder.getRoot());
        assertEquals("hanya berkas bermasalah yang masuk daftar perbaikan", 2, problems.size());
    }

    @Test public void normalisesLeadingSlashesAndBackslashes() {
        assertEquals("data/gta.dat", DataManifest.normalizePath("/data/gta.dat"));
        assertEquals("data/gta.dat", DataManifest.normalizePath("data\\gta.dat"));
        assertEquals("", DataManifest.normalizePath(null));
    }

    @Test public void malformedFileArrayDoesNotThrow() throws Exception {
        DataManifest manifest = DataManifest.parse("{\"files\":\"nope\"}");
        assertTrue(manifest.isEmpty());
        assertFalse(manifest.dataVersion.equals("3"));
    }
}
