package ca.pkay.rcloneexplorer;

import android.app.Application;

import ca.pkay.rcloneexplorer.shizuku.ShizukuProcessManager;

/** Initializes process-wide integrations that must also work for background workers. */
public class RoundSyncApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        ShizukuProcessManager.initialize(this);
    }
}
