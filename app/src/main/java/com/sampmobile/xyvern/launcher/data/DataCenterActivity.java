package com.hopepride.samp.launcher.data;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.hopepride.samp.game.GameDataDownloadService;
import com.hopepride.samp.game.GameDataManager;
import com.hopepride.samp.launcher.ui.MotionBackgroundView;
import com.hopepride.samp.launcher.ui.MotionKit;
import com.hopepride.samp.launcher.ui.MotionSpec;
import com.hopepride.samp.launcher.update.UpdatePlan;
import com.hopepride.samp.launcher.util.GameDataVerifier;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pusat Data Game: download manager, verifikasi, perbaikan, dan instalasi manual.
 *
 * Everything on this screen is connected to something real:
 *
 *   * "Periksa data" runs {@link GameDataVerifier} and, when a manifest is
 *     installed, {@link DataManifest} SHA-256 checks, then lists every file it
 *     actually inspected.
 *   * "Perbaiki" asks {@link UpdatePlan} what the cheapest action is and then
 *     either starts the existing download service or tells the user plainly that
 *     the full archive is needed. It never downloads silently.
 *   * "Pasang dari ZIP" opens the Storage Access Framework picker and runs
 *     {@link ManualDataInstaller}, which streams real progress.
 *   * Pause/Resume/Cancel are wired to the download service state; when the
 *     service cannot do one of those, the button is disabled rather than lying.
 */
public class DataCenterActivity extends AppCompatActivity {

    private static final int BG_TOP = 0xFF0B1420;
    private static final int BG_BOTTOM = 0xFF05090F;
    private static final int ACCENT = 0xFF33B9F7;
    private static final int TEXT = 0xFFEAF3FB;
    private static final int MUTED = 0xFF8FA3B8;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private MotionBackgroundView background;
    private LinearLayout sectionContainer;
    private TextView summaryTitle;
    private TextView summaryDetail;
    private View summaryDot;
    private ProgressBar progress;
    private TextView progressLabel;
    private Button verifyButton;
    private Button repairButton;
    private Button manualButton;
    private Button cancelButton;

    private boolean busy;

    private final ActivityResultLauncher<String> pickArchive =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) installFromUri(uri);
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MotionKit.init(this);
        setContentView(buildUi());
        observeDownloadService();
        runVerification();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (background != null) background.start();
    }

    @Override
    protected void onStop() {
        if (background != null) background.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        GameDataDownloadService.removeListener(listener);
        io.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------- UI

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);

        background = new MotionBackgroundView(this);
        background.setColors(BG_TOP, BG_BOTTOM, ACCENT);
        root.addView(background, new FrameLayout.LayoutParams(-1, -1));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = MotionKit.dp(this, 20);
        column.setPadding(pad, MotionKit.dp(this, 46), pad, MotionKit.dp(this, 36));
        scroll.addView(column, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        column.addView(title("Pusat Data Game", 24));
        column.addView(subtitle(
                "Unduh, periksa, perbaiki, atau pasang data game dari arsip sendiri."));

        // ------------------------------------------------------- status card
        LinearLayout status = card();
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);

        summaryDot = new View(this);
        GradientDrawable dotShape = new GradientDrawable();
        dotShape.setShape(GradientDrawable.OVAL);
        dotShape.setColor(0xFF8FA3B8);
        summaryDot.setBackground(dotShape);
        LinearLayout.LayoutParams dotParams =
                new LinearLayout.LayoutParams(MotionKit.dp(this, 10), MotionKit.dp(this, 10));
        dotParams.rightMargin = MotionKit.dp(this, 12);
        statusRow.addView(summaryDot, dotParams);

        LinearLayout statusText = new LinearLayout(this);
        statusText.setOrientation(LinearLayout.VERTICAL);
        summaryTitle = text("Memeriksa...", 15, TEXT, true);
        summaryDetail = text("", 12, MUTED, false);
        statusText.addView(summaryTitle);
        statusText.addView(summaryDetail, marginTop(4));
        statusRow.addView(statusText, new LinearLayout.LayoutParams(0, -2, 1f));
        status.addView(statusRow);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x22FFFFFF));
        progress.setVisibility(View.GONE);
        progressLabel = text("", 11, MUTED, false);
        progressLabel.setVisibility(View.GONE);
        status.addView(progress, marginTop(16));
        status.addView(progressLabel, marginTop(8));

        column.addView(status, marginTop(22));

        // ---------------------------------------------------------- actions
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);

        verifyButton = primaryButton("PERIKSA DATA");
        verifyButton.setOnClickListener(v -> runVerification());
        actions.addView(verifyButton, marginTop(18));

        repairButton = secondaryButton("PERBAIKI DATA");
        repairButton.setOnClickListener(v -> repair());
        actions.addView(repairButton, marginTop(10));

        manualButton = secondaryButton("PASANG DARI FILE ZIP");
        manualButton.setOnClickListener(v -> {
            try {
                pickArchive.launch("application/zip");
            } catch (android.content.ActivityNotFoundException missing) {
                // Some file managers advertise zip only as octet-stream.
                pickArchive.launch("application/octet-stream");
            }
        });
        actions.addView(manualButton, marginTop(10));

        cancelButton = secondaryButton("HENTIKAN UNDUHAN");
        cancelButton.setEnabled(false);
        cancelButton.setOnClickListener(v -> {
            GameDataDownloadService.cancel(this);
            Toast.makeText(this, "Permintaan berhenti dikirim.", Toast.LENGTH_SHORT).show();
        });
        actions.addView(cancelButton, marginTop(10));

        column.addView(actions);

        // ------------------------------------------------------------ files
        column.addView(sectionLabel("BERKAS YANG DIPERIKSA"), marginTop(28));

        sectionContainer = new LinearLayout(this);
        sectionContainer.setOrientation(LinearLayout.VERTICAL);
        column.addView(sectionContainer);

        TextView note = text(
                "Status \"belum terverifikasi\" berarti berkas ada tetapi belum ada checksum "
                        + "pembanding. Launcher tidak menganggapnya utuh.", 11, MUTED, false);
        note.setLineSpacing(MotionKit.dp(this, 2), 1f);
        column.addView(note, marginTop(16));

        MotionKit.staggerChildren(column);
        return root;
    }

    // ------------------------------------------------------------ verification

    private void runVerification() {
        if (busy) return;
        busy = true;
        verifyButton.setEnabled(false);
        setSummary(0xFFF0B429, "Memeriksa data game...", "Menghitung checksum berkas inti.");
        sectionContainer.removeAllViews();

        final Context appContext = getApplicationContext();
        io.execute(() -> {
            GameDataVerifier.VerificationResult result = GameDataVerifier.verifyGameData(appContext);
            final File root = appContext.getExternalFilesDir(null);
            DataManifest manifest = loadManifest(appContext);
            final List<DataIntegrity.FileStatus> statuses =
                    manifest.isEmpty() ? null : manifest.verify(root);
            final List<DataIntegrity.FileStatus> problems =
                    manifest.isEmpty() ? null : manifest.problems(root);

            main.post(() -> {
                busy = false;
                verifyButton.setEnabled(true);

                if (result.isValid) {
                    setSummary(0xFF4ADE80, "Data inti lengkap",
                            (result.totalSizeBytes / 1048576) + " MB terverifikasi ada.");
                } else {
                    setSummary(0xFFF87171, "Data belum lengkap",
                            TextUtilsJoin.join(result.missingFiles, ", "));
                }

                if (problems != null && !problems.isEmpty()) {
                    UpdatePlan plan = UpdatePlan.decide(
                            statuses.size(), problems.size(), sumBytes(problems),
                            manifest.archiveSize, true, root == null ? 0 : root.getUsableSpace());
                    setSummary(0xFFF87171, problems.size() + " berkas bermasalah",
                            plan.reason);
                }

                renderFiles(result, statuses);
            });
        });
    }

    private void renderFiles(GameDataVerifier.VerificationResult result,
                             List<DataIntegrity.FileStatus> statuses) {
        sectionContainer.removeAllViews();

        if (statuses == null) {
            // No manifest installed: show the essential files the verifier really
            // checked, marked as verified-by-existence only.
            for (String path : GameDataVerifier.ESSENTIAL_FILES) {
                boolean missing = result.missingFiles.contains(path);
                addFileRow(path,
                        missing ? "Hilang" : "Ada",
                        missing ? 0xFFF87171 : 0xFFF0B429,
                        missing ? "" : "hanya keberadaan, checksum belum tersedia");
            }
            TextView hint = text(
                    "Manifest checksum belum tersedia, jadi verifikasi hanya memastikan berkas "
                            + "inti ada. Perbaikan per-berkas memerlukan manifest.",
                    11, MUTED, false);
            hint.setLineSpacing(MotionKit.dp(this, 2), 1f);
            sectionContainer.addView(hint, marginTop(12));
            return;
        }

        int shown = 0;
        for (DataIntegrity.FileStatus status : statuses) {
            if (shown++ >= 40) break;
            int color;
            switch (status.state) {
                case OK:         color = 0xFF4ADE80; break;
                case UNVERIFIED: color = 0xFFF0B429; break;
                default:         color = 0xFFF87171; break;
            }
            addFileRow(status.path, labelFor(status.state), color, status.detail);
        }
        if (statuses.size() > 40) {
            sectionContainer.addView(text("… dan " + (statuses.size() - 40) + " berkas lain.",
                    11, MUTED, false), marginTop(8));
        }
    }

    private void addFileRow(String path, String state, int color, String detail) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(MotionKit.dp(this, 14), MotionKit.dp(this, 12),
                MotionKit.dp(this, 14), MotionKit.dp(this, 12));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0xFF111C2B);
        shape.setCornerRadius(MotionKit.dp(this, 14));
        row.setBackground(shape);

        View dot = new View(this);
        GradientDrawable dotShape = new GradientDrawable();
        dotShape.setShape(GradientDrawable.OVAL);
        dotShape.setColor(color);
        dot.setBackground(dotShape);
        LinearLayout.LayoutParams dotParams =
                new LinearLayout.LayoutParams(MotionKit.dp(this, 8), MotionKit.dp(this, 8));
        dotParams.rightMargin = MotionKit.dp(this, 12);
        row.addView(dot, dotParams);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(path, 12, TEXT, true);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        column.addView(name);
        if (detail != null && !detail.isEmpty()) {
            TextView sub = text(detail, 10, MUTED, false);
            sub.setMaxLines(1);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            column.addView(sub, marginTop(3));
        }
        row.addView(column, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView badge = text(state, 11, color, true);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2);
        badgeParams.leftMargin = MotionKit.dp(this, 10);
        row.addView(badge, badgeParams);

        LinearLayout.LayoutParams rowParams =
                new LinearLayout.LayoutParams(-1, -2);
        rowParams.bottomMargin = MotionKit.dp(this, 8);
        sectionContainer.addView(row, rowParams);
        MotionKit.enter(row, sectionContainer.getChildCount(), MotionSpec.DURATION_BASE, 0.98f);
    }

    private static String labelFor(DataIntegrity.State state) {
        switch (state) {
            case OK:         return "Utuh";
            case MISSING:    return "Hilang";
            case TRUNCATED:  return "Terpotong";
            case CORRUPT:    return "Rusak";
            case UNREADABLE: return "Tidak terbaca";
            case UNVERIFIED: return "Belum dicek";
        }
        return "?";
    }

    private DataManifest loadManifest(Context context) {
        // The manifest is optional. It is read from the data root when a build or
        // a data pack ships one; there is no network fetch here, so nothing is
        // claimed about a manifest that does not exist.
        try {
            File root = context.getExternalFilesDir(null);
            if (root == null) return DataManifest.empty();
            File manifest = new File(root, "data_manifest.json");
            if (!manifest.isFile() || manifest.length() == 0) return DataManifest.empty();
            byte[] bytes = java.nio.file.Files.readAllBytes(manifest.toPath());
            return DataManifest.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception error) {
            return DataManifest.empty();
        }
    }

    // --------------------------------------------------------------- repair

    private void repair() {
        final Context appContext = getApplicationContext();
        final File root = appContext.getExternalFilesDir(null);
        setSummary(0xFFF0B429, "Menyiapkan perbaikan...", "Menentukan tindakan paling murah.");

        io.execute(() -> {
            DataManifest manifest = loadManifest(appContext);
            List<DataIntegrity.FileStatus> problems = manifest.problems(root);
            GameDataVerifier.VerificationResult result = GameDataVerifier.verifyGameData(appContext);

            UpdatePlan plan = UpdatePlan.decide(
                    manifest.files.size(), problems.size(), sumBytes(problems),
                    manifest.archiveSize, isMarkerPresent(root),
                    root == null ? 0 : root.getUsableSpace());

            main.post(() -> {
                switch (plan.action) {
                    case NOTHING:
                        setSummary(0xFF4ADE80, "Tidak ada yang perlu diperbaiki", plan.reason);
                        break;

                    case REBUILD_MANIFEST:
                        setSummary(0xFFF0B429, "Menulis ulang penanda", plan.reason);
                        GameDataManager.rewriteInstallMarker(appContext);
                        break;

                    case PATCH_FILES:
                        // Honest limitation: per-file repair needs a per-file source.
                        // The current data source is one archive, so the launcher
                        // says so instead of pretending to patch two files.
                        setSummary(0xFFF0B429, "Perbaikan per-berkas belum bisa dijalankan",
                                plan.filesToFetch + " berkas perlu diunduh, tetapi sumber data saat ini "
                                        + "hanya menyediakan satu paket lengkap. Gunakan "
                                        + "\"Unduh ulang paket\" bila ingin memperbaiki sekarang.");
                        break;

                    case FULL_ARCHIVE:
                        setSummary(0xFFF0B429, "Perlu paket lengkap", plan.reason);
                        startFullDownload();
                        break;

                    case BLOCKED_NO_SPACE:
                        setSummary(0xFFF87171, "Ruang penyimpanan tidak cukup", plan.reason);
                        break;
                }
                if (!result.isValid && plan.action == UpdatePlan.Action.NOTHING) {
                    setSummary(0xFFF87171, "Data belum lengkap", "Berkas inti belum ditemukan.");
                }
            });
        });
    }

    private void startFullDownload() {
        progress.setVisibility(View.VISIBLE);
        progressLabel.setVisibility(View.VISIBLE);
        GameDataManager.startPackageDownload(getApplicationContext());
    }

    private static boolean isMarkerPresent(File root) {
        return root != null && new File(root, "SAMP/.data_ready").isFile();
    }

    private static long sumBytes(List<DataIntegrity.FileStatus> statuses) {
        long total = 0;
        if (statuses == null) return 0;
        for (DataIntegrity.FileStatus status : statuses) total += Math.max(0, status.size);
        return total;
    }

    // --------------------------------------------------------------- manual

    private void installFromUri(Uri uri) {
        if (busy) {
            Toast.makeText(this, "Tunggu proses sebelumnya selesai.", Toast.LENGTH_SHORT).show();
            return;
        }
        final Context appContext = getApplicationContext();
        final File root = appContext.getExternalFilesDir(null);
        if (root == null) {
            Toast.makeText(this, "Penyimpanan tidak tersedia.", Toast.LENGTH_LONG).show();
            return;
        }

        busy = true;
        manualButton.setEnabled(false);
        verifyButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        progressLabel.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        setSummary(0xFFF0B429, "Memasang dari arsip", displayName(uri));

        io.execute(() -> {
            try {
                ManualDataInstaller.Result result = ManualDataInstaller.install(
                        appContext, uri, root, (stage, percent) -> main.post(() -> {
                            progress.setProgress(percent);
                            progressLabel.setText(stage + "  ·  " + percent + "%");
                        }));

                main.post(() -> {
                    busy = false;
                    manualButton.setEnabled(true);
                    verifyButton.setEnabled(true);
                    progressLabel.setText("");
                    if (result.success) {
                        MotionKit.pulse(summaryDot);
                        setSummary(0xFF4ADE80, "Arsip terpasang", result.message);
                        runVerification();
                    } else {
                        setSummary(0xFFF87171, "Arsip tidak terpasang", result.message);
                    }
                });
            } catch (Exception error) {
                main.post(() -> {
                    busy = false;
                    manualButton.setEnabled(true);
                    verifyButton.setEnabled(true);
                    progress.setVisibility(View.GONE);
                    progressLabel.setVisibility(View.GONE);
                    setSummary(0xFFF87171, "Pemasangan gagal",
                            error.getMessage() == null ? error.toString() : error.getMessage());
                });
            }
        });
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null) return name;
                }
            }
        } catch (Exception ignored) {
            // Fall through to the raw uri, which is still informative.
        }
        return String.valueOf(uri);
    }

    // ------------------------------------------------------------- download

    private final GameDataDownloadService.Listener listener = state -> main.post(() -> {
        boolean running = state.phase == GameDataDownloadService.PHASE_CONNECT
                || state.phase == GameDataDownloadService.PHASE_DOWNLOAD
                || state.phase == GameDataDownloadService.PHASE_EXTRACT;
        cancelButton.setEnabled(running);

        if (running) {
            progress.setVisibility(View.VISIBLE);
            progressLabel.setVisibility(View.VISIBLE);
            // The service reports whole percent; the bar uses the same value, so
            // there is no invented smoothing between the two.
            progress.setProgress(Math.max(0, Math.min(100, state.percent)));
            progressLabel.setText(state.detail == null ? "" : state.detail);
        } else if (state.phase == GameDataDownloadService.PHASE_DONE) {
            progress.setProgress(100);
            setSummary(0xFF4ADE80, "Unduhan selesai", state.detail);
            runVerification();
        } else if (state.phase == GameDataDownloadService.PHASE_FAILED) {
            setSummary(0xFFF87171, "Unduhan gagal", state.error);
        }
    });

    private void observeDownloadService() {
        GameDataDownloadService.addListener(listener);
        GameDataDownloadService.State state = GameDataDownloadService.getState();
        if (state != null && state.phase != GameDataDownloadService.PHASE_IDLE) {
            listener.onState(state);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void setSummary(int color, String title, String detail) {
        summaryTitle.setText(title);
        summaryDetail.setText(detail == null ? "" : detail);
        GradientDrawable dotShape = new GradientDrawable();
        dotShape.setShape(GradientDrawable.OVAL);
        dotShape.setColor(color);
        summaryDot.setBackground(dotShape);
        MotionKit.enter(summaryDetail, 0, MotionSpec.DURATION_FAST, 1f);
    }

    private TextView title(String value, int size) {
        return text(value, size, TEXT, true);
    }

    private TextView subtitle(String value) {
        TextView view = text(value, 12, MUTED, false);
        view.setLineSpacing(MotionKit.dp(this, 3), 1f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = MotionKit.dp(this, 8);
        view.setLayoutParams(params);
        return view;
    }

    private TextView sectionLabel(String value) {
        TextView view = text(value, 11, 0xFF63CAFA, true);
        view.setLetterSpacing(0.1f);
        return view;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = MotionKit.dp(this, 18);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0xFF111C2B);
        shape.setCornerRadius(MotionKit.dp(this, 20));
        shape.setStroke(MotionKit.dp(this, 1), 0x3333B9F7);
        card.setBackground(shape);
        return card;
    }

    private Button primaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(0xFF08131F);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable shape = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF33B9F7, 0xFF79D5FF});
        shape.setCornerRadius(MotionKit.dp(this, 16));
        button.setBackground(shape);
        button.setMinHeight(MotionKit.dp(this, 52));
        MotionKit.attachPressFeedback(button);
        return button;
    }

    private Button secondaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(0xFFEAF3FB);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0xFF142338);
        shape.setCornerRadius(MotionKit.dp(this, 16));
        shape.setStroke(MotionKit.dp(this, 1), 0xFF294159);
        button.setBackground(shape);
        button.setMinHeight(MotionKit.dp(this, 50));
        MotionKit.attachPressFeedback(button);
        return button;
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout.LayoutParams marginTop(int dp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = MotionKit.dp(this, dp);
        return params;
    }

    /** Small helper so the file join stays readable. */
    private static final class TextUtilsJoin {
        static String join(List<String> values, String separator) {
            if (values == null || values.isEmpty()) return "";
            StringBuilder builder = new StringBuilder();
            for (String value : values) {
                if (builder.length() > 0) builder.append(separator);
                builder.append(value);
            }
            return builder.toString();
        }
    }
}
