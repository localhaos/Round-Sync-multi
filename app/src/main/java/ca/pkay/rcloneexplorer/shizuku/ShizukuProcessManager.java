package ca.pkay.rcloneexplorer.shizuku;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import androidx.preference.PreferenceManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import ca.pkay.rcloneexplorer.BuildConfig;
import ca.pkay.rcloneexplorer.R;
import rikka.shizuku.Shizuku;

/** Coordinates Shizuku permission, UserService binding, and privileged rclone processes. */
public final class ShizukuProcessManager {

    public static final int PERMISSION_REQUEST_CODE = 52071;
    private static final int MAX_CONFIGURATION_BYTES = 512 * 1024;
    private static final long SERVICE_CONNECTION_TIMEOUT_SECONDS = 8L;
    private static final Set<String> READ_ONLY_CONFIG_ACTIONS = new HashSet<>(
            Arrays.asList("dump", "show", "providers"));

    private static final Object LOCK = new Object();
    private static volatile Context applicationContext;
    private static volatile IPrivilegedProcessService service;
    private static volatile boolean binding;
    private static volatile CountDownLatch connectionLatch = new CountDownLatch(1);
    private static Shizuku.UserServiceArgs userServiceArgs;

    private static final ServiceConnection SERVICE_CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (LOCK) {
                service = IPrivilegedProcessService.Stub.asInterface(binder);
                binding = false;
                connectionLatch.countDown();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (LOCK) {
                service = null;
                binding = false;
                connectionLatch = new CountDownLatch(1);
            }
        }
    };

    private ShizukuProcessManager() {
    }

    public enum State {
        UNAVAILABLE,
        UNSUPPORTED,
        PERMISSION_REQUIRED,
        CONNECTING,
        SHELL,
        ROOT
    }

    public static void initialize(Context context) {
        if (applicationContext != null) {
            return;
        }
        synchronized (LOCK) {
            if (applicationContext != null) {
                return;
            }
            applicationContext = context.getApplicationContext();
            userServiceArgs = new Shizuku.UserServiceArgs(new ComponentName(
                    applicationContext.getPackageName(),
                    PrivilegedProcessService.class.getName()))
                    .daemon(false)
                    .processNameSuffix("rclone")
                    .debuggable(BuildConfig.DEBUG)
                    .version(BuildConfig.VERSION_CODE);

            Shizuku.addBinderReceivedListenerSticky(ShizukuProcessManager::bindIfPossible);
            Shizuku.addBinderDeadListener(() -> {
                synchronized (LOCK) {
                    service = null;
                    binding = false;
                    connectionLatch = new CountDownLatch(1);
                }
            });
            Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> {
                if (requestCode == PERMISSION_REQUEST_CODE && grantResult == PERMISSION_GRANTED) {
                    bindIfPossible();
                }
            });
        }
    }

    public static State getState(Context context) {
        initialize(context);
        if (!Shizuku.pingBinder()) {
            return State.UNAVAILABLE;
        }
        try {
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 10) {
                return State.UNSUPPORTED;
            }
            if (Shizuku.checkSelfPermission() != PERMISSION_GRANTED) {
                return State.PERMISSION_REQUIRED;
            }
            if (binding && service == null) {
                return State.CONNECTING;
            }
            return Shizuku.getUid() == 0 ? State.ROOT : State.SHELL;
        } catch (RuntimeException exception) {
            return State.UNAVAILABLE;
        }
    }

    /** Returns true when the permission request was dispatched or permission is already granted. */
    public static boolean requestPermission(Context context) {
        initialize(context);
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
            return false;
        }
        try {
            if (Shizuku.checkSelfPermission() == PERMISSION_GRANTED) {
                bindIfPossible();
            } else {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE);
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public static void bindIfPossible() {
        Context context = applicationContext;
        if (context == null || !isEnabled(context) || !Shizuku.pingBinder()) {
            return;
        }
        try {
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 10
                    || Shizuku.checkSelfPermission() != PERMISSION_GRANTED) {
                return;
            }
            synchronized (LOCK) {
                if (service != null || binding) {
                    return;
                }
                binding = true;
                connectionLatch = new CountDownLatch(1);
                Shizuku.bindUserService(userServiceArgs, SERVICE_CONNECTION);
            }
        } catch (RuntimeException exception) {
            synchronized (LOCK) {
                binding = false;
                connectionLatch.countDown();
            }
        }
    }

    public static void unbind() {
        synchronized (LOCK) {
            if (userServiceArgs == null || (!binding && service == null)) {
                return;
            }
            try {
                Shizuku.unbindUserService(userServiceArgs, SERVICE_CONNECTION, true);
            } catch (RuntimeException ignored) {
                // The Shizuku binder may have stopped between the UI action and this call.
            } finally {
                service = null;
                binding = false;
                connectionLatch = new CountDownLatch(1);
            }
        }
    }

    public static boolean shouldUsePrivilegedProcess(Context context, String[] command) {
        return isEnabled(context) && !isConfigurationMutation(command);
    }

    public static Process startProcess(
            Context context,
            String[] command,
            String[] environment,
            String localConfigurationPath,
            String localCachePath) throws IOException {
        initialize(context);
        State state = getState(context);
        if (state == State.UNAVAILABLE) {
            throw new IOException("Shizuku is not running");
        }
        if (state == State.UNSUPPORTED) {
            throw new IOException("Shizuku 11 or newer is required");
        }
        if (state == State.PERMISSION_REQUIRED) {
            throw new IOException("Shizuku permission has not been granted");
        }

        boolean rootAvailable;
        try {
            rootAvailable = Shizuku.getUid() == 0;
        } catch (RuntimeException exception) {
            throw new IOException("Unable to determine Shizuku privilege level", exception);
        }
        boolean rootRequired = isRootRequired(context);
        if (rootRequired && !rootAvailable) {
            throw new IOException("Shizuku+ requires Sui or a root-started Shizuku service");
        }
        if (!rootAvailable) {
            rejectPrivateAppPaths(context, command, localConfigurationPath, localCachePath);
        }

        bindIfPossible();
        IPrivilegedProcessService activeService = awaitService();
        byte[] configuration = readConfiguration(new File(localConfigurationPath));
        try {
            int processId = activeService.start(
                    command,
                    environment == null ? new String[0] : environment,
                    configuration,
                    localConfigurationPath,
                    localCachePath);
            ParcelFileDescriptor stdin = null;
            ParcelFileDescriptor stdout = null;
            ParcelFileDescriptor stderr = null;
            try {
                stdin = activeService.takeStdin(processId);
                stdout = activeService.takeStdout(processId);
                stderr = activeService.takeStderr(processId);
                return new ShizukuRemoteProcess(activeService, processId, stdin, stdout, stderr);
            } catch (RemoteException | RuntimeException exception) {
                closeQuietly(stdin);
                closeQuietly(stdout);
                closeQuietly(stderr);
                try {
                    activeService.terminate(processId, true);
                } catch (RemoteException ignored) {
                    // Preserve the original stream setup error.
                }
                throw exception;
            }
        } catch (RemoteException | RuntimeException exception) {
            throw new IOException("Unable to start rclone through Shizuku", exception);
        }
    }

    private static IPrivilegedProcessService awaitService() throws IOException {
        IPrivilegedProcessService activeService = service;
        if (activeService != null) {
            return activeService;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IOException("Shizuku process service is still connecting; retry the operation");
        }
        CountDownLatch latch = connectionLatch;
        try {
            if (!latch.await(SERVICE_CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("Timed out while connecting to the Shizuku process service");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while connecting to Shizuku", exception);
        }
        activeService = service;
        if (activeService == null) {
            throw new IOException("Shizuku process service is unavailable");
        }
        return activeService;
    }

    private static byte[] readConfiguration(File configurationFile) throws IOException {
        if (!configurationFile.isFile()) {
            return new byte[0];
        }
        if (configurationFile.length() > MAX_CONFIGURATION_BYTES) {
            throw new IOException("rclone.conf is too large for the Shizuku process service");
        }
        try (FileInputStream input = new FileInputStream(configurationFile);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) configurationFile.length())) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            int total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_CONFIGURATION_BYTES) {
                    throw new IOException("rclone.conf is too large for the Shizuku process service");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static void rejectPrivateAppPaths(
            Context context,
            String[] command,
            String localConfigurationPath,
            String localCachePath) throws IOException {
        String dataDirectory = context.getApplicationInfo().dataDir;
        for (int index = 1; index < command.length; index++) {
            String argument = command[index];
            if (argument.equals(localConfigurationPath) || argument.equals(localCachePath)) {
                continue;
            }
            if (argument.equals(dataDirectory) || argument.startsWith(dataDirectory + File.separator)) {
                throw new IOException(
                        "Shizuku shell cannot access an app-private path; use Shizuku+ root mode");
            }
        }
    }

    static boolean isConfigurationMutation(String[] command) {
        for (int index = 0; index < command.length; index++) {
            if (!"config".equals(command[index])) {
                continue;
            }
            if (index + 1 >= command.length) {
                return true;
            }
            return !READ_ONLY_CONFIG_ACTIONS.contains(command[index + 1]);
        }
        return false;
    }

    private static boolean isEnabled(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        return preferences.getBoolean(context.getString(R.string.pref_key_shizuku_enabled), false);
    }

    private static boolean isRootRequired(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        return preferences.getBoolean(context.getString(R.string.pref_key_shizuku_root), false);
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        try {
            descriptor.close();
        } catch (IOException ignored) {
            // Best-effort cleanup after a failed remote process setup.
        }
    }
}
