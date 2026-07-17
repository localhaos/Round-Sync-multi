package ca.pkay.rcloneexplorer.Services;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LanDiscoveryResponderTest {

    @Test
    public void responseContainsOnlyPublicConnectionMetadata() {
        LanDiscoveryResponder responder = new LanDiscoveryResponder(
                8080,
                "Test \"Phone\"",
                "2.5.7",
                true);

        String response = responder.buildResponse();

        assertTrue(response.startsWith(LanDiscoveryResponder.DISCOVERY_RESPONSE_PREFIX));
        assertTrue(response.contains("\"port\":8080"));
        assertTrue(response.contains("\"device\":\"Test \\\"Phone\\\"\""));
        assertTrue(response.contains("\"authentication_required\":true"));
        assertFalse(response.contains("username"));
        assertFalse(response.contains("password"));
        assertFalse(response.contains("path"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidWebDavPort() {
        new LanDiscoveryResponder(0, "Phone", "2.5.7", false);
    }
}
