package com.hopepride.samp.launcher.data;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DataIntegrityTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static final String ABC_SHA256 =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private File write(String name, String content) throws Exception {
        File file = new File(folder.getRoot(), name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    @Test public void hashesKnownContent() throws Exception {
        File file = write("abc.txt", "abc");
        assertEquals(ABC_SHA256, DataIntegrity.sha256(file));
    }

    @Test public void hashOfBytesMatchesHashOfFile() throws Exception {
        File file = write("abc2.txt", "abc");
        assertEquals(DataIntegrity.sha256("abc".getBytes(StandardCharsets.UTF_8)),
                DataIntegrity.sha256(file));
    }

    @Test public void normalizesDigestForms() {
        assertEquals(ABC_SHA256, DataIntegrity.normalizeSha256(ABC_SHA256.toUpperCase()));
        assertEquals(ABC_SHA256, DataIntegrity.normalizeSha256("sha256:" + ABC_SHA256));
        assertEquals(ABC_SHA256, DataIntegrity.normalizeSha256("  " + ABC_SHA256 + " "));
        assertNull(DataIntegrity.normalizeSha256("bukan-hex"));
        assertNull(DataIntegrity.normalizeSha256(null));
        assertNull(DataIntegrity.normalizeSha256(""));
    }

    @Test public void reportsMissingFile() {
        DataIntegrity.FileStatus status =
                DataIntegrity.check(folder.getRoot(), "tidak/ada.dat", 0, null);
        assertEquals(DataIntegrity.State.MISSING, status.state);
        assertTrue(status.needsDownload());
        assertFalse(status.isHealthy());
    }

    @Test public void reportsEmptyFileAsTruncated() throws Exception {
        write("kosong.dat", "");
        DataIntegrity.FileStatus status =
                DataIntegrity.check(folder.getRoot(), "kosong.dat", 0, null);
        assertEquals(DataIntegrity.State.TRUNCATED, status.state);
        assertTrue(status.needsDownload());
    }

    @Test public void reportsWrongSizeAsTruncated() throws Exception {
        write("data.dat", "abc");
        DataIntegrity.FileStatus status =
                DataIntegrity.check(folder.getRoot(), "data.dat", 99, ABC_SHA256);
        assertEquals(DataIntegrity.State.TRUNCATED, status.state);
        assertTrue(status.detail.contains("seharusnya"));
    }

    @Test public void acceptsMatchingDigestAndSize() throws Exception {
        write("data.dat", "abc");
        DataIntegrity.FileStatus status =
                DataIntegrity.check(folder.getRoot(), "data.dat", 3, ABC_SHA256);
        assertEquals(DataIntegrity.State.OK, status.state);
        assertTrue(status.isHealthy());
        assertFalse(status.needsDownload());
    }

    @Test public void flagsCorruptContentEvenWhenSizeMatches() throws Exception {
        write("data.dat", "abd");
        DataIntegrity.FileStatus status =
                DataIntegrity.check(folder.getRoot(), "data.dat", 3, ABC_SHA256);
        assertEquals(DataIntegrity.State.CORRUPT, status.state);
        assertTrue(status.needsDownload());
    }

    @Test public void reportsUnverifiedWhenNoChecksumIsKnown() throws Exception {
        write("data.dat", "abc");
        DataIntegrity.FileStatus status =
                DataIntegrity.check(folder.getRoot(), "data.dat", 3, null);
        assertEquals(DataIntegrity.State.UNVERIFIED, status.state);
        // Unverified is not healthy: the UI must not paint it green.
        assertFalse(status.isHealthy());
        // But it must not trigger a download either, since nothing is known bad.
        assertFalse(status.needsDownload());
    }

    @Test public void unreadablePathIsReportedNotCrashed() {
        DataIntegrity.FileStatus status = DataIntegrity.check(null, null, 0, null);
        assertEquals(DataIntegrity.State.MISSING, status.state);
    }

    @Test public void hexEncodingIsLowercaseAndPadded() {
        assertEquals("00ff10", DataIntegrity.toHex(new byte[]{0x00, (byte) 0xFF, 0x10}));
        assertNull(DataIntegrity.toHex(null));
    }

    @Test public void missingFileHashIsNull() {
        assertNull(DataIntegrity.sha256(new File(folder.getRoot(), "nope.bin")));
        assertNotNull(DataIntegrity.sha256("abc".getBytes(StandardCharsets.UTF_8)));
    }
}
