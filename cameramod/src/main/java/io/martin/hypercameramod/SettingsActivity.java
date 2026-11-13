package io.martin.hypercameramod;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.PreferenceFragmentCompat;

public class SettingsActivity extends AppCompatActivity {

    private static final String CAMERA_PACKAGE = "com.android.camera";
    private static final String LAUNCHER_ALIAS = "io.martin.hypercameramod.LauncherAlias";

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

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings_container, new SettingsFragment())
                .commit();
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
        try {
            // Try to kill the camera app process without root
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                am.killBackgroundProcesses(CAMERA_PACKAGE);
            }
            // Also try to force-stop via package manager (requires permission)
            getPackageManager().setApplicationEnabledSetting(CAMERA_PACKAGE,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0);
            getPackageManager().setApplicationEnabledSetting(CAMERA_PACKAGE,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0);
            Toast.makeText(this, R.string.menu_force_stop_camera + " - done", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not force stop Camera: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
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