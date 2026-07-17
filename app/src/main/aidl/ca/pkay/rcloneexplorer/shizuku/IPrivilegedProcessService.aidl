package ca.pkay.rcloneexplorer.shizuku;

import android.os.ParcelFileDescriptor;

interface IPrivilegedProcessService {
    // Transaction reserved by Shizuku for terminating a UserService.
    void destroy() = 16777114;

    int start(in String[] command, in String[] environment, in byte[] configuration,
            String localConfigurationPath, String localCachePath) = 1;
    ParcelFileDescriptor takeStdin(int processId) = 2;
    ParcelFileDescriptor takeStdout(int processId) = 3;
    ParcelFileDescriptor takeStderr(int processId) = 4;
    boolean isAlive(int processId) = 5;
    int exitValue(int processId) = 6;
    void terminate(int processId, boolean forcibly) = 7;
}
