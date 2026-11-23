package io.martin.hypercameramod;

import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public class SettingsActivity extends AppCompatActivity {

    private static final String CAMERA_PACKAGE = "com.android.camera";
    private static final String LAUNCHER_ALIAS = "io.martin.hypercameramod.LauncherAlias";

    // The camera process reads these through libxposed remote preferences.
    private static final String PREFS_GROUP = "hypercameramod";
    private static final String ORIGINAL_PORTRAIT_KEY = "original_portrait_enabled";
    private static final String LEICA_THEME_KEY = "leica_theme_enabled";
    private static final String ESSENTIAL_MODE_KEY = "essential_mode_enabled";
    private static final String OLD_LOOKS_ENTRIES_KEY = "looks_entries_enabled";
    private static final String OLD_LEICA_UI_KEY = "leica_ui_enabled";
    // One-shot marker: the standard-strip and Leica-Looks M3/M9 switches are now one setting.
    private static final String M3M9_MERGE_KEY = "m3m9_settings_merged_v1";
    // One-shot marker: older builds persisted Original portrait as on for every device.
    private static final String MIGRATION_KEY = "device_defaults_migrated_v2";
    private static final String[] SETTING_KEYS = {
            ESSENTIAL_MODE_KEY, LEICA_THEME_KEY, "m10r_enabled",
            "extra_filters_enabled", ORIGINAL_PORTRAIT_KEY
    };
    // Original portrait is only offered by default on phones whose camera hardware layer can
    // preview it (17 Ultra, 18 Pro Max); on the 14 Ultra it deadlocks the preview.
    private static final boolean[] SETTING_DEFAULTS = {
            true, false, false, true, DeviceSupport.originalPortraitDefault()
    };

    private SharedPreferences modulePrefs;
    private boolean prefListenerRegistered;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (prefs, key) -> {
                for (int i = 0; i < SETTING_KEYS.length; i++) {
                    if (SETTING_KEYS[i].equals(key)) {
                        pushPreference(key, prefs.getBoolean(key, SETTING_DEFAULTS[i]));
                        return;
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.settings_title);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        seedDeviceDefaults();
        connectModuleSettings();

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings_container, new SettingsFragment())
                .commit();
        }
    }

    // Write the device-dependent defaults once, so the settings screen shows the same value the
    // hooks use when the app has never been opened on this phone. Older builds defaulted Original
    // portrait on for every device; on the 14 Ultra that left the switch on even though the style
    // is opt-in there now, so reset it once and remember that the migration ran.
    private void seedDeviceDefaults() {
        SharedPreferences local = PreferenceManager.getDefaultSharedPreferences(this);
        if (!local.getBoolean(MIGRATION_KEY, false)) {
            if (!DeviceSupport.originalPortraitDefault()) {
                local.edit().putBoolean(ORIGINAL_PORTRAIT_KEY, false).apply();
            }
            local.edit().putBoolean(MIGRATION_KEY, true).apply();
        }
        if (!local.getBoolean(M3M9_MERGE_KEY, false)) {
            // The old builds had a separate switch per UI. Keep M3/M9 on if either was on.
            boolean merged = local.getBoolean(ESSENTIAL_MODE_KEY, true);
            if (local.contains(OLD_LOOKS_ENTRIES_KEY)) {
                merged |= local.getBoolean(OLD_LOOKS_ENTRIES_KEY, true);
            }
            local.edit()
                    .putBoolean(ESSENTIAL_MODE_KEY, merged)
                    .remove(OLD_LOOKS_ENTRIES_KEY)
                    .remove(OLD_LEICA_UI_KEY)
                    .putBoolean(M3M9_MERGE_KEY, true)
                    .apply();
        }
        if (!local.contains(ORIGINAL_PORTRAIT_KEY)) {
            local.edit()
                    .putBoolean(ORIGINAL_PORTRAIT_KEY, DeviceSupport.originalPortraitDefault())
                    .apply();
        }
        if (!local.contains(LEICA_THEME_KEY)) {
            local.edit()
                    .putBoolean(LEICA_THEME_KEY, "LCC".equals(readProp("ro.theme_customize")))
                    .apply();
        }
    }

    private static String readProp(String key) {
        try {
            Process process = new ProcessBuilder("getprop", key).redirectErrorStream(true).start();
            String value = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();
            return value;
        } catch (Throwable t) {
            return "";
        }
    }

    // The hooks run in the camera process and cannot read this app's private preferences, so
    // mirror the settings into the manager's remote preferences, which both sides can read.
    private void connectModuleSettings() {
        try {
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(XposedService service) {
                    try {
                        modulePrefs = service.getRemotePreferences(PREFS_GROUP);
                        pushAllPreferences();
                        if (!prefListenerRegistered) {
                            PreferenceManager.getDefaultSharedPreferences(SettingsActivity.this)
                                    .registerOnSharedPreferenceChangeListener(prefListener);
                            prefListenerRegistered = true;
                        }
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                public void onServiceDied(XposedService service) {
                    modulePrefs = null;
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void pushAllPreferences() {
        SharedPreferences local = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences prefs = modulePrefs;
        if (prefs == null) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        for (int i = 0; i < SETTING_KEYS.length; i++) {
            if (local.contains(SETTING_KEYS[i])) {
                editor.putBoolean(SETTING_KEYS[i], local.getBoolean(SETTING_KEYS[i], SETTING_DEFAULTS[i]));
            }
        }
        editor.apply();
    }

    private void pushPreference(String key, boolean value) {
        SharedPreferences prefs = modulePrefs;
        if (prefs != null) {
            prefs.edit().putBoolean(key, value).apply();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.settings_menu, menu);

        // Checkbox means "icon hidden": checked = launcher alias is disabled.
        MenuItem hideItem = menu.findItem(R.id.menu_hide_launcher);
        if (hideItem != null) {
            hideItem.setChecked(isLauncherIconHidden());
        }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem hideItem = menu.findItem(R.id.menu_hide_launcher);
        if (hideItem != null) {
            hideItem.setChecked(isLauncherIconHidden());
        }
        return super.onPrepareOptionsMenu(menu);
    }

    private boolean isLauncherIconHidden() {
        ComponentName componentName = new ComponentName(this, LAUNCHER_ALIAS);
        return getPackageManager().getComponentEnabledSetting(componentName)
                == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_force_stop) {
            forceStopCamera();
            return true;
        } else if (id == R.id.menu_hide_launcher) {
            toggleLauncherIcon();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void forceStopCamera() {
        // Normal apps cannot force-stop another package: killBackgroundProcesses() needs
        // KILL_BACKGROUND_PROCESSES and setApplicationEnabledSetting() is system-only. The
        // module itself also declares no permissions (so Android does not add a synthetic
        // "App info" icon when the launcher icon is hidden), so use root instead.
        Toast.makeText(this, "Stopping Camera…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean stopped = false;
            String reason = null;
            try {
                Process process = new ProcessBuilder("su", "-c", "am force-stop " + CAMERA_PACKAGE)
                        .redirectErrorStream(true)
                        .start();
                stopped = process.waitFor() == 0;
                if (!stopped) {
                    reason = "su exited with code " + process.exitValue();
                }
            } catch (Throwable t) {
                reason = t.getMessage();
            }
            final boolean ok = stopped;
            final String why = reason;
            runOnUiThread(() -> Toast.makeText(this,
                    ok ? "Camera stopped."
                            : "Could not stop Camera. Grant root to HyperCameraMod (Magisk) or stop it from App info."
                                    + (why == null ? "" : "\n" + why),
                    Toast.LENGTH_LONG).show());
        }).start();
    }

    private void toggleLauncherIcon() {
        // Toggle the LauncherAlias (activity-alias). SettingsActivity itself stays enabled,
        // so Vector/LSPosed can always open the module settings via CATEGORY_INFO.
        ComponentName componentName = new ComponentName(this, LAUNCHER_ALIAS);
        PackageManager pm = getPackageManager();

        // hidden is the current state; newState is its opposite.
        boolean hidden = pm.getComponentEnabledSetting(componentName)
                == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        int newState = hidden
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;

        pm.setComponentEnabledSetting(componentName, newState, PackageManager.DONT_KILL_APP);

        // Refresh the checkbox from the new state (checked = hidden).
        invalidateOptionsMenu();

        Toast.makeText(this,
                newState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        ? "Launcher icon hidden. Open settings from Vector/LSPosed module list."
                        : "Launcher icon restored.",
                Toast.LENGTH_SHORT).show();
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);
        }
    }
}