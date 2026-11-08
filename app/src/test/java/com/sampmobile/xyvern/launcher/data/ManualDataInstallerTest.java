package com.hopepride.samp.launcher.data;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the archive side of the manual installer.
 *
 * These run on the JVM: {@link ManualDataInstaller} validates and measures using
 * java.util.zip only, so a real zip can be produced in a temp folder and checked
 * without an Android device. The Storage Access Framework part (reading a
 * content:// Uri) is not covered here and is not claimed to be.
 */
public class ManualDataInstallerTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private File zipWith(String... names) throws IOException {
        File file = folder.newFile("pack.zip");
        try (OutputStream raw = new FileOutputStream(file);
             ZipOutputStream zip = new ZipOutputStream(raw)) {
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(new byte[]{1, 2, 3, 4});
                zip.closeEntry();
            }
        }
        return file;
    }

    @Test public void aWellFormedGameArchivePassesValidation() throws IOException {
        File zip = zipWith("files/SAMP/SAMP.ide", "files/SAMP/objects.ide");
        assertNull(ManualDataInstaller.validateArchive(zip));
    }

    @Test public void anEmptyArchiveIsRejected() throws IOException {
        File file = folder.newFile("empty.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("filler"));
            zip.closeEntry();
        }
        // A zip with only one entry is accepted; a 0 entry zip cannot be written by
        // ZipOutputStream, so this asserts the readable-but-single-entry case.
        assertNull(ManualDataInstaller.validateArchive(file));
    }

    @Test public void aFileThatIsNotAZipIsRejected() throws IOException {
        File notZip = folder.newFile("notzip.zip");
        try (OutputStream out = new FileOutputStream(notZip)) {
            out.write("this is not a zip file".getBytes("UTF-8"));
        }
        String problem = ManualDataInstaller.validateArchive(notZip);
        assertNotNull("a non-zip must be reported, not silently accepted", problem);
    }

    @Test public void zipSlipEntriesMakeTheArchiveSuspicious() throws IOException {
        File zip = zipWith("../../etc/passwd", "../../../system/bin/sh");
        String problem = ManualDataInstaller.validateArchive(zip);
        assertNotNull("an archive made only of traversal entries must be refused", problem);
    }

    @Test public void aMixedArchiveIsAcceptedButCounted() throws IOException {
        // One legitimate file plus junk: the installer installs the good part and
        // reports the rest, it does not refuse the whole pack.
        File zip = zipWith("SAMP/SAMP.ide", "../evil.sh");
        assertNull(ManualDataInstaller.validateArchive(zip));
    }

    @Test public void extractedSizeIsSummedFromTheCentralDirectory() throws IOException {
        File zip = zipWith("a.bin", "b.bin", "c.bin");
        assertEquals(12, ManualDataInstaller.estimateExtractedBytes(zip));
    }

    @Test public void entryCountIsReadWithoutExtracting() throws IOException {
        File zip = zipWith("a", "b", "c", "d");
        assertEquals(4, ManualDataInstaller.countEntries(zip));
    }

    @Test public void byteFormattingIsReadableAndNeverInventsUnits() {
        assertEquals("512 B", ManualDataInstaller.formatBytes(512));
        assertEquals("1.0 KB", ManualDataInstaller.formatBytes(1024));
        assertEquals("1.5 MB", ManualDataInstaller.formatBytes(1536L * 1024));
        assertEquals("2.0 GB", ManualDataInstaller.formatBytes(2L * 1024 * 1024 * 1024));
    }

    @Test public void contentsCanBeListedForPreview() throws IOException {
        File zip = zipWith("SAMP/SAMP.ide", "SAMP/objects.ide");
        assertEquals(2, ManualDataInstaller.describeContents(zip, 10).size());
        assertEquals(1, ManualDataInstaller.describeContents(zip, 1).size());
    }

    @Test public void copiedBytesMatchTheSource() throws IOException {
        File source = folder.newFile("source.bin");
        byte[] payload = new byte[300 * 1024];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i % 251);
        try (OutputStream out = new FileOutputStream(source)) {
            out.write(payload);
        }

        File target = folder.newFile("target.bin");
        long copied = ManualDataInstaller.copyTo(new java.io.FileInputStream(source), target);

        assertEquals(payload.length, copied);
        assertEquals(payload.length, target.length());
        assertTrue("a copy that loses the tail would break the checksum later",
                java.util.Arrays.equals(payload, java.nio.file.Files.readAllBytes(target.toPath())));
    }
}
