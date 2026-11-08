package com.hopepride.samp.launcher.intro;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.hopepride.samp.R;
import com.hopepride.samp.diagnostics.Diagnostics;
import com.hopepride.samp.launcher.MainActivity;
import com.hopepride.samp.launcher.ui.MotionBackgroundView;
import com.hopepride.samp.launcher.ui.MotionKit;
import com.hopepride.samp.launcher.ui.MotionSpec;

/**
 * Splash pertama yang dilihat pengguna.
 *
 * The motion is real but the work it reports is not faked: the logo springs in,
 * the text settles in sequence, and the view is then handed to the next screen.
 * There is no progress bar here because the launcher has not started doing
 * anything yet - the honest place for a progress indicator is the launcher's own
 * data gate, which this screen does not duplicate.
 *
 * The transition out is a shared-element style move: the logo scales up slightly
 * and fades while the destination fades in, so it does not look like two unrelated
 * screens swapping.
 */
public class SplashActivity extends AppCompatActivity {

    public static final String UI_PREFS = "hopepride_ui";
    public static final String KEY_ONBOARDING_DONE = "onboarding_done";
    public static final String KEY_ANIMATIONS = "animations_enabled";

    private static final int BG_TOP = 0xFF132A44;
    private static final int BG_BOTTOM = 0xFF05090F;
    private static final int TEXT = 0xFFEAF3FB;
    private static final int MUTED = 0xFF7F93A8;

    private boolean navigated;
    private MotionBackgroundView background;
    private ImageView logoView;
    private LinearLayout columnView;
    private View glowView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Diagnostics.event("SPLASH_SHOWN");
        MotionKit.init(this);

        setContentView(buildUi());
        animateIn();

        // Short hold: the actual preparation happens in MainActivity's data gate,
        // which shows its own progress. This screen only exists to avoid a cold
        // white frame and to set the tone.
        long delay = animationsEnabled() ? 1250L : 250L;
        new Handler(Looper.getMainLooper()).postDelayed(this::goNext, delay);
    }

    private boolean animationsEnabled() {
        SharedPreferences prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
        return prefs.getBoolean(KEY_ANIMATIONS, true) && MotionKit.shouldAnimate(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (background != null && animationsEnabled()) background.start();
    }

    @Override
    protected void onStop() {
        if (background != null) background.stop();
        super.onStop();
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);

        background = new MotionBackgroundView(this);
        background.setColors(BG_TOP, BG_BOTTOM, 0xFF33B9F7);
        root.addView(background, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Soft glow behind the logo, drawn as a stretched circle so there is no
        // bitmap to decode and no allocation per frame.
        glowView = new View(this);
        android.graphics.drawable.GradientDrawable glow = new android.graphics.drawable.GradientDrawable();
        glow.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        glow.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientRadius(dp(210));
        glow.setColors(new int[]{0x5533B9F7, 0x0033B9F7});
        glowView.setBackground(glow);
        FrameLayout.LayoutParams glowParams =
                new FrameLayout.LayoutParams(dp(360), dp(360));
        glowParams.gravity = Gravity.CENTER;
        root.addView(glowView, glowParams);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams columnParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        columnParams.gravity = Gravity.CENTER;
        root.addView(column, columnParams);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.hopepride_logo);
        logoView = logo;
        columnView = column;
        column.addView(logo, new LinearLayout.LayoutParams(dp(132), dp(132)));

        TextView title = new TextView(this);
        title.setText("HopePride SAMP");
        title.setTextColor(TEXT);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.06f);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(20);
        column.addView(title, titleParams);

        TextView subtitle = new TextView(this);
        subtitle.setText("Klien SA-MP Android");
        subtitle.setTextColor(MUTED);
        subtitle.setTextSize(13);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(6);
        column.addView(subtitle, subtitleParams);

        TextView credit = new TextView(this);
        credit.setText("Pall & Fann");
        credit.setTextColor(0xFF63CAFA);
        credit.setTextSize(11);
        credit.setLetterSpacing(0.1f);
        credit.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams creditParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        creditParams.topMargin = dp(22);
        column.addView(credit, creditParams);

        TextView version = new TextView(this);
        version.setText("Versi " + versionName());
        version.setTextColor(0xFF5F7488);
        version.setTextSize(10);
        version.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams versionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        versionParams.topMargin = dp(8);
        column.addView(version, versionParams);

        return root;
    }

    private void animateIn() {
        final View content = findViewById(android.R.id.content);
        if (content == null) return;

        if (!animationsEnabled()) {
            logoView.setAlpha(1f);
            return;
        }

        content.post(() -> {
            if (logoView == null) return;

            logoView.setAlpha(0f);
            logoView.setScaleX(0.72f);
            logoView.setScaleY(0.72f);
            logoView.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(MotionSpec.DURATION_HERO)
                    .setInterpolator(MotionKit.springInterpolator())
                    .start();

            if (glowView != null) {
                glowView.setAlpha(0f);
                glowView.setScaleX(0.7f);
                glowView.setScaleY(0.7f);
                glowView.animate()
                        .alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(MotionSpec.DURATION_HERO + 120L)
                        .setInterpolator(MotionKit.enterInterpolator())
                        .start();
            }

            // Text settles after the logo, one line at a time.
            for (int index = 1; index < columnView.getChildCount(); index++) {
                View child = columnView.getChildAt(index);
                child.setAlpha(0f);
                child.setTranslationY(dp(14));
                child.animate()
                        .alpha(1f).translationY(0f)
                        .setStartDelay(220L + index * MotionSpec.STAGGER_STEP)
                        .setDuration(MotionSpec.DURATION_MEDIUM)
                        .setInterpolator(MotionKit.enterInterpolator())
                        .start();
            }
        });
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "1.7.0";
        }
    }

    private void goNext() {
        if (navigated || isFinishing() || isDestroyed()) return;
        navigated = true;

        SharedPreferences prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
        boolean onboardingDone = prefs.getBoolean(KEY_ONBOARDING_DONE, false);

        Diagnostics.event("SPLASH_FINISHED onboarding_done=" + onboardingDone);

        final Intent intent = new Intent(this,
                onboardingDone ? MainActivity.class : OnboardingActivity.class);

        if (!animationsEnabled() || logoView == null) {
            startActivity(intent);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
            return;
        }

        // Exit move: the logo grows a little while everything fades, then the next
        // screen fades in. Reads as one continuous motion from splash to launcher.
        logoView.animate()
                .scaleX(1.18f).scaleY(1.18f)
                .alpha(0f)
                .setDuration(MotionSpec.DURATION_MEDIUM)
                .setInterpolator(MotionKit.enterInterpolator())
                .start();
        columnView.animate().alpha(0f)
                .setDuration(MotionSpec.DURATION_FAST)
                .start();
        if (glowView != null) {
            glowView.animate().alpha(0f).setDuration(MotionSpec.DURATION_MEDIUM).start();
        }

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            startActivity(intent);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        }, MotionSpec.DURATION_FAST + 40L);
    }

    @Override
    public void onBackPressed() {
        // Nothing to go back to from the splash screen.
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
