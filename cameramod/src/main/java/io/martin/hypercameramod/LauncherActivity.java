package io.martin.hypercameramod;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Launcher entry point - can be disabled to hide app icon from launcher
 * without affecting SettingsActivity accessibility from Vector/LSPosed.
 */
public class LauncherActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Forward to SettingsActivity and finish
        startActivity(new Intent(this, SettingsActivity.class));
        finish();
    }
}