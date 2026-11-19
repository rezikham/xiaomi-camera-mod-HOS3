package io.martin.hypercameramod;

import android.app.Application;
import android.util.Log;

public class ModuleMain extends Application {

    private static final String TAG = "HyperCameraMod";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "HyperCameraMod module initialized (clean build - no camera hooks)");
    }
}
