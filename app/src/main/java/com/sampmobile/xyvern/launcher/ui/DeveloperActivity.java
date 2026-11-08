package com.hopepride.samp.launcher.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.hopepride.samp.BuildConfig;
import com.hopepride.samp.R;

/**
 * Halaman Developer: HopePride Developers.
 *
 * Content comes from {@link DeveloperProfile}, which only knows facts that exist in
 * the repository (the two creator names from the launcher strings, app name,
 * version, build type). No photo, handle or address is invented: the avatars are
 * initial letters drawn in code, and the contact row is only added when a real
 * https link was supplied.
 *
 * Entrance motion is staggered per card and honours the reduced-motion setting.
 */
public class DeveloperActivity extends AppCompatActivity {

    private static final int BG_TOP = 0xFF0B1420;
    private static final int BG_BOTTOM = 0xFF060B12;
    private static final int ACCENT = 0xFF33B9F7;
    private static final int CARD = 0xFF111C2B;
    private static final int TEXT = 0xFFEAF3FB;
    private static final int MUTED = 0xFF8FA3B8;

    private LinearLayout column;
    private MotionBackgroundView background;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MotionKit.init(this);
        setContentView(buildUi());
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

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);

        background = new MotionBackgroundView(this);
        background.setColors(BG_TOP, BG_BOTTOM, ACCENT);
        root.addView(background, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = MotionKit.dp(this, 20);
        column.setPadding(pad, MotionKit.dp(this, 44), pad, MotionKit.dp(this, 32));
        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        DeveloperProfile profile = DeveloperProfile.create(
                getString(R.string.app_name),
                "HopePride SAMP Android",
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                BuildConfig.DEBUG ? "debug" : "release",
                readContactUrl());

        // ------------------------------------------------ header with logo
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.hopepride_logo);
        LinearLayout.LayoutParams logoParams =
                new LinearLayout.LayoutParams(MotionKit.dp(this, 84), MotionKit.dp(this, 84));
        logoParams.gravity = Gravity.CENTER_HORIZONTAL;
        column.addView(logo, logoParams);

        TextView title = text("HopePride Developers", 24, TEXT, true);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        column.addView(title, marginTop(14));

        TextView subtitle = text(profile.projectName, 13, MUTED, false);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        column.addView(subtitle, marginTop(6));

        TextView build = text(profile.buildLine(), 12, MUTED, false);
        build.setGravity(Gravity.CENTER_HORIZONTAL);
        column.addView(build, marginTop(10));

        // ------------------------------------------------ developer cards
        for (DeveloperProfile.Person person : profile.people) {
            column.addView(personCard(person), marginTop(22));
        }

        // --------------------------------------------------------- project
        LinearLayout project = card();
        project.addView(text("Tentang proyek", 15, TEXT, true));
        TextView projectBody = text(
                "HopePride SAMP Android adalah klien SA-MP untuk Android: launcher dengan "
                        + "pengelola server, pengelola data game, pusat diagnostik, dan klien game "
                        + "berbasis RenderWare.", 13, MUTED, false);
        projectBody.setLineSpacing(MotionKit.dp(this, 3), 1f);
        project.addView(projectBody, marginTop(8));
        column.addView(project, marginTop(22));

        // ---------------------------------------------------------- contact
        if (profile.hasContact()) {
            TextView contact = actionButton("Buka tautan resmi", profile.contactUrl);
            contact.setOnClickListener(v -> openLink(profile.contactUrl));
            column.addView(contact, marginTop(22));
        } else {
            // Honest placeholder instead of a decorative button: there is no
            // verified public channel yet, so nothing is offered to tap.
            TextView note = text(
                    "Belum ada tautan resmi yang terverifikasi, jadi tidak ada tombol kontak di sini.",
                    12, MUTED, false);
            note.setGravity(Gravity.CENTER_HORIZONTAL);
            column.addView(note, marginTop(24));
        }

        TextView copyright = text(profile.copyright, 12, MUTED, false);
        copyright.setGravity(Gravity.CENTER_HORIZONTAL);
        column.addView(copyright, marginTop(28));

        // Staggered entrance, last so every child exists and has a position.
        MotionKit.staggerChildren(column);
        return root;
    }

    /**
     * Optional public contact link, injected at build time when it exists.
     *
     * Deliberately not BuildConfig.EVENT_RELAY_URL: that is the analytics relay,
     * not an address owned by the developers, and putting it behind a "contact"
     * button would be misleading. The manifest meta-data below is only added by a
     * build that actually has an official link.
     */
    private String readContactUrl() {
        try {
            android.content.pm.ApplicationInfo info = getPackageManager()
                    .getApplicationInfo(getPackageName(),
                            android.content.pm.PackageManager.GET_META_DATA);
            if (info == null || info.metaData == null) return null;
            Object value = info.metaData.get("com.hopepride.samp.CONTACT_URL");
            return value instanceof String ? (String) value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private View personCard(DeveloperProfile.Person person) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);

        TextView avatar = new TextView(this);
        avatar.setText(person.initials());
        avatar.setTextColor(Color.WHITE);
        avatar.setTextSize(22);
        avatar.setTypeface(Typeface.DEFAULT_BOLD);
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable shape = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF2B6CB0, 0xFF33B9F7});
        shape.setShape(GradientDrawable.OVAL);
        avatar.setBackground(shape);
        LinearLayout.LayoutParams avatarParams =
                new LinearLayout.LayoutParams(MotionKit.dp(this, 62), MotionKit.dp(this, 62));
        card.addView(avatar, avatarParams);

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        detailsParams.leftMargin = MotionKit.dp(this, 16);

        details.addView(text(person.name, 18, TEXT, true));
        details.addView(text(person.role, 12, ACCENT, true), marginTop(3));

        TextView description = text(person.description, 12, MUTED, false);
        description.setLineSpacing(MotionKit.dp(this, 2), 1f);
        details.addView(description, marginTop(8));

        card.addView(details, detailsParams);

        // The whole card answers to touch, which is what makes it feel like a
        // surface rather than a picture.
        MotionKit.attachPressFeedback(card);
        return card;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = MotionKit.dp(this, 18);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(CARD);
        shape.setCornerRadius(MotionKit.dp(this, 20));
        shape.setStroke(MotionKit.dp(this, 1), 0x3333B9F7);
        card.setBackground(shape);
        card.setElevation(MotionKit.dp(this, 2));
        return card;
    }

    private TextView actionButton(String label, final String url) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextColor(0xFF08131F);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        GradientDrawable shape = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF33B9F7, 0xFF79D5FF});
        shape.setCornerRadius(MotionKit.dp(this, 16));
        button.setBackground(shape);
        button.setPadding(MotionKit.dp(this, 20), MotionKit.dp(this, 15),
                MotionKit.dp(this, 20), MotionKit.dp(this, 15));
        MotionKit.attachPressFeedback(button);
        return button;
    }

    private void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "Tidak ada aplikasi untuk membuka tautan.", Toast.LENGTH_SHORT).show();
        }
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(sizeSp);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout.LayoutParams marginTop(int dp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = MotionKit.dp(this, dp);
        return params;
    }
}
