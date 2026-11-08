package com.hopepride.samp.launcher.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DeveloperProfileTest {

    @Test public void containsTheTwoRealCreatorsInOrder() {
        DeveloperProfile profile = DeveloperProfile.create(
                "HopePride", "HopePride SAMP Android", "1.7.0", 164, "debug", null);

        assertEquals(2, profile.people.size());
        assertEquals("Pall", profile.people.get(0).name);
        assertEquals("Fann", profile.people.get(1).name);
        assertEquals("Pall & Fann", profile.creditLine());
    }

    @Test public void noContactButtonWithoutARealLink() {
        DeveloperProfile profile = DeveloperProfile.create(
                "HopePride", "HopePride SAMP Android", "1.7.0", 164, "debug", null);
        assertFalse(profile.hasContact());

        // A bare scheme, a word, or a scheme without host must not become a button.
        assertFalse(DeveloperProfile.create("a", "b", "1", 1, "debug", "").hasContact());
        assertFalse(DeveloperProfile.create("a", "b", "1", 1, "debug", "https://").hasContact());
        assertFalse(DeveloperProfile.create("a", "b", "1", 1, "debug", "pall").hasContact());
        assertFalse(DeveloperProfile.create("a", "b", "1", 1, "debug", "ftp://x.id").hasContact());
    }

    @Test public void acceptsARealHttpsLink() {
        DeveloperProfile profile = DeveloperProfile.create(
                "HopePride", "HopePride SAMP Android", "1.7.0", 164, "debug",
                "https://example.com/hopepride");
        assertTrue(profile.hasContact());
        assertEquals("https://example.com/hopepride", profile.contactUrl);
    }

    @Test public void initialsAreDerivedFromTheName() {
        assertEquals("P", new DeveloperProfile.Person("Pall", "", "").initials());
        assertEquals("F", new DeveloperProfile.Person("fann", "", "").initials());
        assertEquals("P", new DeveloperProfile.Person("  Pall  ", "", "").initials());
        assertEquals("PA", new DeveloperProfile.Person("Pall Ardi", "", "").initials());
        assertEquals("?", new DeveloperProfile.Person("", "", "").initials());
    }

    @Test public void buildLineShowsVersionAndBuildType() {
        DeveloperProfile profile = DeveloperProfile.create(
                "HopePride", "HopePride SAMP Android", "1.7.0", 164, "debug", null);
        assertEquals("Versi 1.7.0 (164) · debug", profile.buildLine());
    }

    @Test public void copyrightUsesTheCurrentYear() {
        DeveloperProfile profile = DeveloperProfile.create(
                "HopePride", "HopePride SAMP Android", "1.7.0", 164, "debug", null);
        String year = String.valueOf(java.util.Calendar.getInstance().get(java.util.Calendar.YEAR));
        assertEquals("© " + year + " HopePride", profile.copyright);
    }

    @Test public void fallsBackToSaneDefaultsForEmptyInput() {
        DeveloperProfile profile = DeveloperProfile.create(null, null, null, 0, null, null);
        assertEquals("HopePride", profile.appName);
        assertEquals("HopePride SAMP Android", profile.projectName);
        assertEquals("-", profile.versionName);
        assertEquals("debug", profile.buildType);
    }
}
