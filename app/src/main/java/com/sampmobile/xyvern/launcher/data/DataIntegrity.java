package com.hopepride.samp.launcher.data;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * SHA-256 helpers for game-data verification and repair.
 *
 * The launcher used to trust file names and sizes only, which cannot tell a
 * complete texture database apart from one that was cut off at 90%. A digest can,
 * so a manifest of relative path + size + sha256 is the source of truth when one
 * is published; without a manifest the verifier still works, it just reports
 * "unknown integrity" instead of pretending.
 *
 * Deliberately free of Android imports so the parsing and comparison rules can be
 * unit tested on the JVM.
 */
public final class DataIntegrity {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private DataIntegrity() { }

    /** Lower-case hex SHA-256 of a file, or null when it cannot be read. */
    public static String sha256(File file) {
        if (file == null || !file.isFile()) return null;
        InputStream in = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            in = new FileInputStream(file);
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            return toHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException error) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
        }
    }

    /** Lower-case hex SHA-256 of a byte array. */
    public static String sha256(byte[] data) {
        if (data == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(data));
        } catch (NoSuchAlgorithmException error) {
            return null;
        }
    }

    public static String toHex(byte[] bytes) {
        if (bytes == null) return null;
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            out[i * 2] = HEX[value >>> 4];
            out[i * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(out);
    }

    /** Normalises a digest string for comparison, or null when it is not a sha256. */
    public static String normalizeSha256(String value) {
        if (value == null) return null;
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        if (trimmed.startsWith("sha256:")) trimmed = trimmed.substring(7);
        if (trimmed.length() != 64) return null;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) return null;
        }
        return trimmed;
    }

    public enum State {
        /** File exists and its digest matches the manifest. */
        OK,
        /** File is absent. */
        MISSING,
        /** File exists but is empty or the wrong size. */
        TRUNCATED,
        /** Digest differs from the manifest. */
        CORRUPT,
        /** A manifest entry exists but the file cannot be read. */
        UNREADABLE,
        /** No digest available for this file; only existence was checked. */
        UNVERIFIED
    }

    public static final class FileStatus {
        public final String path;
        public final State state;
        public final long size;
        public final String detail;

        public FileStatus(String path, State state, long size, String detail) {
            this.path = path;
            this.state = state;
            this.size = size;
            this.detail = detail == null ? "" : detail;
        }

        /** Only OK is a pass; UNVERIFIED is reported but never silently treated as OK. */
        public boolean isHealthy() {
            return state == State.OK;
        }

        public boolean needsDownload() {
            return state == State.MISSING || state == State.TRUNCATED
                    || state == State.CORRUPT || state == State.UNREADABLE;
        }

        @Override
        public String toString() {
            return path + " [" + state + (detail.isEmpty() ? "" : ": " + detail) + "]";
        }
    }

    /**
     * Checks one file against an optional manifest entry.
     *
     * @param root          data root the relative path is resolved against
     * @param relativePath  path inside the data root
     * @param expectedSize  manifest size, 0 when unknown
     * @param expectedSha   manifest sha256, null when unknown
     */
    public static FileStatus check(File root, String relativePath, long expectedSize, String expectedSha) {
        if (root == null || relativePath == null || relativePath.isEmpty())
            return new FileStatus(String.valueOf(relativePath), State.MISSING, 0, "path kosong");

        File file = new File(root, relativePath);
        if (!file.isFile()) {
            return new FileStatus(relativePath, State.MISSING, 0, "");
        }

        long length = file.length();
        if (length == 0) {
            return new FileStatus(relativePath, State.TRUNCATED, 0, "berkas kosong");
        }
        if (expectedSize > 0 && length != expectedSize) {
            return new FileStatus(relativePath, State.TRUNCATED, length,
                    "ukuran " + length + " byte, seharusnya " + expectedSize);
        }

        String normalized = normalizeSha256(expectedSha);
        if (normalized == null) {
            return new FileStatus(relativePath, State.UNVERIFIED, length, "checksum belum tersedia");
        }

        String actual = sha256(file);
        if (actual == null) {
            return new FileStatus(relativePath, State.UNREADABLE, length, "tidak dapat dibaca");
        }
        if (!normalized.equals(actual)) {
            return new FileStatus(relativePath, State.CORRUPT, length,
                    "sha256 " + actual.substring(0, 12) + "… tidak cocok");
        }

        return new FileStatus(relativePath, State.OK, length, "");
    }
}
