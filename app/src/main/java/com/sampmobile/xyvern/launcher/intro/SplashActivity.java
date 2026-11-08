package com.hopepride.samp.launcher.intro;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
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

import androidx.appcompat.app.AppCompatActivity;

import com.hopepride.samp.R;
import com.hopepride.samp.diagnostics.Diagnostics;
import com.hopepride.samp.launcher.MainActivity;

/**
 * Splash pertama yang dilihat pengguna: logo masuk dengan fade dan skala, judul
 * menyusul, lalu indikator progres berjalan selama proses start berlangsung.
 *
 * Indikatornya indeterminate — tidak ada persentase palsu. Lama tampil mengikuti
 * waktu animasi, bukan proses yang diklaim selesai.
 */
public class SplashActivity extends AppCompatActivity {

    public static final String UI_PREFS = "hopepride_ui";
    public static final String KEY_ONBOARDING_DONE = "onboarding_done";
    public static final String KEY_ANIMATIONS = "animations_enabled";

    private static final int BG = Color.parseColor("#070B14");
    private static final int ACCENT = Color.parseColor("#33B9F7");
    private static final int TEXT = Color.parseColor("#EAF3FB");
    private static final int MUTED = Color.parseColor("#7F93A8");

    private boolean navigated;
    private ImageView logoView;
    private LinearLayout columnView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Diagnostics.event("SPLASH_SHOWN");

        setContentView(buildUi());
        animateIn();

        long delay = animationsEnabled() ? 1500L : 350L;
        new Handler(Looper.getMainLooper()).postDelayed(this::goNext, delay);
    }

    private boolean animationsEnabled() {
        SharedPreferences prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
        return prefs.getBoolean(KEY_ANIMATIONS, true);
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        View glow = new View(this);
        GradientDrawable glowShape = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#16233A"), BG});
        glow.setBackground(glowShape);
        root.addView(glow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(132), dp(132));
        column.addView(logo, logoParams);

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

        TextView version = new TextView(this);
        version.setText("Versi " + versionName());
        version.setTextColor(MUTED);
        version.setTextSize(11);
        version.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams versionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        versionParams.topMargin = dp(28);
        column.addView(version, versionParams);

        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(120), dp(4));
        progressParams.topMargin = dp(18);
        column.addView(progress, progressParams);

        return root;
    }

    private void animateIn() {
        final View root = findViewById(android.R.id.content);
        root.post(() -> {
            View logo = logoView;
            if (logo == null) return;

            logo.setAlpha(0f);
            logo.setScaleX(0.82f);
            logo.setScaleY(0.82f);

            long duration = animationsEnabled() ? 520L : 0L;
            logo.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(duration)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();

            if (duration == 0L) return;

            ViewGroup column = columnView;
            if (column == null) column = (ViewGroup) logo.getParent();
            for (int index = 1; index < column.getChildCount(); index++) {
                View child = column.getChildAt(index);
                child.setAlpha(0f);
                child.setTranslationY(dp(14));
                child.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setStartDelay(220L + index * 90L)
                        .setDuration(360L)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            }

            ObjectAnimator pulse = ObjectAnimator.ofFloat(logo, View.ALPHA, 1f, 0.86f, 1f);
            pulse.setStartDelay(600L);
            pulse.setDuration(900L);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.start();
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

        Intent intent = new Intent(this,
                onboardingDone ? MainActivity.class : OnboardingActivity.class);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();

        Diagnostics.event("SPLASH_FINISHED onboarding_done=" + onboardingDone);
    }

    @Override
    public void onBackPressed() {
        // Nothing to go back to from the splash screen.
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
