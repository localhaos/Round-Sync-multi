from __future__ import annotations

import json
import socket
import time
from dataclasses import dataclass


DISCOVERY_PORT = 21080
DISCOVERY_REQUEST = b"ROUNDSYNC_DISCOVER/1"
DISCOVERY_RESPONSE_PREFIX = "ROUNDSYNC/1 "
MAX_RESPONSE_SIZE = 4096


class DiscoveryProtocolError(ValueError):
    """Raised when a UDP response is not a valid Round Sync discovery packet."""


@dataclass(frozen=True, slots=True)
class DiscoveredDevice:
    address: str
    port: int
    name: str
    app_version: str
    authentication_required: bool

    @property
    def endpoint(self) -> str:
        return f"http://{self.address}:{self.port}/"

    @property
    def label(self) -> str:
        authentication = "logowanie" if self.authentication_required else "bez logowania"
        return f"{self.name} — {self.address}:{self.port} ({authentication})"


def parse_response(payload: bytes, source_address: str) -> DiscoveredDevice:
    if len(payload) > MAX_RESPONSE_SIZE:
        raise DiscoveryProtocolError("Discovery response is too large")

    try:
        message = payload.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise DiscoveryProtocolError("Discovery response is not UTF-8") from error

    if not message.startswith(DISCOVERY_RESPONSE_PREFIX):
        raise DiscoveryProtocolError("Unknown discovery protocol")

    try:
        document = json.loads(message[len(DISCOVERY_RESPONSE_PREFIX) :])
    except json.JSONDecodeError as error:
        raise DiscoveryProtocolError("Invalid discovery JSON") from error

    if not isinstance(document, dict):
        raise DiscoveryProtocolError("Discovery payload must be an object")
    if document.get("service") != "roundsync-webdav":
        raise DiscoveryProtocolError("Unsupported service")
    if document.get("protocol_version") != 1:
        raise DiscoveryProtocolError("Unsupported discovery version")

    port = document.get("port")
    if not isinstance(port, int) or isinstance(port, bool) or not 1 <= port <= 65535:
        raise DiscoveryProtocolError("Invalid WebDAV port")

    name = document.get("device", "Android")
    version = document.get("app_version", "unknown")
    authentication_required = document.get("authentication_required", False)
    if not isinstance(name, str) or not name.strip() or len(name) > 128:
        raise DiscoveryProtocolError("Invalid device name")
    if not isinstance(version, str) or len(version) > 64:
        raise DiscoveryProtocolError("Invalid app version")
    if not isinstance(authentication_required, bool):
        raise DiscoveryProtocolError("Invalid authentication flag")

    # The sender address is authoritative. Never trust an address from JSON.
    return DiscoveredDevice(
        address=source_address,
        port=port,
        name=name.strip(),
        app_version=version,
        authentication_required=authentication_required,
    )


class DiscoveryClient:
    def __init__(self, timeout: float = 2.0) -> None:
        if timeout <= 0:
            raise ValueError("timeout must be positive")
        self.timeout = timeout

    def discover(self) -> list[DiscoveredDevice]:
        devices: dict[tuple[str, int], DiscoveredDevice] = {}
        deadline = time.monotonic() + self.timeout

        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP) as client:
            client.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            client.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            client.bind(("", 0))
            client.settimeout(min(0.25, self.timeout))

            for broadcast_address in self._broadcast_addresses():
                try:
                    client.sendto(DISCOVERY_REQUEST, (broadcast_address, DISCOVERY_PORT))
                except OSError:
                    # One unavailable adapter must not disable discovery on the others.
                    continue

            while time.monotonic() < deadline:
                try:
                    payload, source = client.recvfrom(MAX_RESPONSE_SIZE + 1)
                except socket.timeout:
                    continue
                except OSError:
                    break

                try:
                    device = parse_response(payload, source[0])
                except DiscoveryProtocolError:
                    continue
                devices[(device.address, device.port)] = device

        return sorted(devices.values(), key=lambda item: (item.name.casefold(), item.address, item.port))

    @staticmethod
    def _broadcast_addresses() -> set[str]:
        addresses = {"255.255.255.255"}
        try:
            candidates = socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)
        except OSError:
            return addresses

        for candidate in candidates:
            address = candidate[4][0]
            octets = address.split(".")
            if len(octets) != 4 or address.startswith(("127.", "169.254.")):
                continue
            # Directed /24 broadcast is an additional compatibility path. The limited
            # broadcast above remains correct for networks using a different prefix.
            addresses.add(".".join((*octets[:3], "255")))
        return addresses
