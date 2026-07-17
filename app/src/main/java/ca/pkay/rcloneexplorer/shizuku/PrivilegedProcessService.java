package ca.pkay.rcloneexplorer.shizuku;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.system.Os;

import androidx.annotation.Keep;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shizuku UserService that owns rclone child processes under the shell or root UID.
 * Configuration is delivered over Binder and stored in a private per-process directory.
 */
public final class PrivilegedProcessService extends IPrivilegedProcessService.Stub {

    private static final long COMPLETED_PROCESS_RETENTION_MS = 5 * 60 * 1000L;
    private static final File RUNTIME_PARENT = new File("/data/local/tmp");

    private final AtomicInteger nextProcessId = new AtomicInteger(1);
    private final Map<Integer, ProcessRecord> processes = new ConcurrentHashMap<>();
    private final String runtimePrefix;

    /** Required by Shizuku API 10-12. */
    public PrivilegedProcessService() {
        this.runtimePrefix = "roundsync";
    }

    /** Shizuku API 13 supplies a package context to UserService constructors. */
    @Keep
    public PrivilegedProcessService(Context context) {
        this.runtimePrefix = "roundsync-" + sanitize(context.getPackageName());
    }

    @Override
    public int start(
            String[] command,
            String[] environment,
            byte[] configuration,
            String localConfigurationPath,
            String localCachePath) throws RemoteException {
        pruneCompletedProcesses();
        int processId = nextProcessId.getAndIncrement();
        File runtimeDirectory = new File(
                RUNTIME_PARENT,
                runtimePrefix + "-" + android.os.Process.myUid() + "-" + processId
                        + "-" + UUID.randomUUID().toString());
        File cacheDirectory = new File(runtimeDirectory, "cache");
        File configurationFile = new File(runtimeDirectory, "rclone.conf");

        try {
            prepareRuntime(runtimeDirectory, cacheDirectory, configurationFile, configuration);
            PrivilegedCommandRewriter.RewrittenCommand rewritten = PrivilegedCommandRewriter.rewrite(
                    command,
                    environment,
                    localConfigurationPath,
                    localCachePath,
                    configurationFile.getAbsolutePath(),
                    cacheDirectory.getAbsolutePath());

            Process process = Runtime.getRuntime().exec(
                    rewritten.getCommand(),
                    rewritten.getEnvironment(),
                    runtimeDirectory);
            try {
                ProcessRecord record = new ProcessRecord(processId, process, runtimeDirectory);
                processes.put(processId, record);
                record.startBridges();
                return processId;
            } catch (IOException exception) {
                process.destroy();
                throw exception;
            }
        } catch (IOException | RuntimeException exception) {
            deleteRecursively(runtimeDirectory);
            throw remoteFailure("Unable to start privileged rclone process", exception);
        }
    }

    @Override
    public ParcelFileDescriptor takeStdin(int processId) throws RemoteException {
        return requireProcess(processId).takeStdin();
    }

    @Override
    public ParcelFileDescriptor takeStdout(int processId) throws RemoteException {
        return requireProcess(processId).takeStdout();
    }

    @Override
    public ParcelFileDescriptor takeStderr(int processId) throws RemoteException {
        return requireProcess(processId).takeStderr();
    }

    @Override
    public boolean isAlive(int processId) throws RemoteException {
        return requireProcess(processId).isAlive();
    }

    @Override
    public int exitValue(int processId) throws RemoteException {
        ProcessRecord record = requireProcess(processId);
        if (record.isAlive()) {
            return Integer.MIN_VALUE;
        }
        return record.process.exitValue();
    }

    @Override
    public void terminate(int processId, boolean forcibly) throws RemoteException {
        ProcessRecord record = requireProcess(processId);
        // Android's Process.destroy() terminates the native child. This is also safe below API 26.
        record.process.destroy();
    }

    @Override
    public void destroy() {
        for (ProcessRecord record : processes.values()) {
            record.process.destroy();
            record.closeClientDescriptors();
            deleteRecursively(record.runtimeDirectory);
        }
        processes.clear();
        System.exit(0);
    }

    private static void prepareRuntime(
            File runtimeDirectory,
            File cacheDirectory,
            File configurationFile,
            byte[] configuration) throws IOException {
        if ((!runtimeDirectory.mkdirs() && !runtimeDirectory.isDirectory())
                || (!cacheDirectory.mkdirs() && !cacheDirectory.isDirectory())) {
            throw new IOException("Cannot create privileged runtime directory");
        }
        try {
            Os.chmod(runtimeDirectory.getAbsolutePath(), 0700);
            Os.chmod(cacheDirectory.getAbsolutePath(), 0700);
        } catch (Exception exception) {
            throw new IOException("Cannot protect privileged runtime directory", exception);
        }

        byte[] safeConfiguration = configuration == null ? new byte[0] : configuration;
        try (FileOutputStream output = new FileOutputStream(configurationFile, false)) {
            output.write(safeConfiguration);
            output.getFD().sync();
        }
        try {
            Os.chmod(configurationFile.getAbsolutePath(), 0600);
        } catch (Exception exception) {
            throw new IOException("Cannot protect privileged rclone configuration", exception);
        }
    }

    private ProcessRecord requireProcess(int processId) throws RemoteException {
        ProcessRecord record = processes.get(processId);
        if (record == null) {
            throw new RemoteException("Unknown privileged process: " + processId);
        }
        return record;
    }

    private void pruneCompletedProcesses() {
        long cutoff = System.currentTimeMillis() - COMPLETED_PROCESS_RETENTION_MS;
        for (Map.Entry<Integer, ProcessRecord> entry : processes.entrySet()) {
            ProcessRecord record = entry.getValue();
            if (record.completedAt > 0 && record.completedAt < cutoff
                    && processes.remove(entry.getKey(), record)) {
                record.closeClientDescriptors();
            }
        }
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static RemoteException remoteFailure(String message, Throwable cause) {
        RemoteException exception = new RemoteException(message + ": " + cause.getMessage());
        exception.initCause(cause);
        return exception;
    }

    private static void bridge(InputStream source, OutputStream destination) {
        try (InputStream input = source; OutputStream output = destination) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                output.flush();
            }
        } catch (IOException ignored) {
            // A closed pipe is expected when callers cancel or destroy a process.
        }
    }

    private static void deleteRecursively(File target) {
        if (target == null || !target.exists()) {
            return;
        }
        File[] children = target.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        // Runtime paths are generated below /data/local/tmp and never supplied by callers.
        target.delete();
    }

    private static final class ProcessRecord {
        private final int processId;
        private final Process process;
        private final File runtimeDirectory;
        private final ParcelFileDescriptor serviceStdin;
        private final ParcelFileDescriptor serviceStdout;
        private final ParcelFileDescriptor serviceStderr;
        private ParcelFileDescriptor clientStdin;
        private ParcelFileDescriptor clientStdout;
        private ParcelFileDescriptor clientStderr;
        private volatile long completedAt;

        private ProcessRecord(int processId, Process process, File runtimeDirectory) throws IOException {
            this.processId = processId;
            this.process = process;
            this.runtimeDirectory = runtimeDirectory;

            ParcelFileDescriptor[] stdinPipe = ParcelFileDescriptor.createPipe();
            serviceStdin = stdinPipe[0];
            clientStdin = stdinPipe[1];

            ParcelFileDescriptor[] stdoutPipe = ParcelFileDescriptor.createPipe();
            clientStdout = stdoutPipe[0];
            serviceStdout = stdoutPipe[1];

            ParcelFileDescriptor[] stderrPipe = ParcelFileDescriptor.createPipe();
            clientStderr = stderrPipe[0];
            serviceStderr = stderrPipe[1];
        }

        private void startBridges() {
            startThread("stdin", () -> bridge(
                    new ParcelFileDescriptor.AutoCloseInputStream(serviceStdin),
                    process.getOutputStream()));
            startThread("stdout", () -> bridge(
                    process.getInputStream(),
                    new ParcelFileDescriptor.AutoCloseOutputStream(serviceStdout)));
            startThread("stderr", () -> bridge(
                    process.getErrorStream(),
                    new ParcelFileDescriptor.AutoCloseOutputStream(serviceStderr)));
            startThread("waiter", () -> {
                try {
                    process.waitFor();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    process.destroy();
                } finally {
                    completedAt = System.currentTimeMillis();
                    deleteRecursively(runtimeDirectory);
                }
            });
        }

        private void startThread(String role, Runnable runnable) {
            Thread thread = new Thread(runnable, "roundsync-shizuku-" + processId + "-" + role);
            thread.setDaemon(true);
            thread.start();
        }

        private synchronized ParcelFileDescriptor takeStdin() throws RemoteException {
            ParcelFileDescriptor result = duplicateDescriptor(clientStdin, "stdin");
            closeQuietly(clientStdin);
            clientStdin = null;
            return result;
        }

        private synchronized ParcelFileDescriptor takeStdout() throws RemoteException {
            ParcelFileDescriptor result = duplicateDescriptor(clientStdout, "stdout");
            closeQuietly(clientStdout);
            clientStdout = null;
            return result;
        }

        private synchronized ParcelFileDescriptor takeStderr() throws RemoteException {
            ParcelFileDescriptor result = duplicateDescriptor(clientStderr, "stderr");
            closeQuietly(clientStderr);
            clientStderr = null;
            return result;
        }

        private ParcelFileDescriptor duplicateDescriptor(ParcelFileDescriptor descriptor, String name)
                throws RemoteException {
            if (descriptor == null) {
                throw new RemoteException(name + " has already been taken");
            }
            try {
                return ParcelFileDescriptor.dup(descriptor.getFileDescriptor());
            } catch (IOException exception) {
                throw remoteFailure("Cannot transfer " + name, exception);
            }
        }

        private boolean isAlive() {
            try {
                process.exitValue();
                return false;
            } catch (IllegalThreadStateException ignored) {
                return true;
            }
        }

        private synchronized void closeClientDescriptors() {
            closeQuietly(clientStdin);
            closeQuietly(clientStdout);
            closeQuietly(clientStderr);
            clientStdin = null;
            clientStdout = null;
            clientStderr = null;
        }

        private static void closeQuietly(ParcelFileDescriptor descriptor) {
            if (descriptor == null) {
                return;
            }
            try {
                descriptor.close();
            } catch (IOException ignored) {
                // Best-effort cleanup.
            }
        }
    }
}
