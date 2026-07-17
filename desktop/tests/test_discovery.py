import json
import unittest

from roundsync_pc.discovery import (
    DISCOVERY_RESPONSE_PREFIX,
    DiscoveryProtocolError,
    parse_response,
)


class DiscoveryProtocolTest(unittest.TestCase):
    def test_parses_valid_response_and_uses_udp_source_address(self) -> None:
        document = {
            "service": "roundsync-webdav",
            "protocol_version": 1,
            "port": 8080,
            "device": "Samsung SM-S901B",
            "app_version": "2.5.7",
            "authentication_required": True,
            "address": "203.0.113.99",
        }
        payload = (DISCOVERY_RESPONSE_PREFIX + json.dumps(document)).encode()

        device = parse_response(payload, "192.168.1.25")

        self.assertEqual("192.168.1.25", device.address)
        self.assertEqual(8080, device.port)
        self.assertEqual("Samsung SM-S901B", device.name)
        self.assertTrue(device.authentication_required)
        self.assertEqual("http://192.168.1.25:8080/", device.endpoint)

    def test_rejects_unknown_protocol(self) -> None:
        with self.assertRaises(DiscoveryProtocolError):
            parse_response(b"OTHER/1 {}", "192.168.1.25")

    def test_rejects_boolean_as_port(self) -> None:
        payload = (
            DISCOVERY_RESPONSE_PREFIX
            + '{"service":"roundsync-webdav","protocol_version":1,"port":true}'
        ).encode()
        with self.assertRaises(DiscoveryProtocolError):
            parse_response(payload, "192.168.1.25")


if __name__ == "__main__":
    unittest.main()
