package ca.pkay.rcloneexplorer.Services;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import ca.pkay.rcloneexplorer.util.FLog;

/**
 * Announces an active WebDAV share to Round Sync desktop clients on the local network.
 *
 * <p>The discovery response intentionally contains no credentials or remote path. The source
 * address of the UDP response is authoritative; clients must ignore any address supplied in the
 * payload. Discovery is enabled only while the user has explicitly enabled remote WebDAV access.
 */
public final class LanDiscoveryResponder implements Closeable {

    static final String TAG = "LanDiscovery";
    public static final int DISCOVERY_PORT = 21080;
    public static final String DISCOVERY_REQUEST = "ROUNDSYNC_DISCOVER/1";
    public static final String DISCOVERY_RESPONSE_PREFIX = "ROUNDSYNC/1 ";

    private static final int MAX_PACKET_SIZE = 512;
    private static final int SOCKET_TIMEOUT_MS = 1000;

    private final int webDavPort;
    private final String deviceName;
    private final String appVersion;
    private final boolean authenticationRequired;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private DatagramSocket socket;
    private Thread worker;

    public LanDiscoveryResponder(
            int webDavPort,
            String deviceName,
            String appVersion,
            boolean authenticationRequired) {
        if (webDavPort < 1 || webDavPort > 65535) {
            throw new IllegalArgumentException("Invalid WebDAV port: " + webDavPort);
        }
        this.webDavPort = webDavPort;
        this.deviceName = deviceName == null ? "Android" : deviceName;
        this.appVersion = appVersion == null ? "unknown" : appVersion;
        this.authenticationRequired = authenticationRequired;
    }

    public synchronized void start() throws SocketException {
        if (running.get()) {
            return;
        }

        DatagramSocket discoverySocket = new DatagramSocket(null);
        try {
            discoverySocket.setReuseAddress(true);
            discoverySocket.setBroadcast(true);
            discoverySocket.setSoTimeout(SOCKET_TIMEOUT_MS);
            discoverySocket.bind(new InetSocketAddress(DISCOVERY_PORT));
        } catch (SocketException error) {
            discoverySocket.close();
            throw error;
        }

        socket = discoverySocket;
        running.set(true);
        worker = new Thread(this::listen, "roundsync-lan-discovery");
        worker.setDaemon(true);
        worker.start();
    }

    private void listen() {
        DatagramSocket activeSocket = socket;
        byte[] buffer = new byte[MAX_PACKET_SIZE];
        while (running.get()) {
            DatagramPacket request = new DatagramPacket(buffer, buffer.length);
            try {
                activeSocket.receive(request);
                if (!isDiscoveryRequest(request)) {
                    continue;
                }

                byte[] payload = buildResponse().getBytes(StandardCharsets.UTF_8);
                DatagramPacket response = new DatagramPacket(
                        payload,
                        payload.length,
                        request.getAddress(),
                        request.getPort());
                activeSocket.send(response);
            } catch (SocketTimeoutException ignored) {
                // Periodically re-check the running flag.
            } catch (IOException error) {
                if (running.get()) {
                    FLog.w(TAG, "LAN discovery failed: %s", error.getMessage());
                }
            } catch (RuntimeException error) {
                FLog.e(TAG, "Unexpected LAN discovery error", error);
            }
        }
    }

    private boolean isDiscoveryRequest(DatagramPacket packet) {
        String request = new String(
                packet.getData(),
                packet.getOffset(),
                packet.getLength(),
                StandardCharsets.US_ASCII);
        return DISCOVERY_REQUEST.equals(request);
    }

    String buildResponse() {
        return DISCOVERY_RESPONSE_PREFIX
                + "{\"service\":\"roundsync-webdav\""
                + ",\"protocol_version\":1"
                + ",\"port\":" + webDavPort
                + ",\"device\":\"" + jsonEscape(deviceName) + "\""
                + ",\"app_version\":\"" + jsonEscape(appVersion) + "\""
                + ",\"authentication_required\":" + authenticationRequired
                + "}";
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '\b':
                    escaped.append("\\b");
                    break;
                case '\f':
                    escaped.append("\\f");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
            }
        }
        return escaped.toString();
    }

    @Override
    public synchronized void close() {
        running.set(false);
        if (socket != null) {
            socket.close();
            socket = null;
        }
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }
}
