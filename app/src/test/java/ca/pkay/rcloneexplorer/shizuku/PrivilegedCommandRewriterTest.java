package ca.pkay.rcloneexplorer.shizuku;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PrivilegedCommandRewriterTest {

    @Test
    public void rewritesOnlyConfigurationCacheAndEnvironmentValues() {
        String[] command = {
                "/data/app/lib/librclone.so",
                "--config", "/data/user/0/app/files/rclone.conf",
                "--cache-db-path", "/data/user/0/app/cache",
                "copy", "remote:file", "/storage/emulated/0/Download/file"
        };
        String[] environment = {
                "TMPDIR=/data/user/0/app/cache",
                "RCLONE_LOCAL_NO_SET_MODTIME=true"
        };

        PrivilegedCommandRewriter.RewrittenCommand rewritten =
                PrivilegedCommandRewriter.rewrite(
                        command,
                        environment,
                        "/data/user/0/app/files/rclone.conf",
                        "/data/user/0/app/cache",
                        "/data/local/tmp/roundsync/rclone.conf",
                        "/data/local/tmp/roundsync/cache");

        assertArrayEquals(new String[]{
                        "/data/app/lib/librclone.so",
                        "--config", "/data/local/tmp/roundsync/rclone.conf",
                        "--cache-db-path", "/data/local/tmp/roundsync/cache",
                        "copy", "remote:file", "/storage/emulated/0/Download/file"
                },
                rewritten.getCommand());
        assertArrayEquals(new String[]{
                        "TMPDIR=/data/local/tmp/roundsync/cache",
                        "RCLONE_LOCAL_NO_SET_MODTIME=true"
                },
                rewritten.getEnvironment());

        assertArrayEquals(command, new String[]{
                "/data/app/lib/librclone.so",
                "--config", "/data/user/0/app/files/rclone.conf",
                "--cache-db-path", "/data/user/0/app/cache",
                "copy", "remote:file", "/storage/emulated/0/Download/file"
        });
    }

    @Test
    public void keepsReadOnlyConfigurationCommandsPrivileged() {
        assertFalse(ShizukuProcessManager.isConfigurationMutation(
                new String[]{"rclone", "--config", "file", "config", "dump"}));
        assertFalse(ShizukuProcessManager.isConfigurationMutation(
                new String[]{"rclone", "config", "providers"}));
    }

    @Test
    public void detectsConfigurationMutationsThatMustStayInAppProcess() {
        assertTrue(ShizukuProcessManager.isConfigurationMutation(
                new String[]{"rclone", "config", "delete", "remote"}));
        assertTrue(ShizukuProcessManager.isConfigurationMutation(
                new String[]{"rclone", "config", "reconnect", "remote"}));
        assertTrue(ShizukuProcessManager.isConfigurationMutation(
                new String[]{"rclone", "config"}));
    }
}
