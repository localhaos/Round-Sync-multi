import unittest

from roundsync_pc.mount import normalize_drive_letter, webdav_url_to_unc


class DriveMountHelpersTest(unittest.TestCase):
    def test_normalizes_drive_letter(self) -> None:
        self.assertEqual("R:", normalize_drive_letter(" r: "))
        self.assertEqual("Z:", normalize_drive_letter("z"))

    def test_rejects_reserved_or_invalid_drive_letters(self) -> None:
        for value in ("", "A", "C:", "AA", "1"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                normalize_drive_letter(value)

    def test_converts_http_nonstandard_port_to_webdav_unc(self) -> None:
        self.assertEqual(
            r"\\192.168.1.25@8080\DavWWWRoot",
            webdav_url_to_unc("http://192.168.1.25:8080/"),
        )

    def test_converts_https_path_to_webdav_unc(self) -> None:
        self.assertEqual(
            r"\\phone.local@SSL\DavWWWRoot\share\Muzyka Live",
            webdav_url_to_unc("https://phone.local/share/Muzyka%20Live/"),
        )

    def test_converts_https_nonstandard_port(self) -> None:
        self.assertEqual(
            r"\\phone.local@SSL@8443\DavWWWRoot\share",
            webdav_url_to_unc("https://phone.local:8443/share"),
        )

    def test_rejects_credentials_query_and_parent_segments(self) -> None:
        invalid_endpoints = (
            "http://user:secret@phone.local:8080/",
            "http://phone.local:8080/?token=secret",
            "http://phone.local:8080/a/../b",
        )
        for endpoint in invalid_endpoints:
            with self.subTest(endpoint=endpoint), self.assertRaises(ValueError):
                webdav_url_to_unc(endpoint)


if __name__ == "__main__":
    unittest.main()
