package com.hopepride.samp.launcher.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The developer page content, as data.
 *
 * Only facts that are already in the repository are recorded here: the two
 * creator names that appear in the launcher strings ("Dev Launcher: Pall & Fann"),
 * the application name, and the build values the caller passes in. No photo, no
 * social handle, no address and no contact button is invented - a contact action
 * is only offered when {@link #hasContact()} is true, which requires a link that
 * actually exists.
 *
 * Pure Java so the rules (initials, ordering, contact gating) are unit tested.
 */
public final class DeveloperProfile {

    public static final class Person {
        public final String name;
        public final String role;
        public final String description;

        public Person(String name, String role, String description) {
            this.name = name == null ? "" : name.trim();
            this.role = role == null ? "" : role;
            this.description = description == null ? "" : description;
        }

        /** Elegant initial avatar, e.g. "Pall" -> "P". Empty name gives "?". */
        public String initials() {
            if (name.isEmpty()) return "?";
            String[] words = name.split("\\s+");
            StringBuilder builder = new StringBuilder();
            for (String word : words) {
                if (word.isEmpty()) continue;
                builder.append(Character.toUpperCase(word.charAt(0)));
                if (builder.length() == 2) break;
            }
            return builder.length() == 0 ? "?" : builder.toString();
        }
    }

    public final String appName;
    public final String projectName;
    public final String versionName;
    public final int versionCode;
    public final String buildType;
    public final String copyright;
    public final String contactUrl;
    public final List<Person> people;

    private DeveloperProfile(String appName, String projectName, String versionName, int versionCode,
                             String buildType, String copyright, String contactUrl, List<Person> people) {
        this.appName = appName;
        this.projectName = projectName;
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.buildType = buildType;
        this.copyright = copyright;
        this.contactUrl = contactUrl;
        this.people = Collections.unmodifiableList(people);
    }

    /**
     * Builds the profile for the running build.
     *
     * @param contactUrl a verified official link, or null/empty when none exists
     */
    public static DeveloperProfile create(String appName, String projectName, String versionName,
                                          int versionCode, String buildType, String contactUrl) {
        List<Person> people = new ArrayList<>();
        people.add(new Person("Pall", "Developer / App Creator",
                "Pengembang inti HopePride SAMP Android: integrasi native, klien SA-MP, dan pipeline data game."));
        people.add(new Person("Fann", "Developer / App Creator",
                "Pengembang launcher HopePride: antarmuka, pengalaman pengguna, dan pemeliharaan rilis."));

        String cleanContact = contactUrl == null ? "" : contactUrl.trim();
        if (!isHttpUrl(cleanContact)) cleanContact = "";

        String year = String.valueOf(java.util.Calendar.getInstance().get(java.util.Calendar.YEAR));

        return new DeveloperProfile(
                appName == null || appName.isEmpty() ? "HopePride" : appName,
                projectName == null || projectName.isEmpty() ? "HopePride SAMP Android" : projectName,
                versionName == null || versionName.isEmpty() ? "-" : versionName,
                versionCode,
                buildType == null || buildType.isEmpty() ? "debug" : buildType,
                "© " + year + " HopePride",
                cleanContact,
                people);
    }

    /** Only a real https link may become a button. */
    public static boolean isHttpUrl(String value) {
        if (value == null) return false;
        String trimmed = value.trim();
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) return false;
        // A bare scheme is not a link.
        int afterScheme = trimmed.indexOf("://") + 3;
        return trimmed.length() > afterScheme + 1 && trimmed.indexOf(' ', afterScheme) < 0;
    }

    /** The contact row is only shown when there is somewhere to go. */
    public boolean hasContact() {
        return !contactUrl.isEmpty();
    }

    /** One line for the about/developer header, e.g. "Versi 1.7.0 (164) · debug". */
    public String buildLine() {
        return "Versi " + versionName + " (" + versionCode + ") · " + buildType;
    }

    /** Names joined for the credit line used by the splash and loading screens. */
    public String creditLine() {
        StringBuilder builder = new StringBuilder();
        for (Person person : people) {
            if (builder.length() > 0) builder.append(" & ");
            builder.append(person.name);
        }
        return builder.toString();
    }
}
