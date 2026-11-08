package com.hopepride.samp.launcher.intro;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.hopepride.samp.diagnostics.Diagnostics;
import com.hopepride.samp.launcher.MainActivity;

/**
 * Perkenalan tiga halaman saat aplikasi pertama kali dibuka. Halaman berganti
 * dengan animasi geser + fade, tombol "Lewati" tersedia di semua halaman, dan
 * status selesai disimpan supaya tidak muncul lagi.
 */
public class OnboardingActivity extends AppCompatActivity {

    private static final int BG = Color.parseColor("#070B14");
    private static final int CARD = Color.parseColor("#12203A");
    private static final int ACCENT = Color.parseColor("#33B9F7");
    private static final int TEXT = Color.parseColor("#EAF3FB");
    private static final int MUTED = Color.parseColor("#7F93A8");

    private static final String[][] PAGES = {
            {"Selamat datang di HopePride",
                    "Klien SA-MP untuk Android dengan pengelola server, pengelola mod, dan pusat crash "
                            + "yang bisa dibuka langsung dari launcher."},
            {"Atur server dan data game",
                    "Simpan server favorit, unduh data game sekali, lalu masuk tanpa mengulang pengaturan. "
                            + "Unduhan yang terputus bisa dilanjutkan."},
            {"Kalau ada masalah, ada laporan",
                    "Pusat Crash membaca crash_native.txt dan samp_log.txt, mengenali sinyal dan pola yang "
                            + "dikenal, lalu bisa membagikan laporannya. Safe mode tersedia bila start gagal."}
    };

    private final TextView[] dots = new TextView[PAGES.length];
    private LinearLayout pageHost;
    private Button nextButton;
    private int page;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Diagnostics.event("ONBOARDING_SHOWN");
        setContentView(buildUi());
        showPage(0, false);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(24), dp(36), dp(24), dp(24));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);

        TextView brand = new TextView(this);
        brand.setText("HOPEPRIDE");
        brand.setTextColor(ACCENT);
        brand.setTextSize(12);
        brand.setLetterSpacing(0.18f);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(brand, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button skip = new Button(this);
        skip.setText("Lewati");
        skip.setAllCaps(false);
        skip.setTextColor(MUTED);
        skip.setBackgroundColor(Color.TRANSPARENT);
        skip.setOnClickListener(v -> finishOnboarding());
        top.addView(skip);

        root.addView(top);

        pageHost = new LinearLayout(this);
        pageHost.setOrientation(LinearLayout.VERTICAL);
        pageHost.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(pageHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout dotsRow = new LinearLayout(this);
        dotsRow.setGravity(Gravity.CENTER);
        for (int index = 0; index < PAGES.length; index++) {
            TextView dot = new TextView(this);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(index == 0 ? ACCENT : CARD);
            shape.setCornerRadius(dp(4));
            dot.setBackground(shape);
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(24), dp(6));
            dotParams.setMargins(dp(4), 0, dp(4), 0);
            dotsRow.addView(dot, dotParams);
            dots[index] = dot;
        }
        root.addView(dotsRow);

        nextButton = new Button(this);
        nextButton.setText("Lanjut");
        nextButton.setAllCaps(false);
        nextButton.setTextColor(Color.parseColor("#04121E"));
        nextButton.setBackground(round(ACCENT, dp(14)));
        nextButton.setOnClickListener(v -> {
            if (page + 1 < PAGES.length) showPage(page + 1, true);
            else finishOnboarding();
        });
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        buttonParams.topMargin = dp(20);
        root.addView(nextButton, buttonParams);

        return root;
    }

    private void showPage(int target, boolean animate) {
        page = target;
        pageHost.removeAllViews();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(PAGES[target][0]);
        title.setTextColor(TEXT);
        title.setTextSize(23);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        TextView body = new TextView(this);
        body.setText(PAGES[target][1]);
        body.setTextColor(MUTED);
        body.setTextSize(14);
        body.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyParams.topMargin = dp(14);
        card.addView(body, bodyParams);

        pageHost.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        nextButton.setText(target + 1 < PAGES.length ? "Lanjut" : "Mulai");

        for (int index = 0; index < dots.length; index++) {
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(index == target ? ACCENT : CARD);
            shape.setCornerRadius(dp(4));
            dots[index].setBackground(shape);
            dots[index].setAlpha(index == target ? 1f : 0.6f);
        }

        if (!animate) {
            card.setAlpha(1f);
            return;
        }

        card.setAlpha(0f);
        card.setTranslationX(dp(60));
        card.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(280L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        animateDots();
    }

    private void animateDots() {
        for (TextView dot : dots) {
            ValueAnimator animator = ValueAnimator.ofFloat(0.9f, 1f);
            animator.setDuration(200L);
            animator.addUpdateListener(animation -> dot.setScaleX((float) animation.getAnimatedValue()));
            animator.start();
        }
    }

    private void finishOnboarding() {
        getSharedPreferences(SplashActivity.UI_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(SplashActivity.KEY_ONBOARDING_DONE, true)
                .apply();
        Diagnostics.event("ONBOARDING_FINISHED page=" + page);

        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        if (page > 0) showPage(page - 1, true);
        else finishOnboarding();
    }
}
