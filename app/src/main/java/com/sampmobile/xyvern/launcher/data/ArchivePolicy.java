package com.hopepride.samp.launcher.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Archive entry policy: which zip entries are allowed to be written into the data
 * root, and which are refused.
 *
 * Game data archives come from third parties, so the installer cannot assume the
 * content is only GTA files. The rules here are intentionally conservative and
 * explainable:
 *
 *   * path traversal / absolute paths / drive letters are refused (zip slip);
 *   * over-long paths and names are refused (they cannot be created anyway);
 *   * control characters are refused (they break every later file operation);
 *   * executables and shared libraries are refused, because the game never loads
 *     anything from the data directory - a ".so" or ".apk" in a data pack is
 *     either a mistake or an attempt to plant something;
 *   * entries outside the game data tree are refused when a wrapper folder was
 *     detected, so an unrelated "backup/" tree cannot be written.
 *
 * Pure Java, no Android imports: covered by JVM unit tests.
 */
public final class ArchivePolicy {

    /** Hard limits taken from the extractor so both stay in sync. */
    public static final int MAX_NAME_LENGTH = 240;
    public static final int MAX_PATH_LENGTH = 3500;
    /** A single archive entry larger than this is treated as corruption. */
    public static final long MAX_ENTRY_BYTES = 2L * 1024 * 1024 * 1024;

    private static final String[] BLOCKED_EXTENSIONS = {
            "apk", "dex", "jar", "so", "oat", "vdex", "odex", "elf", "bin",
            "sh", "bat", "cmd", "exe", "dll", "msi", "ps1", "py", "pl", "php",
            "jsp", "aspx", "cgi"
    };

    private static final String[] BLOCKED_PREFIXES = {
            "meta-inf/", "android/", "system/", "sdcard/", "mnt/", "proc/", "sys/", "data/data/"
    };

    /** Reasons an entry is refused. Kept as strings so a report can show them. */
    public static final class Decision {
        public final String name;
        public final boolean accept;
        public final String reason;

        Decision(String name, boolean accept, String reason) {
            this.name = name;
            this.accept = accept;
            this.reason = reason == null ? "" : reason;
        }
    }

    private ArchivePolicy() { }

    /** Normalises separators to '/', leaving the entry name otherwise untouched. */
    public static String normalize(String name) {
        if (name == null) return null;
        return name.replace('\\', '/');
    }

    /**
     * Judges one entry name.
     *
     * @param rawName      the entry name exactly as it appears in the archive
     * @param stripPrefix  wrapper folder that has already been proven to hold the
     *                     game data ("" when the archive is flat)
     */
    public static Decision judge(String rawName, String stripPrefix) {
        String normalized = normalize(rawName);
        if (normalized == null || normalized.isEmpty()) {
            return new Decision(String.valueOf(rawName), false, "nama kosong");
        }
        if (normalized.indexOf('\u0000') >= 0) {
            return new Decision(normalized, false, "mengandung karakter nol");
        }
        if (normalized.startsWith("/")) {
            return new Decision(normalized, false, "path absolut");
        }
        if (normalized.length() > 2 && Character.isLetter(normalized.charAt(0))
                && normalized.charAt(1) == ':') {
            return new Decision(normalized, false, "path absolut (drive letter)");
        }

        String relative = normalized;
        if (stripPrefix != null && !stripPrefix.isEmpty()) {
            if (!normalized.startsWith(stripPrefix)) {
                return new Decision(normalized, false, "di luar folder data");
            }
            relative = normalized.substring(stripPrefix.length());
        }
        if (relative.isEmpty() || relative.equals(".") || relative.equals("./")) {
            return new Decision(normalized, false, "entri folder induk");
        }

        if (relative.length() > MAX_PATH_LENGTH) {
            return new Decision(normalized, false, "path terlalu panjang");
        }

        String lower = relative.toLowerCase(Locale.ROOT);
        for (String prefix : BLOCKED_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return new Decision(normalized, false, "folder sistem: " + prefix);
            }
        }

        String[] parts = relative.split("/");
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) return new Decision(normalized, false, "mencoba keluar dari folder tujuan");
            if (part.length() > MAX_NAME_LENGTH) return new Decision(normalized, false, "nama komponen terlalu panjang");
            for (int i = 0; i < part.length(); i++) {
                if (part.charAt(i) < 0x20) return new Decision(normalized, false, "karakter kontrol");
            }
        }

        if (!relative.endsWith("/")) {
            String extension = extensionOf(lower);
            for (String blocked : BLOCKED_EXTENSIONS) {
                if (blocked.equals(extension)) {
                    return new Decision(normalized, false, "berkas yang dapat dieksekusi: ." + extension);
                }
            }
        }

        return new Decision(normalized, true, "");
    }

    static String extensionOf(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot < slash) return "";
        return path.substring(dot + 1);
    }

    /** True when the name describes a directory entry. */
    public static boolean isDirectoryName(String name) {
        String normalized = normalize(name);
        return normalized != null && normalized.endsWith("/");
    }

    /** Collects duplicates: the first occurrence wins, later ones are reported. */
    public static List<String> findDuplicates(List<String> names) {
        if (names == null) return Collections.emptyList();
        List<String> seen = new ArrayList<>();
        List<String> duplicates = new ArrayList<>();
        for (String name : names) {
            String normalized = normalize(name);
            if (normalized == null) continue;
            String key = normalized.toLowerCase(Locale.ROOT);
            if (seen.contains(key)) {
                if (!duplicates.contains(normalized)) duplicates.add(normalized);
            } else {
                seen.add(key);
            }
        }
        return duplicates;
    }
}
