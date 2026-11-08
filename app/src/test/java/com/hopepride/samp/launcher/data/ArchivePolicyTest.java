package com.hopepride.samp.launcher.data;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ArchivePolicyTest {

    @Test public void acceptsOrdinaryGameDataEntries() {
        assertTrue(ArchivePolicy.judge("data/handling.cfg", "").accept);
        assertTrue(ArchivePolicy.judge("texdb/player/player.dxt.tmb", "").accept);
        assertTrue(ArchivePolicy.judge("models/gta3.img", "").accept);
        assertTrue(ArchivePolicy.judge("SAMP/settings.ini", "").accept);
        assertTrue(ArchivePolicy.judge("audio/CONFIG/BankSlot.dat", "").accept);
        assertTrue(ArchivePolicy.judge("texdb/player/", "").accept);
    }

    @Test public void refusesPathTraversal() {
        ArchivePolicy.Decision decision = ArchivePolicy.judge("../evil.txt", "");
        assertFalse(decision.accept);
        assertTrue(decision.reason.contains("keluar"));

        assertFalse(ArchivePolicy.judge("data/../../escape.bin", "").accept);
    }

    @Test public void refusesAbsolutePaths() {
        assertFalse(ArchivePolicy.judge("/sdcard/evil.txt", "").accept);
        assertFalse(ArchivePolicy.judge("\\windows\\evil.dll", "").accept);
        assertFalse(ArchivePolicy.judge("C:/Windows/evil.exe", "").accept);
        assertFalse(ArchivePolicy.judge("d:evil.apk", "").accept);
    }

    @Test public void refusesExecutables() {
        assertFalse(ArchivePolicy.judge("lib/arm64-v8a/libevil.so", "").accept);
        assertFalse(ArchivePolicy.judge("payload.apk", "").accept);
        assertFalse(ArchivePolicy.judge("update.dex", "").accept);
        assertFalse(ArchivePolicy.judge("run.sh", "").accept);
        assertFalse(ArchivePolicy.judge("installer.exe", "").accept);
        assertFalse(ArchivePolicy.judge("tool.bat", "").accept);
    }

    @Test public void doesNotRefuseHarmlessNamesThatContainBlockedSubstrings() {
        // A texture called "shadow.dxt.tmb" must not be mistaken for "sh".
        assertTrue(ArchivePolicy.judge("texdb/samp/shadow.dxt.tmb", "").accept);
        assertTrue(ArchivePolicy.judge("data/exe.dat", "").accept);
        assertTrue(ArchivePolicy.judge("models/bin.ide", "").accept);
    }

    @Test public void refusesSystemFolders() {
        assertFalse(ArchivePolicy.judge("META-INF/MANIFEST.MF", "").accept);
        assertFalse(ArchivePolicy.judge("system/build.prop", "").accept);
        assertFalse(ArchivePolicy.judge("data/data/com.other/files/x", "").accept);
    }

    @Test public void refusesControlCharactersAndNul() {
        assertFalse(ArchivePolicy.judge("bad\u0000name.dat", "").accept);
        assertFalse(ArchivePolicy.judge("bad\u0007name.dat", "").accept);
    }

    @Test public void refusesOverlongNames() {
        StringBuilder longName = new StringBuilder("data/");
        for (int i = 0; i < ArchivePolicy.MAX_NAME_LENGTH + 10; i++) longName.append('a');
        assertFalse(ArchivePolicy.judge(longName.toString(), "").accept);
        assertFalse(ArchivePolicy.judge(longName.toString() + ".dat", "").accept);
    }

    @Test public void refusesEmptyOrParentOnlyEntries() {
        assertFalse(ArchivePolicy.judge("", "").accept);
        assertFalse(ArchivePolicy.judge("files/", "files/").accept);
        assertFalse(ArchivePolicy.judge(".", "").accept);
    }

    @Test public void refusesEntriesOutsideTheDetectedWrapper() {
        assertTrue(ArchivePolicy.judge("files/data/handling.cfg", "files/").accept);
        ArchivePolicy.Decision outside = ArchivePolicy.judge("backup/secret.txt", "files/");
        assertFalse(outside.accept);
        assertTrue(outside.reason.contains("folder data"));
    }

    @Test public void extensionParsingIgnoresDotsInFolders() {
        assertEquals("tmb", ArchivePolicy.extensionOf("texdb/a.b/c.dxt.tmb"));
        assertEquals("", ArchivePolicy.extensionOf("texdb/a.b/c"));
        assertEquals("", ArchivePolicy.extensionOf("noext"));
    }

    @Test public void findsDuplicatesCaseInsensitively() {
        List<String> names = Arrays.asList("data/gta.dat", "DATA/GTA.DAT", "data/other.dat");
        List<String> duplicates = ArchivePolicy.findDuplicates(names);
        assertEquals(1, duplicates.size());
        assertEquals("DATA/GTA.DAT", duplicates.get(0));
    }

    @Test public void recognisesDirectoryEntries() {
        assertTrue(ArchivePolicy.isDirectoryName("texdb/player/"));
        assertTrue(ArchivePolicy.isDirectoryName("texdb\\player\\"));
        assertFalse(ArchivePolicy.isDirectoryName("texdb/player/txd.tmb"));
    }
}
