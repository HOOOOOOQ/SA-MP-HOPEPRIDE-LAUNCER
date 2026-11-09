package com.hopepride.samp.launcher.fragments;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import com.joom.paranoid.Obfuscate;
import com.hopepride.samp.R;
import com.hopepride.samp.launcher.MainActivity;
import com.hopepride.samp.launcher.ui.MotionKit;
import com.hopepride.samp.launcher.util.LauncherEvents;
import com.hopepride.samp.launcher.util.LauncherVersion;
import com.hopepride.samp.launcher.util.SharedPreferenceCore;

import org.ini4j.Wini;

import java.io.File;
import java.io.IOException;

/**
 * Settings page of the launcher.
 *
 * Lifecycle contract, which a crash in this file broke once already:
 *
 *   onCreateView()  inflate, return the view, and nothing else. No requireView(),
 *                   no requireContext(), no listener that can fire during
 *                   inflation, no View touched after the return statement.
 *   onViewCreated() bind the view, then load state into it.
 *   onResume()      re-read preferences; the view is guaranteed to exist here.
 *   onDestroyView() drop every view reference, cancel listeners, null the Wini
 *                   handle so no callback writes through a dead view.
 *
 * Why the separation matters: FragmentPagerAdapter calls onCreateView() from
 * FragmentStateManager.createView(), and the framework only publishes
 * Fragment.mView *after* onCreateView() returns. Calling requireView() inside
 * onCreateView() therefore throws
 *   IllegalStateException: Fragment SettingsFragment did not return a View ...
 * which is exactly the fatal crash reported from MainActivity -> setOffscreenPageLimit().
 */
@Obfuscate
public class SettingsFragment extends Fragment {

    private static final String APP_PREFS = "com.hopepride.samp";

    private static final String[] VERSIONS = {"0.3.7", "0.3.DL-R1", "0.3.7-R5"};

    /** settings.ini handle. Null until onViewCreated() and after onDestroyView(). */
    @Nullable private Wini mWini;

    private EditText mNickName;
    private SwitchCompat mKeyboardSwitch;
    private SwitchCompat mVoiceSwitch;
    private SwitchCompat mModifySwitch;
    private SwitchCompat mAMLSwitch;
    private SwitchCompat mCefSwitch;
    private SwitchCompat mFPSSwitch;
    private SwitchCompat mReduceMotionSwitch;
    private SwitchCompat mAnimationsSwitch;
    private SeekBar mMessagesSeekBar;
    private TextView mMessagesText;
    private SeekBar mFPSSeekBar;
    private TextView mFPSText;
    private Spinner mVersionSpinner;

    /** True only between onViewCreated() and onDestroyView(). */
    private boolean viewReady;

    // ------------------------------------------------------------------ view

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater layoutInflater,
                             @Nullable ViewGroup viewGroup,
                             @Nullable Bundle bundle) {
        // Inflate and return. Everything else belongs to onViewCreated(), where the
        // Fragment's view already exists.
        View view = layoutInflater.inflate(R.layout.fragment_settings, viewGroup, false);

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).hideKeyboard(getActivity());
        }

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewReady = true;

        mNickName = view.findViewById(R.id.settings_nickname);
        mVersionSpinner = view.findViewById(R.id.spinner);
        mKeyboardSwitch = view.findViewById(R.id.keyboard_switch);
        mFPSSwitch = view.findViewById(R.id.fps_switch);
        mVoiceSwitch = view.findViewById(R.id.voice_switch);
        mModifySwitch = view.findViewById(R.id.modify_switch);
        mAMLSwitch = view.findViewById(R.id.aml_switch);
        mCefSwitch = view.findViewById(R.id.cef_switch);
        mMessagesSeekBar = view.findViewById(R.id.messages_seekbar);
        mMessagesText = view.findViewById(R.id.messages_count);
        mFPSSeekBar = view.findViewById(R.id.fps_seekbar);
        mFPSText = view.findViewById(R.id.fps_count);
        mReduceMotionSwitch = view.findViewById(R.id.reduce_motion_switch);
        mAnimationsSwitch = view.findViewById(R.id.animations_switch);

        bindDiagnosticShare(view);
        bindAnimations(view);
        bindReduceMotion();
        bindHelpCentre(view);
        bindEventReporting(view);
        bindVersionSpinner();
        bindNickname();
        bindMessageSeekBar();
        bindFpsSeekBar();
        bindToggles();

        // Entrance motion for the settings cards, staggered by reading order.
        if (view instanceof ViewGroup) {
            MotionKit.staggerChildren((ViewGroup) view);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!viewReady || getView() == null) return;
        loadPreferencesIntoToggles();
        loadVersionSelection();
        loadSliderValues();
    }

    @Override
    public void onDestroyView() {
        viewReady = false;
        // The Wini handle writes to settings.ini; after this point no callback may
        // reach it, so it is dropped together with the view references.
        mWini = null;
        mNickName = null;
        mVersionSpinner = null;
        mKeyboardSwitch = null;
        mVoiceSwitch = null;
        mModifySwitch = null;
        mAMLSwitch = null;
        mCefSwitch = null;
        mFPSSwitch = null;
        mReduceMotionSwitch = null;
        mAnimationsSwitch = null;
        mMessagesSeekBar = null;
        mMessagesText = null;
        mFPSSeekBar = null;
        mFPSText = null;
        super.onDestroyView();
    }

    // ---------------------------------------------------------------- binding

    private void bindDiagnosticShare(View view) {
        View button = view.findViewById(R.id.share_diagnostic_logs);
        if (button == null) return;
        MotionKit.attachPressFeedback(button);
        button.setOnClickListener(v -> {
            if (!viewReady) return;
            com.hopepride.samp.diagnostics.DiagnosticShare.confirm(requireActivity());
        });
    }

    private void bindAnimations(View view) {
        if (mAnimationsSwitch == null) return;
        // The animations switch lives in the same store MotionKit uses; init() is
        // called by preferences() so the handle is never null.
        android.content.SharedPreferences uiPrefs = MotionKit.preferences(requireContext());
        if (uiPrefs == null) return;

        mAnimationsSwitch.setChecked(uiPrefs.getBoolean(
                com.hopepride.samp.launcher.intro.SplashActivity.KEY_ANIMATIONS, true));
        mAnimationsSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (!viewReady) return;
            uiPrefs.edit().putBoolean(
                    com.hopepride.samp.launcher.intro.SplashActivity.KEY_ANIMATIONS, enabled).apply();
        });
    }

    private void bindReduceMotion() {
        if (mReduceMotionSwitch == null) return;
        MotionKit.init(requireContext());
        mReduceMotionSwitch.setChecked(MotionKit.reducedMotion(requireContext()));
        mReduceMotionSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (!viewReady) return;
            MotionKit.setReducedMotion(requireContext(), enabled);
        });
    }

    /**
     * Help centre shortcuts. Every target is a real Activity declared in the
     * manifest; none of these is a decorative button.
     */
    private void bindHelpCentre(View view) {
        wire(view, R.id.open_data_center,
                com.hopepride.samp.launcher.data.DataCenterActivity.class);
        wire(view, R.id.open_crash_center,
                com.hopepride.samp.launcher.crash.CrashCenterActivity.class);
        wire(view, R.id.open_mod_manager,
                com.hopepride.samp.launcher.mods.ModManagerActivity.class);
        wire(view, R.id.open_developer_page,
                com.hopepride.samp.launcher.ui.DeveloperActivity.class);
    }

    private void bindEventReporting(View view) {
        SwitchCompat eventReporting = view.findViewById(R.id.event_reporting_switch);
        if (eventReporting == null) return;

        TextView description = view.findViewById(R.id.event_reporting_description);
        boolean configured = LauncherEvents.isConfigured();
        eventReporting.setEnabled(configured);
        eventReporting.setChecked(configured && LauncherEvents.preferences(requireContext())
                .getBoolean(LauncherEvents.ENABLED, LauncherEvents.DEFAULT_ENABLED));

        if (description != null) {
            if (!configured) {
                description.setText("Tidak tersedia: webhook Discord atau relay belum "
                        + "dikonfigurasi saat build.");
            } else if (LauncherEvents.isDirectDiscord()) {
                description.setText("Aktif secara default jika webhook dikonfigurasi; dapat "
                        + "dimatikan di sini. Alamat dan port server dikirim saat mencoba masuk. "
                        + "Discord dapat melihat IP internet; nickname dan password tidak dikirim.");
            }
        }

        eventReporting.setOnCheckedChangeListener((button, enabled) -> {
            if (!viewReady) return;
            LauncherEvents.preferences(requireContext()).edit()
                    .putBoolean(LauncherEvents.ENABLED, enabled).apply();
        });
    }

    private void bindVersionSpinner() {
        if (mVersionSpinner == null) return;

        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(requireContext(), R.layout.spinner_item, VERSIONS);
        adapter.setDropDownViewResource(R.layout.spinner_item);
        mVersionSpinner.setAdapter(adapter);

        // Keep every currently supported selection; only legacy/out-of-range values
        // are normalised, so nobody silently changes their protocol version.
        SharedPreferenceCore versionPrefs = new SharedPreferenceCore();
        Context app = requireContext().getApplicationContext();
        int rawVersion = versionPrefs.getInt(app, "VERSION", 0);
        int versionIndex = LauncherVersion.normalizeIndex(rawVersion, VERSIONS.length);
        if (versionIndex != rawVersion) versionPrefs.setInt(app, "VERSION", versionIndex);
        mVersionSpinner.setSelection(versionIndex);

        mVersionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View selected, int position, long id) {
                if (!viewReady) return;
                Context context = getContext();
                if (context == null) return;
                new SharedPreferenceCore().setInt(context.getApplicationContext(), "VERSION", position);
                writeIni("client", "version", VERSIONS[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void bindNickname() {
        if (mNickName == null) return;

        // The display name lives in settings.ini; the launcher mirrors it in
        // preferences so the dashboard can show it before the ini is read.
        String existing = readNicknameFromIni();
        mNickName.setText(existing == null || existing.isEmpty() ? "YourName" : existing);

        mNickName.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (!viewReady) return;
                Context context = getContext();
                if (context == null) return;
                context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
                        .edit().putString("nickname", s.toString()).apply();
            }

            @Override public void afterTextChanged(Editable s) { }
        });
    }

    private void bindMessageSeekBar() {
        if (mMessagesSeekBar == null) return;
        mMessagesSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!viewReady) return;
                int real = messageCountFor(progress);
                Context context = getContext();
                if (context == null) return;
                new SharedPreferenceCore().setInt(context.getApplicationContext(),
                        "MESSAGE_COUNT", real);
                writeIni("gui", "ChatMaxMessages", real);
                if (mMessagesText != null) mMessagesText.setText(String.valueOf(real));
            }

            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
    }

    private void bindFpsSeekBar() {
        if (mFPSSeekBar == null) return;
        mFPSSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!viewReady) return;
                int real = fpsLimitFor(progress);
                Context context = getContext();
                if (context == null) return;
                new SharedPreferenceCore().setInt(context.getApplicationContext(),
                        "FPS_LIMIT", real);
                writeIni("gui", "FPSLimit", real);
                if (mFPSText != null) mFPSText.setText(String.valueOf(real));
            }

            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
    }

    private void bindToggles() {
        if (mAMLSwitch != null) {
            mAMLSwitch.setChecked(requireContext()
                    .getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
                    .getBoolean("aml_enabled", true));
            mAMLSwitch.setOnCheckedChangeListener((button, enabled) -> {
                if (!viewReady) return;
                Context context = getContext();
                if (context == null) return;
                context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean("aml_enabled", enabled).apply();
                Toast.makeText(context, "Pengaturan AML tersimpan. Mulai ulang proses game "
                        + "untuk menerapkannya.", Toast.LENGTH_LONG).show();
            });
        }

        if (mCefSwitch != null) {
            mCefSwitch.setOnCheckedChangeListener((button, enabled) -> {
                if (!viewReady) return;
                Context context = getContext();
                if (context == null) return;
                new SharedPreferenceCore().setBoolean(context.getApplicationContext(),
                        "CEF_ENABLED", enabled);
                Toast.makeText(context, enabled
                        ? "CEF WebView diaktifkan"
                        : "CEF WebView dimatikan. Restart game untuk menerapkan.",
                        Toast.LENGTH_LONG).show();
            });
        }

        if (mModifySwitch != null) {
            mModifySwitch.setOnCheckedChangeListener((button, enabled) -> {
                if (!viewReady) return;
                Context context = getContext();
                if (context == null) return;
                new SharedPreferenceCore().setBoolean(context.getApplicationContext(),
                        "MODIFIED_DATA", enabled);
            });
        }

        if (mKeyboardSwitch != null) {
            mKeyboardSwitch.setOnCheckedChangeListener((button, enabled) -> {
                if (!viewReady) return;
                Context context = getContext();
                if (context == null) return;
                new SharedPreferenceCore().setBoolean(context.getApplicationContext(),
                        "ANDROID_KEYBOARD", enabled);
                writeIni("gui", "androidkeyboard", enabled);
            });
        }

        if (mVoiceSwitch != null) {
            mVoiceSwitch.setOnCheckedChangeListener((button, enabled) -> {
                if (!viewReady) return;
                Context context = getContext();
                if (context == null) return;
                new SharedPreferenceCore().setBoolean(context.getApplicationContext(),
                        "VOICE_CHAT", enabled);
                writeIni("gui", "VoiceChatEnable", enabled);
            });
        }

        if (mFPSSwitch != null) {
            mFPSSwitch.setOnCheckedChangeListener((button, enabled) -> {
                if (!viewReady) return;
                Context context = getContext();
                if (context == null) return;
                new SharedPreferenceCore().setBoolean(context.getApplicationContext(),
                        "FPS_DISPLAY", enabled);
                writeIni("gui", "fps", enabled ? 1 : 0);
            });
        }
    }

    // ----------------------------------------------------------------- state

    private void loadPreferencesIntoToggles() {
        Context app = requireContext().getApplicationContext();
        SharedPreferenceCore prefs = new SharedPreferenceCore();

        if (mKeyboardSwitch != null) {
            mKeyboardSwitch.setChecked(prefs.getBoolean(app, "ANDROID_KEYBOARD"));
        }
        if (mVoiceSwitch != null) {
            mVoiceSwitch.setChecked(prefs.getBoolean(app, "VOICE_CHAT"));
        }
        if (mFPSSwitch != null) {
            mFPSSwitch.setChecked(prefs.getBoolean(app, "FPS_DISPLAY"));
        }
        if (mModifySwitch != null) {
            mModifySwitch.setChecked(prefs.getBoolean(app, "MODIFIED_DATA"));
        }
        if (mAMLSwitch != null) {
            mAMLSwitch.setChecked(requireContext()
                    .getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
                    .getBoolean("aml_enabled", true));
        }
        if (mCefSwitch != null) {
            // CEF defaults to ON: only an explicit OFF is honoured.
            android.content.SharedPreferences cefPrefs = app.getSharedPreferences(
                    SharedPreferenceCore.APP_PREFERENCES, 0);
            boolean cefOn = !cefPrefs.contains("CEF_ENABLED")
                    || cefPrefs.getBoolean("CEF_ENABLED", true);
            mCefSwitch.setChecked(cefOn);
        }
    }

    private void loadVersionSelection() {
        if (mVersionSpinner == null) return;
        int saved = new SharedPreferenceCore()
                .getInt(requireContext().getApplicationContext(), "VERSION");
        int index = LauncherVersion.normalizeIndex(saved, VERSIONS.length);
        mVersionSpinner.setSelection(index);
    }

    private void loadSliderValues() {
        Context app = requireContext().getApplicationContext();
        int fps = new SharedPreferenceCore().getInt(app, "FPS_LIMIT");
        if (mFPSSeekBar != null) mFPSSeekBar.setProgress(fpsProgressFor(fps));
        if (mFPSText != null) mFPSText.setText(String.valueOf(fps));

        int messages = new SharedPreferenceCore().getInt(app, "MESSAGE_COUNT");
        if (mMessagesSeekBar != null) mMessagesSeekBar.setProgress(messageProgressFor(messages));
        if (mMessagesText != null) mMessagesText.setText(String.valueOf(messages));
    }

    // ------------------------------------------------------------- ini access

    /** settings.ini path, or null when external storage is unavailable. */
    @Nullable
    private File settingsIni() {
        Context context = getContext();
        if (context == null) return null;
        File root = context.getExternalFilesDir(null);
        if (root == null) return null;
        return new File(root, "SAMP/settings.ini");
    }

    /**
     * Reads the stored client name. Returns null when the file cannot be read, so
     * the caller decides on the default instead of this method inventing one.
     */
    @Nullable
    private String readNicknameFromIni() {
        File file = settingsIni();
        if (file == null || !file.isFile()) return null;
        try {
            Wini ini = new Wini(file);
            String name = ini.get("client", "name");
            mWini = ini;
            return name;
        } catch (Exception ignored) {
            // A half written ini is not fatal: the field keeps its default and the
            // user can type a new name.
            mWini = null;
            return null;
        }
    }

    /**
     * Writes one key into settings.ini.
     *
     * The handle is opened lazily and closed on destroy, so a toggle flipped while
     * the fragment is alive cannot leak a writer, and a write arriving after
     * onDestroyView() is ignored because the view is gone.
     */
    private void writeIni(String section, String key, Object value) {
        if (!viewReady) return;

        File file = settingsIni();
        if (file == null || !file.isFile()) return;

        try {
            if (mWini == null) mWini = new Wini(file);
            mWini.put(section, key, value);
            mWini.store();
        } catch (IOException | RuntimeException error) {
            // Not fatal: the preference copy is already saved, and the next launch
            // rewrites the ini from preferences where applicable.
            mWini = null;
            if (getContext() != null) {
                com.hopepride.samp.diagnostics.Diagnostics.error("SETTINGS_INI_WRITE_FAILED", error);
            }
        }
    }

    // --------------------------------------------------------------- helpers

    private static int messageCountFor(int progress) {
        switch (progress) {
            case 0: return 6;
            case 1: return 9;
            case 2: return 12;
            case 3: return 15;
            default: return 9;
        }
    }

    private static int messageProgressFor(int messages) {
        switch (messages) {
            case 6: return 0;
            case 9: return 1;
            case 12: return 2;
            case 15: return 3;
            default: return 1;
        }
    }

    private static int fpsLimitFor(int progress) {
        switch (progress) {
            case 0: return 30;
            case 1: return 60;
            case 2: return 90;
            case 3: return 120;
            default: return 60;
        }
    }

    private static int fpsProgressFor(int limit) {
        switch (limit) {
            case 30: return 0;
            case 60: return 1;
            case 90: return 2;
            case 120: return 3;
            default: return 1;
        }
    }

    /**
     * Wires a view to an Activity. A null target is accepted: the row is then
     * skipped rather than crashing on a layout that does not define it.
     */
    private void wire(View root, int id, @Nullable Class<?> target) {
        View button = root.findViewById(id);
        if (button == null) return;
        MotionKit.attachPressFeedback(button);
        if (target == null) return;
        button.setOnClickListener(v -> {
            if (!viewReady) return;
            Context context = getContext();
            if (context == null) return;
            startActivity(new Intent(context, target));
        });
    }
}
