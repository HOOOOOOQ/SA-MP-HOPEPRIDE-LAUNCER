package com.hopepride.samp.launcher.data;

import android.content.Context;
import android.net.Uri;

import com.hopepride.samp.game.GameDataExtractor;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Manual installation from a user chosen archive.
 *
 * The user picks a zip through the Storage Access Framework, so no storage
 * permission is needed and the picker works under scoped storage. The archive is
 * copied into app-private cache first because:
 *
 *   * a content:// Uri may stop being readable while a long extraction runs;
 *   * the size can be checked before anything is written into the data root;
 *   * the copy is what the ZIP validator reads, so the user's own file is never
 *     modified or deleted.
 *
 * Extraction itself is delegated to {@link GameDataExtractor}, which already
 * handles zip slip, file/directory clashes, temp files and partial writes. This
 * class only adds the Uri plumbing, the size guard and the free space check.
 */
public final class ManualDataInstaller {

    /** Refuse an archive bigger than this; a full SA data set is far below it. */
    public static final long MAX_ARCHIVE_BYTES = 3L * 1024 * 1024 * 1024;

    public interface Progress {
        void onStage(String stage, int percent);
    }

    public static final class Result {
        public final boolean success;
        public final String message;
        public final int files;
        public final long bytes;

        Result(boolean success, String message, int files, long bytes) {
            this.success = success;
            this.message = message;
            this.files = files;
            this.bytes = bytes;
        }
    }

    private ManualDataInstaller() { }

    /**
     * Copies the picked archive into cache and extracts it into {@code destination}.
     * Blocking: call from a worker thread.
     */
    public static Result install(Context context, Uri archiveUri, File destination, Progress progress)
            throws IOException {

        if (context == null || archiveUri == null) {
            throw new IOException("Arsip belum dipilih.");
        }
        if (destination == null) {
            throw new IOException("Folder tujuan tidak tersedia.");
        }

        File cache = new File(context.getCacheDir(), "manual_install");
        if (!cache.isDirectory() && !cache.mkdirs()) {
            throw new IOException("Tidak dapat menyiapkan folder sementara.");
        }

        File staged = new File(cache, "picked.zip");

        // ---------------------------------------------------------- verify zip
        if (progress != null) progress.onStage("Memeriksa arsip", 0);

        long copied;
        try (InputStream raw = context.getContentResolver().openInputStream(archiveUri)) {
            if (raw == null) throw new IOException("Arsip tidak dapat dibuka.");
            copied = copyTo(raw, staged);
        }

        if (copied == 0) {
            //noinspection ResultOfMethodCallIgnored
            staged.delete();
            throw new IOException("Arsip kosong.");
        }
        if (copied > MAX_ARCHIVE_BYTES) {
            //noinspection ResultOfMethodCallIgnored
            staged.delete();
            throw new IOException("Arsip terlalu besar (" + copied + " byte).");
        }

        String archiveProblem = validateArchive(staged);
        if (archiveProblem != null) {
            //noinspection ResultOfMethodCallIgnored
            staged.delete();
            throw new IOException(archiveProblem);
        }

        // -------------------------------------------------------- free space
        long needed = estimateExtractedBytes(staged);
        long available = destination.getUsableSpace();
        // 5% headroom for filesystem overhead and the temp files the extractor
        // writes next to each target before renaming.
        if (needed > 0 && available > 0 && needed + (needed / 20) > available) {
            //noinspection ResultOfMethodCallIgnored
            staged.delete();
            throw new IOException("Ruang penyimpanan tidak cukup: butuh sekitar "
                    + formatBytes(needed) + ", tersedia " + formatBytes(available) + ".");
        }

        // ----------------------------------------------------------- extract
        if (progress != null) progress.onStage("Mengekstrak data", 10);

        // The extractor reports a running file index, and the archive's entry count
        // is known from the central directory, so the percentage is computed from
        // real work done rather than from a timer.
        final int entryCount = countEntries(staged);

        GameDataExtractor.Summary summary;
        try {
            summary = GameDataExtractor.extract(staged, destination, (done, totalFiles, name) -> {
                if (progress == null) return;
                int denominator = entryCount > 0 ? entryCount : totalFiles;
                if (denominator <= 0) {
                    progress.onStage("Mengekstrak " + name, 10);
                    return;
                }
                int percent = 10 + (int) (85.0 * done / denominator);
                progress.onStage("Mengekstrak " + name, Math.max(10, Math.min(95, percent)));
            });
        } finally {
            //noinspection ResultOfMethodCallIgnored
            staged.delete();
        }

        if (progress != null) progress.onStage("Memeriksa hasil", 96);

        String describe = summary.describe();
        if (summary.files == 0 && summary.directories == 0) {
            return new Result(false, "Arsip tidak berisi berkas yang dapat dipasang.", 0, 0);
        }

        // Entries the archive policy refused are reported, not hidden: the user
        // has to know that part of their archive was not installed.
        if (!summary.problems.isEmpty()) {
            describe += " · " + summary.skipped + " entri dilewati ("
                    + summary.problems.get(0) + (summary.problems.size() > 1 ? " …" : "") + ")";
        }

        return new Result(true, describe, summary.files, summary.bytes);
    }

    /** Copies an input stream to a file, returning the byte count. */
    static long copyTo(InputStream raw, File target) throws IOException {
        long total = 0;
        try (InputStream in = new BufferedInputStream(raw);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                total += read;
                if (total > MAX_ARCHIVE_BYTES) {
                    throw new IOException("Arsip terlalu besar.");
                }
            }
        }
        return total;
    }

    /**
     * Reads the archive's central directory without writing anything.
     * Returns null when the archive is usable, otherwise a user facing reason.
     */
    static String validateArchive(File archive) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive)) {
            if (zip.size() <= 0) return "Arsip kosong atau rusak.";
            int suspicious = 0;
            int total = 0;
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                total++;
                String name = entry.getName();
                if (name == null || name.isEmpty()) {
                    suspicious++;
                    continue;
                }
                if (ArchivePolicy.judge(name, "").accept) continue;
                // Entries the policy refuses are counted, not fatal: a data pack
                // may legitimately ship a README next to the game files.
                suspicious++;
            }
            if (total > 0 && suspicious == total) {
                return "Isi arsip tidak dikenali sebagai data game.";
            }
            return null;
        } catch (IOException error) {
            return "Arsip tidak dapat dibaca: " + error.getMessage();
        }
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"B", "KB", "MB", "GB"};
        int index = 0;
        double value = bytes;
        while (value >= 1024 && index < units.length - 1) {
            value /= 1024;
            index++;
        }
        return String.format(java.util.Locale.US,
                index == 0 ? "%.0f %s" : "%.1f %s", value, units[index]);
    }

    /** Entry count from the central directory, or 0 when it cannot be read. */
    static int countEntries(File archive) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive)) {
            return zip.size();
        } catch (IOException error) {
            return 0;
        }
    }

    /** Sum of declared uncompressed sizes, or 0 when the archive size is unknown. */
    static long estimateExtractedBytes(File archive) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive)) {
            long total = 0;
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                long size = entry.getSize();
                if (size > 0 && total < Long.MAX_VALUE - size) total += size;
            }
            return total;
        } catch (IOException error) {
            return 0;
        }
    }

    /**
     * Counts entries in the archive without extracting, so the UI can show what
     * the user is about to install.
     */
    public static java.util.List<String> describeContents(File archive, int limit) {
        java.util.List<String> result = new java.util.ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(
                new FileInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null && result.size() < limit) {
                result.add(entry.getName());
            }
        } catch (IOException ignored) {
            // A broken archive returns whatever was read before the failure.
        }
        return result;
    }
}
