package com.hopepride.samp.game.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.hopepride.samp.BuildConfig;
import com.hopepride.samp.R;
import com.hopepride.samp.launcher.ui.MotionKit;
import com.hopepride.samp.launcher.ui.MotionBackgroundView;

/**
 * The in-game loading screen.
 *
 * What changed and why:
 *
 *   * It is built in code instead of inflated from loadingscreen.xml, because
 *     every previous screen depended on the layout having the exact ids the code
 *     expected, and that is how showLoadingScreen() ended up doing nothing.
 *   * The progress bar is no longer indeterminate. It follows
 *     {@link StartupStageModel}, which only advances when the native layer reports
 *     a stage it actually reached (see EngineTelemetry / GameStartupStage.h).
 *   * If the native stage is unavailable the screen says so and keeps the bar
 *     hidden rather than pretending to load.
 *   * The stage label animates on change, the background drifts only while the
 *     screen is visible, and reduced motion is respected.
 */
public class LoadingScreen {

    /** Polling interval for the native stage. Cheap call, once per 250 ms. */
    private static final long POLL_INTERVAL_MS = 250L;

    private final Activity activity;
    private final StartupStageModel model = new StartupStageModel();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private MotionBackgroundView background;
    private ImageView logo;
    private TextView stageLabel;
    private TextView detailLabel;
    private TextView versionLabel;
    private ProgressBar progressBar;

    private boolean visible;
    private boolean nativeStageAvailable = true;
    private int lastReportedStage = -1;

    public LoadingScreen(Activity activity) {
        this.activity = activity;
        this.root = build();
        activity.addContentView(root, new ViewGroup.LayoutParams(-1, -1));
    }

    // ------------------------------------------------------------------ build

    private FrameLayout build() {
        FrameLayout container = new FrameLayout(activity);
        container.setVisibility(View.GONE);
        container.setClickable(true);   // do not let taps reach the game surface

        background = new MotionBackgroundView(activity);
        background.setColors(0xFF0B1420, 0xFF05090F, 0xFF33B9F7);
        container.addView(background, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams columnParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        columnParams.gravity = Gravity.CENTER;
        container.addView(column, columnParams);

        logo = new ImageView(activity);
        logo.setImageResource(R.drawable.hopepride_logo);
        column.addView(logo, new LinearLayout.LayoutParams(dp(96), dp(96)));

        TextView title = new TextView(activity);
        title.setText("HOPEPRIDE");
        title.setTextColor(0xFFF2F7FC);
        title.setTextSize(20);
        title.setLetterSpacing(0.14f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(16);
        column.addView(title, titleParams);

        stageLabel = new TextView(activity);
        stageLabel.setTextColor(0xFF9FB4CA);
        stageLabel.setTextSize(13);
        stageLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stageParams.topMargin = dp(18);
        column.addView(stageLabel, stageParams);

        progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF33B9F7));
        progressBar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0x22FFFFFF));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(dp(220), dp(5));
        barParams.topMargin = dp(16);
        column.addView(progressBar, barParams);

        detailLabel = new TextView(activity);
        detailLabel.setTextColor(0xFF7E93A8);
        detailLabel.setTextSize(11);
        detailLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = dp(10);
        column.addView(detailLabel, detailParams);

        versionLabel = new TextView(activity);
        versionLabel.setText("Versi " + BuildConfig.VERSION_NAME
                + "  ·  Pall & Fann");
        versionLabel.setTextColor(0xFF5F7488);
        versionLabel.setTextSize(10);
        versionLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams versionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        versionParams.topMargin = dp(22);
        column.addView(versionLabel, versionParams);

        return container;
    }

    // ---------------------------------------------------------------- control

    public void show() {
        runOnUi(() -> {
            if (root == null) return;
            model.reset();
            lastReportedStage = -1;
            progressBar.setProgress(0);
            stageLabel.setText(StartupStageModel.labelFor(StartupStageModel.STAGE_UNKNOWN));
            detailLabel.setText("");
            root.setVisibility(View.VISIBLE);
            visible = true;
            if (background != null) background.start();
            animateIn();
            handler.removeCallbacks(poll);
            handler.postDelayed(poll, POLL_INTERVAL_MS);
        });
    }

    /**
     * Hides the screen. Called by the engine when it takes over, so the screen is
     * never dismissed on a timer.
     */
    public void hide() {
        runOnUi(() -> {
            visible = false;
            handler.removeCallbacks(poll);
            if (background != null) background.stop();
            if (root == null) return;
            if (!MotionKit.shouldAnimate(activity)) {
                root.setVisibility(View.GONE);
                return;
            }
            root.animate().alpha(0f).setDuration(MotionKit.DURATION_BASE)
                    .withEndAction(() -> {
                        if (root == null) return;
                        root.setVisibility(View.GONE);
                        root.setAlpha(1f);
                    })
                    .start();
        });
    }

    public void setProgress(int value) {
        // Kept for the native ShowLoadingScreen path: a value from outside still
        // has to look sane, so it is clamped instead of trusted.
        runOnUi(() -> {
            int clamped = Math.max(0, Math.min(100, value));
            if (progressBar != null) MotionKit.animateProgress(progressBar, clamped);
        });
    }

    public boolean isVisible() {
        return visible;
    }

    // ------------------------------------------------------------------ polling

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            int reported = readNativeStage();
            if (reported < 0) {
                // Native side unavailable: say so, and stop claiming progress.
                if (nativeStageAvailable) {
                    nativeStageAvailable = false;
                    progressBar.setVisibility(View.INVISIBLE);
                    detailLabel.setText("Status detail tidak tersedia di build ini.");
                }
            } else {
                nativeStageAvailable = true;
                progressBar.setVisibility(View.VISIBLE);
                if (reported != lastReportedStage) {
                    lastReportedStage = reported;
                    if (model.update(reported)) applyStage();
                }
            }
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    /** Reads the stage the engine reported. -1 when the native call is missing. */
    private int readNativeStage() {
        try {
            return com.hopepride.samp.game.SAMP.nativeStartupStage();
        } catch (UnsatisfiedLinkError error) {
            return -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private void applyStage() {
        final String label = model.label();
        MotionKit.animateProgress(progressBar, model.percent());

        if (!MotionKit.shouldAnimate(activity)) {
            stageLabel.setText(label);
            detailLabel.setText(progressDetail());
            return;
        }

        // Label swap: fade out, change text, fade in. Short enough to read.
        stageLabel.animate().alpha(0f).setDuration(MotionSpecShort.FADE)
                .withEndAction(() -> {
                    stageLabel.setText(label);
                    stageLabel.setTranslationY(dp(6));
                    stageLabel.animate().alpha(1f).translationY(0f)
                            .setDuration(MotionSpecShort.FADE)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                })
                .start();
        detailLabel.setText(progressDetail());
        MotionKit.pulse(progressBar);
    }

    private String progressDetail() {
        if (model.finished()) return "Menyelesaikan...";
        return model.percent() + "%  ·  langkah " + model.completedSteps() + "/" + model.totalSteps();
    }

    private void animateIn() {
        if (!MotionKit.shouldAnimate(activity)) {
            logo.setAlpha(1f);
            logo.setScaleX(1f);
            logo.setScaleY(1f);
            return;
        }
        logo.setAlpha(0f);
        logo.setScaleX(0.86f);
        logo.setScaleY(0.86f);
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(MotionKit.DURATION_HERO)
                .setInterpolator(MotionKit.springInterpolator())
                .start();
    }

    // ------------------------------------------------------------------ helper

    /** Small local copy so this file does not need the launcher MotionSpec. */
    private static final class MotionSpecShort {
        static final long FADE = 120L;
    }

    private void runOnUi(Runnable action) {
        if (activity == null || activity.isFinishing()) return;
        activity.runOnUiThread(action);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
