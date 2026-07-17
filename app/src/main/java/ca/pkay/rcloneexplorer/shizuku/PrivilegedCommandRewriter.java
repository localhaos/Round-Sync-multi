package ca.pkay.rcloneexplorer.shizuku;

import java.util.Arrays;

/** Pure command transformation shared by the app process and the Shizuku UserService. */
public final class PrivilegedCommandRewriter {

    private PrivilegedCommandRewriter() {
    }

    public static RewrittenCommand rewrite(
            String[] command,
            String[] environment,
            String localConfigurationPath,
            String localCachePath,
            String privilegedConfigurationPath,
            String privilegedCachePath) {
        if (command == null || command.length == 0) {
            throw new IllegalArgumentException("command must not be empty");
        }

        String[] rewrittenCommand = Arrays.copyOf(command, command.length);
        for (int index = 0; index < rewrittenCommand.length; index++) {
            if (localConfigurationPath.equals(rewrittenCommand[index])) {
                rewrittenCommand[index] = privilegedConfigurationPath;
            } else if (localCachePath.equals(rewrittenCommand[index])) {
                rewrittenCommand[index] = privilegedCachePath;
            }
        }

        String[] sourceEnvironment = environment == null ? new String[0] : environment;
        String[] rewrittenEnvironment = Arrays.copyOf(sourceEnvironment, sourceEnvironment.length);
        for (int index = 0; index < rewrittenEnvironment.length; index++) {
            int separator = rewrittenEnvironment[index].indexOf('=');
            if (separator < 0) {
                continue;
            }
            String name = rewrittenEnvironment[index].substring(0, separator + 1);
            String value = rewrittenEnvironment[index].substring(separator + 1);
            if (localConfigurationPath.equals(value)) {
                rewrittenEnvironment[index] = name + privilegedConfigurationPath;
            } else if (localCachePath.equals(value)) {
                rewrittenEnvironment[index] = name + privilegedCachePath;
            }
        }

        return new RewrittenCommand(rewrittenCommand, rewrittenEnvironment);
    }

    public static final class RewrittenCommand {
        private final String[] command;
        private final String[] environment;

        private RewrittenCommand(String[] command, String[] environment) {
            this.command = command;
            this.environment = environment;
        }

        public String[] getCommand() {
            return command;
        }

        public String[] getEnvironment() {
            return environment;
        }
    }
}
