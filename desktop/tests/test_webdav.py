from __future__ import annotations

import base64
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from roundsync_pc.webdav import WebDavClient, WebDavError


MULTISTATUS = b"""<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/share/</d:href>
    <d:propstat><d:prop><d:displayname>share</d:displayname><d:resourcetype><d:collection /></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  </d:response>
  <d:response>
    <d:href>/share/Muzyka/</d:href>
    <d:propstat><d:prop><d:displayname>Muzyka</d:displayname><d:resourcetype><d:collection /></d:resourcetype><d:getlastmodified>Fri, 17 Jul 2026 12:00:00 GMT</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  </d:response>
  <d:response>
    <d:href>/share/test%20file.txt</d:href>
    <d:propstat><d:prop><d:displayname>test file.txt</d:displayname><d:resourcetype /><d:getcontentlength>5</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  </d:response>
</d:multistatus>
"""


class FakeWebDavHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    uploads: dict[str, bytes] = {}
    methods: list[tuple[str, str]] = []
    expected_auth = "Basic " + base64.b64encode(b"user:secret").decode("ascii")

    def _authorized(self) -> bool:
        if self.headers.get("Authorization") == self.expected_auth:
            return True
        self.send_response(401)
        self.send_header("Content-Length", "0")
        self.end_headers()
        return False

    def do_PROPFIND(self) -> None:  # noqa: N802
        if not self._authorized():
            return
        self.methods.append(("PROPFIND", self.path))
        length = int(self.headers.get("Content-Length", "0"))
        self.rfile.read(length)
        self.send_response(207)
        self.send_header("Content-Type", "application/xml")
        self.send_header("Content-Length", str(len(MULTISTATUS)))
        self.end_headers()
        self.wfile.write(MULTISTATUS)

    def do_PUT(self) -> None:  # noqa: N802
        if not self._authorized():
            return
        self.methods.append(("PUT", self.path))
        length = int(self.headers["Content-Length"])
        self.uploads[self.path] = self.rfile.read(length)
        self.send_response(201)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self) -> None:  # noqa: N802
        if not self._authorized():
            return
        self.methods.append(("GET", self.path))
        payload = b"phone-data"
        self.send_response(200)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_MKCOL(self) -> None:  # noqa: N802
        if not self._authorized():
            return
        self.methods.append(("MKCOL", self.path))
        self.send_response(201)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_DELETE(self) -> None:  # noqa: N802
        if not self._authorized():
            return
        self.methods.append(("DELETE", self.path))
        self.send_response(204)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, _format: str, *_args: object) -> None:
        return


class WebDavClientTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), FakeWebDavHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls) -> None:
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)

    def setUp(self) -> None:
        FakeWebDavHandler.methods.clear()
        FakeWebDavHandler.uploads.clear()
        endpoint = f"http://127.0.0.1:{self.server.server_port}/share/"
        self.client = WebDavClient(endpoint, "user", "secret")

    def test_lists_directory_and_decodes_paths(self) -> None:
        entries = self.client.list_directory()

        self.assertEqual(["Muzyka", "test file.txt"], [entry.name for entry in entries])
        self.assertTrue(entries[0].is_directory)
        self.assertEqual(("test file.txt",), entries[1].path)
        self.assertEqual(5, entries[1].size)
        self.assertEqual(("PROPFIND", "/share/"), FakeWebDavHandler.methods[0])

    def test_upload_download_create_and_delete(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary = Path(temporary_directory)
            source = temporary / "set test.txt"
            source.write_bytes(b"desktop-data")
            destination = temporary / "download.bin"

            self.client.upload(source, ("Muzyka", source.name))
            self.client.download(("remote.bin",), destination)
            self.client.create_directory(("Nowy folder",))
            self.client.delete(("old.txt",))

            self.assertEqual(b"desktop-data", FakeWebDavHandler.uploads["/share/Muzyka/set%20test.txt"])
            self.assertEqual(b"phone-data", destination.read_bytes())
            self.assertIn(("MKCOL", "/share/Nowy%20folder/"), FakeWebDavHandler.methods)
            self.assertIn(("DELETE", "/share/old.txt"), FakeWebDavHandler.methods)

    def test_rejects_invalid_credentials(self) -> None:
        endpoint = f"http://127.0.0.1:{self.server.server_port}/share/"
        client = WebDavClient(endpoint, "user", "wrong")
        with self.assertRaises(WebDavError) as context:
            client.list_directory()
        self.assertEqual(401, context.exception.status)


if __name__ == "__main__":
    unittest.main()
