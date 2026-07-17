package ca.pkay.rcloneexplorer.shizuku;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

/** java.lang.Process facade backed by a process running inside a Shizuku UserService. */
final class ShizukuRemoteProcess extends Process {

    private static final long POLL_INTERVAL_MS = 50L;

    private final IPrivilegedProcessService service;
    private final int processId;
    private final OutputStream outputStream;
    private final InputStream inputStream;
    private final InputStream errorStream;

    ShizukuRemoteProcess(
            IPrivilegedProcessService service,
            int processId,
            ParcelFileDescriptor stdin,
            ParcelFileDescriptor stdout,
            ParcelFileDescriptor stderr) {
        this.service = service;
        this.processId = processId;
        this.outputStream = new ParcelFileDescriptor.AutoCloseOutputStream(stdin);
        this.inputStream = new ParcelFileDescriptor.AutoCloseInputStream(stdout);
        this.errorStream = new ParcelFileDescriptor.AutoCloseInputStream(stderr);
    }

    @Override
    public OutputStream getOutputStream() {
        return outputStream;
    }

    @Override
    public InputStream getInputStream() {
        return inputStream;
    }

    @Override
    public InputStream getErrorStream() {
        return errorStream;
    }

    @Override
    public int waitFor() throws InterruptedException {
        while (isAlive()) {
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return exitValue();
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        long remainingNanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + remainingNanos;
        while (isAlive()) {
            if (remainingNanos <= 0) {
                return false;
            }
            long sleepMillis = Math.min(POLL_INTERVAL_MS,
                    Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            Thread.sleep(sleepMillis);
            remainingNanos = deadline - System.nanoTime();
        }
        return true;
    }

    @Override
    public int exitValue() {
        try {
            int result = service.exitValue(processId);
            if (result == Integer.MIN_VALUE) {
                throw new IllegalThreadStateException("Privileged process is still running");
            }
            return result;
        } catch (RemoteException exception) {
            throw remoteFailure(exception);
        }
    }

    @Override
    public void destroy() {
        terminate(false);
    }

    @Override
    public Process destroyForcibly() {
        terminate(true);
        return this;
    }

    @Override
    public boolean isAlive() {
        try {
            return service.isAlive(processId);
        } catch (RemoteException exception) {
            return false;
        }
    }

    private void terminate(boolean forcibly) {
        try {
            service.terminate(processId, forcibly);
        } catch (RemoteException exception) {
            throw remoteFailure(exception);
        }
    }

    private static IllegalStateException remoteFailure(RemoteException exception) {
        return new IllegalStateException("Shizuku process service is unavailable", exception);
    }
}
