from __future__ import annotations

import base64
import http.client
import os
import ssl
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO, Collection, Iterable, Sequence
from urllib.parse import quote, unquote, urlsplit


DAV_NAMESPACE = "DAV:"
PROPFIND_BODY = b"""<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:displayname />
    <d:resourcetype />
    <d:getcontentlength />
    <d:getlastmodified />
  </d:prop>
</d:propfind>
"""
MAX_METADATA_RESPONSE = 16 * 1024 * 1024


class WebDavError(RuntimeError):
    def __init__(self, status: int, reason: str, details: str = "") -> None:
        self.status = status
        self.reason = reason
        self.details = details
        message = f"WebDAV {status}: {reason}"
        if details:
            message += f" — {details}"
        super().__init__(message)


@dataclass(frozen=True, slots=True)
class WebDavEntry:
    name: str
    path: tuple[str, ...]
    is_directory: bool
    size: int | None = None
    modified: str = ""


class WebDavClient:
    def __init__(
        self,
        endpoint: str,
        username: str = "",
        password: str = "",
        timeout: float = 30.0,
    ) -> None:
        endpoint = endpoint.strip()
        if "://" not in endpoint:
            endpoint = "http://" + endpoint
        parsed = urlsplit(endpoint)
        if parsed.scheme not in {"http", "https"}:
            raise ValueError("Obsługiwane są wyłącznie adresy HTTP i HTTPS")
        if not parsed.hostname:
            raise ValueError("Adres serwera nie zawiera nazwy hosta")
        if parsed.query or parsed.fragment:
            raise ValueError("Adres serwera nie może zawierać query ani fragmentu")
        if timeout <= 0:
            raise ValueError("timeout must be positive")

        self.scheme = parsed.scheme
        self.host = parsed.hostname
        try:
            self.port = parsed.port or (443 if parsed.scheme == "https" else 80)
        except ValueError as error:
            raise ValueError("Nieprawidłowy port serwera") from error
        self.base_segments = self._normalize_path(tuple(
            unquote(segment) for segment in parsed.path.split("/") if segment
        ))
        self.username = username
        self.password = password
        self.timeout = timeout

    def list_directory(self, path: Sequence[str] = ()) -> list[WebDavEntry]:
        normalized_path = self._normalize_path(path)
        status, _, body = self._request(
            "PROPFIND",
            normalized_path,
            headers={"Depth": "1", "Content-Type": "application/xml; charset=utf-8"},
            body=PROPFIND_BODY,
            expected={207},
            directory=True,
        )
        if status != 207:
            raise WebDavError(status, "Expected Multi-Status")
        return self._parse_directory(body, normalized_path)

    def create_directory(self, path: Sequence[str]) -> None:
        normalized_path = self._normalize_path(path)
        if not normalized_path:
            raise ValueError("Nie można utworzyć katalogu głównego")
        self._request("MKCOL", normalized_path, expected={201}, directory=True)

    def delete(self, path: Sequence[str], *, is_directory: bool = False) -> None:
        normalized_path = self._normalize_path(path)
        if not normalized_path:
            raise ValueError("Usunięcie katalogu głównego jest zabronione")
        self._request(
            "DELETE",
            normalized_path,
            expected={200, 202, 204},
            directory=is_directory,
        )

    def upload(self, source: Path, destination: Sequence[str]) -> None:
        source = Path(source)
        if not source.is_file():
            raise FileNotFoundError(source)
        normalized_destination = self._normalize_path(destination)
        if not normalized_destination:
            raise ValueError("Docelowa nazwa pliku jest wymagana")
        with source.open("rb") as stream:
            self._request(
                "PUT",
                normalized_destination,
                headers={"Content-Type": "application/octet-stream"},
                stream=stream,
                stream_length=source.stat().st_size,
                expected={200, 201, 204},
            )

    def download(self, source: Sequence[str], destination: Path) -> None:
        normalized_source = self._normalize_path(source)
        if not normalized_source:
            raise ValueError("Źródłowy plik jest wymagany")
        destination = Path(destination)
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_name(destination.name + ".roundsync-part")
        try:
            with temporary.open("wb") as output:
                self._request(
                    "GET",
                    normalized_source,
                    expected={200},
                    output=output,
                )
            os.replace(temporary, destination)
        except Exception:
            temporary.unlink(missing_ok=True)
            raise

    def _request(
        self,
        method: str,
        path: Sequence[str],
        *,
        headers: dict[str, str] | None = None,
        body: bytes | None = None,
        stream: BinaryIO | None = None,
        stream_length: int | None = None,
        expected: Collection[int],
        directory: bool = False,
        output: BinaryIO | None = None,
    ) -> tuple[int, http.client.HTTPMessage, bytes]:
        if body is not None and stream is not None:
            raise ValueError("body and stream are mutually exclusive")
        target = self._target(path, directory=directory)
        connection = self._new_connection()
        request_headers = {
            "Accept": "*/*",
            "Connection": "close",
            "User-Agent": "RoundSync-PC/1.0",
        }
        request_headers.update(headers or {})
        if self.username or self.password:
            raw_credentials = f"{self.username}:{self.password}".encode("utf-8")
            token = base64.b64encode(raw_credentials).decode("ascii")
            request_headers["Authorization"] = f"Basic {token}"
        if body is not None:
            request_headers["Content-Length"] = str(len(body))
        elif stream is not None:
            if stream_length is None or stream_length < 0:
                raise ValueError("stream_length is required")
            request_headers["Content-Length"] = str(stream_length)

        try:
            connection.putrequest(method, target)
            for name, value in request_headers.items():
                connection.putheader(name, value)
            connection.endheaders()
            if body is not None:
                connection.send(body)
            elif stream is not None:
                while chunk := stream.read(1024 * 1024):
                    connection.send(chunk)

            response = connection.getresponse()
            if response.status not in expected:
                details = response.read(64 * 1024).decode("utf-8", errors="replace").strip()
                raise WebDavError(response.status, response.reason, details)

            if output is not None:
                while chunk := response.read(1024 * 1024):
                    output.write(chunk)
                response_body = b""
            else:
                response_body = response.read(MAX_METADATA_RESPONSE + 1)
                if len(response_body) > MAX_METADATA_RESPONSE:
                    raise WebDavError(response.status, response.reason, "Odpowiedź metadanych jest zbyt duża")
            return response.status, response.headers, response_body
        except WebDavError:
            raise
        except (OSError, http.client.HTTPException) as error:
            raise ConnectionError(f"Błąd połączenia z {self.host}:{self.port}: {error}") from error
        finally:
            connection.close()

    def _new_connection(self) -> http.client.HTTPConnection:
        if self.scheme == "https":
            return http.client.HTTPSConnection(
                self.host,
                self.port,
                timeout=self.timeout,
                context=ssl.create_default_context(),
            )
        return http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)

    def _target(self, path: Sequence[str], *, directory: bool = False) -> str:
        segments = (*self.base_segments, *self._normalize_path(path))
        target = "/" + "/".join(quote(segment, safe="") for segment in segments)
        if directory and not target.endswith("/"):
            target += "/"
        return target or "/"

    @staticmethod
    def _normalize_path(path: Sequence[str]) -> tuple[str, ...]:
        normalized: list[str] = []
        for segment in path:
            if not isinstance(segment, str):
                raise TypeError("Każdy segment ścieżki musi być tekstem")
            if not segment or segment in {".", ".."} or "/" in segment or "\x00" in segment:
                raise ValueError(f"Nieprawidłowy segment ścieżki: {segment!r}")
            normalized.append(segment)
        return tuple(normalized)

    def _parse_directory(
        self,
        payload: bytes,
        requested_path: tuple[str, ...],
    ) -> list[WebDavEntry]:
        try:
            root = ElementTree.fromstring(payload)
        except ElementTree.ParseError as error:
            raise WebDavError(207, "Nieprawidłowa odpowiedź XML", str(error)) from error

        entries: list[WebDavEntry] = []
        for response in root.findall(f"{{{DAV_NAMESPACE}}}response"):
            href_node = response.find(f"{{{DAV_NAMESPACE}}}href")
            if href_node is None or not href_node.text:
                continue
            relative_path = self._relative_path(href_node.text)
            if relative_path is None or relative_path == requested_path:
                continue

            prop = self._successful_prop(response)
            if prop is None:
                continue
            resource_type = prop.find(f"{{{DAV_NAMESPACE}}}resourcetype")
            is_directory = resource_type is not None and resource_type.find(
                f"{{{DAV_NAMESPACE}}}collection"
            ) is not None
            display_name_node = prop.find(f"{{{DAV_NAMESPACE}}}displayname")
            name = (
                display_name_node.text.strip()
                if display_name_node is not None and display_name_node.text and display_name_node.text.strip()
                else relative_path[-1]
            )
            length_node = prop.find(f"{{{DAV_NAMESPACE}}}getcontentlength")
            try:
                size = int(length_node.text) if length_node is not None and length_node.text else None
            except ValueError:
                size = None
            modified_node = prop.find(f"{{{DAV_NAMESPACE}}}getlastmodified")
            modified = modified_node.text.strip() if modified_node is not None and modified_node.text else ""
            entries.append(WebDavEntry(name, relative_path, is_directory, size, modified))

        entries.sort(key=lambda item: (not item.is_directory, item.name.casefold()))
        return entries

    def _relative_path(self, href: str) -> tuple[str, ...] | None:
        parsed = urlsplit(href)
        absolute_segments = tuple(
            unquote(segment) for segment in parsed.path.split("/") if segment
        )
        base_length = len(self.base_segments)
        if absolute_segments[:base_length] != self.base_segments:
            return None
        try:
            return self._normalize_path(absolute_segments[base_length:])
        except (TypeError, ValueError):
            return None

    @staticmethod
    def _successful_prop(response: ElementTree.Element) -> ElementTree.Element | None:
        for propstat in response.findall(f"{{{DAV_NAMESPACE}}}propstat"):
            status = propstat.find(f"{{{DAV_NAMESPACE}}}status")
            if status is None or not status.text or " 200 " not in status.text:
                continue
            return propstat.find(f"{{{DAV_NAMESPACE}}}prop")
        return response.find(f"{{{DAV_NAMESPACE}}}prop")
