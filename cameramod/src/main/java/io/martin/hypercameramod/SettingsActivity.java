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
    private boolean hideLauncherChecked = false;

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

        // Load current hide launcher state
        android.content.SharedPreferences prefs = getSharedPreferences("hypercameramod_prefs", MODE_PRIVATE);
        hideLauncherChecked = prefs.getBoolean("hide_launcher_enabled", false);

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
        MenuItem hideItem = menu.findItem(R.id.menu_hide_launcher);
        if (hideItem != null) {
            hideItem.setChecked(hideLauncherChecked);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_force_stop) {
            forceStopCamera();
            return true;
        } else if (id == R.id.menu_hide_launcher) {
            hideLauncherChecked = !item.isChecked();
            item.setChecked(hideLauncherChecked);
            toggleLauncherIcon(hideLauncherChecked);
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

    private void toggleLauncherIcon(boolean hide) {
        // Toggle the LauncherAlias (activity-alias) instead of SettingsActivity
        // This keeps SettingsActivity accessible for Vector/LSPosed
        ComponentName componentName = new ComponentName(this, "io.martin.hypercameramod.LauncherAlias");
        PackageManager pm = getPackageManager();
        int newState = hide ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

        pm.setComponentEnabledSetting(componentName, newState, PackageManager.DONT_KILL_APP);

        // Save preference
        android.content.SharedPreferences prefs = getSharedPreferences("hypercameramod_prefs", MODE_PRIVATE);
        prefs.edit().putBoolean("hide_launcher_enabled", hide).apply();

        Toast.makeText(this,
                hide ? "App icon hidden. Open from Vector/LSPosed module list."
                : "App icon restored.",
                Toast.LENGTH_SHORT).show();
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);
        }
    }
}