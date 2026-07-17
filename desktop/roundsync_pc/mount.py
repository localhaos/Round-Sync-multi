from __future__ import annotations

import ctypes
import os
import re
import string
import subprocess
import sys
import time
from ctypes import wintypes
from dataclasses import dataclass
from urllib.parse import unquote, urlsplit


DRIVE_LETTERS: tuple[str, ...] = tuple(f"{letter}:" for letter in reversed(string.ascii_uppercase[3:]))

_RESOURCETYPE_DISK = 0x00000001
_CONNECT_UPDATE_PROFILE = 0x00000001
_ERROR_ACCESS_DENIED = 5
_ERROR_BAD_NETPATH = 53
_ERROR_BAD_NET_NAME = 67
_ERROR_ALREADY_ASSIGNED = 85
_ERROR_INVALID_PASSWORD = 86
_ERROR_MORE_DATA = 234
_ERROR_NOT_CONNECTED = 2250
_ERROR_SESSION_CREDENTIAL_CONFLICT = 1219


class DriveMountError(RuntimeError):
    """Raised when Windows cannot create or remove a WebDAV drive mapping."""

    def __init__(self, message: str, *, winerror: int | None = None) -> None:
        self.winerror = winerror
        super().__init__(message)


class ElevationRequired(DriveMountError):
    """Raised when Windows configuration requires an elevated process."""


@dataclass(frozen=True, slots=True)
class MountResult:
    letter: str
    remote_name: str
    persistent: bool
    already_mounted: bool = False


class _NETRESOURCEW(ctypes.Structure):
    _fields_ = [
        ("dwScope", wintypes.DWORD),
        ("dwType", wintypes.DWORD),
        ("dwDisplayType", wintypes.DWORD),
        ("dwUsage", wintypes.DWORD),
        ("lpLocalName", wintypes.LPWSTR),
        ("lpRemoteName", wintypes.LPWSTR),
        ("lpComment", wintypes.LPWSTR),
        ("lpProvider", wintypes.LPWSTR),
    ]


def is_windows() -> bool:
    return os.name == "nt"


def normalize_drive_letter(value: str) -> str:
    normalized = value.strip().rstrip(":").upper()
    if len(normalized) != 1 or normalized < "D" or normalized > "Z":
        raise ValueError("Litera dysku musi należeć do zakresu D:–Z:.")
    return f"{normalized}:"


def webdav_url_to_unc(endpoint: str) -> str:
    value = endpoint.strip()
    if "://" not in value:
        value = "http://" + value

    parsed = urlsplit(value)
    if parsed.scheme not in {"http", "https"}:
        raise ValueError("Montowanie obsługuje wyłącznie WebDAV przez HTTP lub HTTPS.")
    if not parsed.hostname:
        raise ValueError("Adres WebDAV nie zawiera hosta.")
    if parsed.username is not None or parsed.password is not None:
        raise ValueError("Danych logowania nie należy umieszczać w adresie WebDAV.")
    if parsed.query or parsed.fragment:
        raise ValueError("Adres WebDAV nie może zawierać query ani fragmentu.")
    if ":" in parsed.hostname:
        raise ValueError("Systemowy klient WebDAV Windows nie obsługuje tutaj bezpośredniego hosta IPv6.")

    try:
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
    except ValueError as error:
        raise ValueError("Adres WebDAV zawiera nieprawidłowy port.") from error

    server = parsed.hostname
    if parsed.scheme == "https":
        server += "@SSL"
        if port != 443:
            server += f"@{port}"
    elif port != 80:
        server += f"@{port}"

    segments: list[str] = []
    for raw_segment in parsed.path.split("/"):
        if not raw_segment:
            continue
        segment = unquote(raw_segment)
        if segment in {".", ".."} or "\\" in segment or "\x00" in segment:
            raise ValueError("Adres WebDAV zawiera niedozwolony segment ścieżki.")
        segments.append(segment)

    remote_name = rf"\\{server}\DavWWWRoot"
    if segments:
        remote_name += "\\" + "\\".join(segments)
    return remote_name


def is_administrator() -> bool:
    if not is_windows():
        return False
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except (AttributeError, OSError):
        return False


class _SHELLEXECUTEINFOW(ctypes.Structure):
    _fields_ = [
        ("cbSize", wintypes.DWORD),
        ("fMask", wintypes.ULONG),
        ("hwnd", wintypes.HWND),
        ("lpVerb", wintypes.LPCWSTR),
        ("lpFile", wintypes.LPCWSTR),
        ("lpParameters", wintypes.LPCWSTR),
        ("lpDirectory", wintypes.LPCWSTR),
        ("nShow", ctypes.c_int),
        ("hInstApp", wintypes.HINSTANCE),
        ("lpIDList", wintypes.LPVOID),
        ("lpClass", wintypes.LPCWSTR),
        ("hkeyClass", wintypes.HKEY),
        ("dwHotKey", wintypes.DWORD),
        ("hIconOrMonitor", wintypes.HANDLE),
        ("hProcess", wintypes.HANDLE),
    ]


_HELPER_SWITCH = "--roundsync-configure-webclient"
_SEE_MASK_NOCLOSEPROCESS = 0x00000040
_INFINITE = 0xFFFFFFFF
_ERROR_CANCELLED = 1223


def configure_windows_webdav_as_administrator(endpoint: str, authenticated: bool) -> None:
    """Configure WebClient in an elevated helper while preserving the user's drive session."""
    if not is_windows():
        raise DriveMountError("Konfiguracja WebClient jest dostępna wyłącznie w Windows.")

    # Validate before invoking UAC. The endpoint is not a secret and credentials are never passed.
    webdav_url_to_unc(endpoint)
    helper_arguments = [_HELPER_SWITCH, "--endpoint", endpoint]
    if authenticated:
        helper_arguments.append("--authenticated")

    if getattr(sys, "frozen", False):
        executable = sys.executable
        parameters = subprocess.list2cmdline(helper_arguments)
    else:
        executable = sys.executable
        parameters = subprocess.list2cmdline(["-m", "roundsync_pc", *helper_arguments])

    execute_info = _SHELLEXECUTEINFOW()
    execute_info.cbSize = ctypes.sizeof(execute_info)
    execute_info.fMask = _SEE_MASK_NOCLOSEPROCESS
    execute_info.lpVerb = "runas"
    execute_info.lpFile = executable
    execute_info.lpParameters = parameters
    execute_info.nShow = 0

    shell32 = ctypes.WinDLL("shell32", use_last_error=True)
    shell32.ShellExecuteExW.argtypes = [ctypes.POINTER(_SHELLEXECUTEINFOW)]
    shell32.ShellExecuteExW.restype = wintypes.BOOL
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
    kernel32.WaitForSingleObject.restype = wintypes.DWORD
    kernel32.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
    kernel32.GetExitCodeProcess.restype = wintypes.BOOL
    kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel32.CloseHandle.restype = wintypes.BOOL

    try:
        ctypes.set_last_error(0)
        if not shell32.ShellExecuteExW(ctypes.byref(execute_info)):
            error_code = ctypes.get_last_error()
            if error_code == _ERROR_CANCELLED:
                raise DriveMountError("Anulowano żądanie uprawnień administratora.")
            raise DriveMountError(
                f"Nie można uruchomić konfiguratora WebClient (kod Windows {error_code}).",
                winerror=error_code,
            )
        if not execute_info.hProcess:
            raise DriveMountError("Windows nie zwrócił uchwytu procesu konfiguratora WebClient.")

        wait_result = kernel32.WaitForSingleObject(execute_info.hProcess, _INFINITE)
        if wait_result == _INFINITE:
            error_code = ctypes.get_last_error()
            raise DriveMountError(
                f"Oczekiwanie na konfigurator WebClient nie powiodło się (kod Windows {error_code}).",
                winerror=error_code,
            )
        exit_code = wintypes.DWORD()
        if not kernel32.GetExitCodeProcess(execute_info.hProcess, ctypes.byref(exit_code)):
            error_code = ctypes.get_last_error()
            raise DriveMountError(
                f"Nie można odczytać wyniku konfiguratora WebClient (kod Windows {error_code}).",
                winerror=error_code,
            )
        if exit_code.value != 0:
            raise DriveMountError(
                f"Konfigurator WebClient zakończył się błędem (kod {exit_code.value})."
            )
    finally:
        if execute_info.hProcess:
            kernel32.CloseHandle(execute_info.hProcess)


def run_privileged_configuration_if_requested(arguments: list[str]) -> int | None:
    """Execute the hidden elevated helper mode. Returns None during normal GUI startup."""
    if _HELPER_SWITCH not in arguments:
        return None

    try:
        endpoint = arguments[arguments.index("--endpoint") + 1]
    except (ValueError, IndexError):
        return 2

    try:
        if not is_administrator():
            raise DriveMountError("Konfigurator WebClient nie otrzymał uprawnień administratora.")
        prepare_webdav_system(endpoint, authenticated="--authenticated" in arguments)
        return 0
    except Exception as error:
        try:
            ctypes.windll.user32.MessageBoxW(
                None,
                str(error) or error.__class__.__name__,
                "Round Sync PC — konfiguracja WebClient",
                0x00000010,
            )
        except (AttributeError, OSError):
            pass
        return 1


def _run_sc(*arguments: str) -> subprocess.CompletedProcess[str]:
    creation_flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    return subprocess.run(
        ["sc.exe", *arguments],
        check=False,
        capture_output=True,
        text=True,
        errors="replace",
        creationflags=creation_flags,
    )


def _ensure_basic_auth_for_http(endpoint: str, authenticated: bool) -> bool:
    parsed_endpoint = endpoint if "://" in endpoint else "http://" + endpoint
    if not authenticated or urlsplit(parsed_endpoint).scheme != "http":
        return False

    import winreg

    key_path = r"SYSTEM\CurrentControlSet\Services\WebClient\Parameters"
    access = winreg.KEY_READ | getattr(winreg, "KEY_WOW64_64KEY", 0)
    current_value = 0
    try:
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, key_path, 0, access) as key:
            current_value = int(winreg.QueryValueEx(key, "BasicAuthLevel")[0])
    except FileNotFoundError:
        current_value = 0
    except OSError as error:
        raise DriveMountError(f"Nie można odczytać konfiguracji WebClient: {error}") from error

    if current_value >= 2:
        return False
    if not is_administrator():
        raise ElevationRequired(
            "Windows blokuje uwierzytelnianie Basic przez HTTP. "
            "Wymagana jest jednorazowa konfiguracja WebClient przez UAC."
        )

    write_access = winreg.KEY_SET_VALUE | getattr(winreg, "KEY_WOW64_64KEY", 0)
    try:
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, key_path, 0, write_access) as key:
            winreg.SetValueEx(key, "BasicAuthLevel", 0, winreg.REG_DWORD, 2)
    except OSError as error:
        raise DriveMountError(f"Nie można włączyć Basic Auth dla WebClient: {error}") from error
    return True


def _service_state() -> int:
    query = _run_sc("query", "WebClient")
    if query.returncode != 0:
        raise DriveMountError(
            "Usługa systemowa WebClient nie jest dostępna. "
            f"sc.exe: {(query.stderr or query.stdout).strip()}"
        )
    match = re.search(r"(?:STATE|STAN)\s*:\s*(\d+)", query.stdout, flags=re.IGNORECASE)
    if match is None:
        raise DriveMountError("Nie można odczytać stanu usługi WebClient.")
    return int(match.group(1))


def _wait_for_service_state(expected_state: int, timeout_seconds: float = 10.0) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if _service_state() == expected_state:
            return
        time.sleep(0.2)
    raise DriveMountError(
        f"Usługa WebClient nie osiągnęła oczekiwanego stanu {expected_state} w wymaganym czasie."
    )


def _ensure_webclient_service(configuration_changed: bool) -> None:
    # SERVICE_RUNNING = 4, SERVICE_STOPPED = 1. Numeric states are not localized.
    state = _service_state()

    if configuration_changed and state == 4:
        if not is_administrator():
            raise ElevationRequired("Restart usługi WebClient wymaga uprawnień administratora.")
        stop = _run_sc("stop", "WebClient")
        if stop.returncode not in {0, 1062}:
            raise DriveMountError(
                "Nie można zatrzymać usługi WebClient po zmianie konfiguracji: "
                + (stop.stderr or stop.stdout).strip()
            )
        _wait_for_service_state(1)
        state = 1

    if state == 4:
        return

    start = _run_sc("start", "WebClient")
    if start.returncode == 1058:
        if not is_administrator():
            raise ElevationRequired(
                "Usługa WebClient jest wyłączona i jej włączenie wymaga uprawnień administratora."
            )
        configure = _run_sc("config", "WebClient", "start=", "demand")
        if configure.returncode != 0:
            raise DriveMountError(
                "Nie można włączyć usługi WebClient: "
                + (configure.stderr or configure.stdout).strip()
            )
        start = _run_sc("start", "WebClient")

    # 1056: service instance already running.
    if start.returncode not in {0, 1056}:
        details = (start.stderr or start.stdout).strip()
        if start.returncode in {_ERROR_ACCESS_DENIED, 5}:
            raise ElevationRequired(
                "Uruchomienie lub skonfigurowanie usługi WebClient wymaga uprawnień administratora."
            )
        raise DriveMountError(f"Nie można uruchomić usługi WebClient: {details}")
    _wait_for_service_state(4)


def prepare_webdav_system(endpoint: str, authenticated: bool) -> None:
    """Validate the endpoint, configure Basic Auth when required, and start WebClient."""
    webdav_url_to_unc(endpoint)
    configuration_changed = _ensure_basic_auth_for_http(endpoint, authenticated)
    _ensure_webclient_service(configuration_changed)


class WindowsDriveMounter:
    def __init__(self) -> None:
        if not is_windows():
            raise DriveMountError("Montowanie pod literą dysku jest dostępne wyłącznie w Windows.")

        self._mpr = ctypes.WinDLL("mpr", use_last_error=True)
        self._mpr.WNetAddConnection2W.argtypes = [
            ctypes.POINTER(_NETRESOURCEW),
            wintypes.LPCWSTR,
            wintypes.LPCWSTR,
            wintypes.DWORD,
        ]
        self._mpr.WNetAddConnection2W.restype = wintypes.DWORD
        self._mpr.WNetCancelConnection2W.argtypes = [
            wintypes.LPCWSTR,
            wintypes.DWORD,
            wintypes.BOOL,
        ]
        self._mpr.WNetCancelConnection2W.restype = wintypes.DWORD
        self._mpr.WNetGetConnectionW.argtypes = [
            wintypes.LPCWSTR,
            wintypes.LPWSTR,
            ctypes.POINTER(wintypes.DWORD),
        ]
        self._mpr.WNetGetConnectionW.restype = wintypes.DWORD

    def get_mapping(self, drive_letter: str) -> str | None:
        letter = normalize_drive_letter(drive_letter)
        capacity = wintypes.DWORD(2048)
        while True:
            buffer = ctypes.create_unicode_buffer(capacity.value)
            result = self._mpr.WNetGetConnectionW(letter, buffer, ctypes.byref(capacity))
            if result == 0:
                return buffer.value
            if result == _ERROR_NOT_CONNECTED:
                return None
            if result == _ERROR_MORE_DATA:
                continue
            raise self._windows_error(result, f"Nie można sprawdzić mapowania {letter}")

    def mount(
        self,
        endpoint: str,
        drive_letter: str,
        username: str = "",
        password: str = "",
        *,
        persistent: bool = True,
    ) -> MountResult:
        letter = normalize_drive_letter(drive_letter)
        remote_name = webdav_url_to_unc(endpoint)
        existing = self.get_mapping(letter)
        if existing:
            if existing.casefold().rstrip("\\") == remote_name.casefold().rstrip("\\"):
                return MountResult(letter, remote_name, persistent, already_mounted=True)
            raise DriveMountError(
                f"{letter} jest już przypisany do {existing}. Wybierz inną literę.",
                winerror=_ERROR_ALREADY_ASSIGNED,
            )

        prepare_webdav_system(endpoint, authenticated=bool(username or password))

        resource = _NETRESOURCEW()
        resource.dwType = _RESOURCETYPE_DISK
        resource.lpLocalName = letter
        resource.lpRemoteName = remote_name
        flags = _CONNECT_UPDATE_PROFILE if persistent else 0

        result = self._mpr.WNetAddConnection2W(
            ctypes.byref(resource),
            password or None,
            username or None,
            flags,
        )
        if result != 0:
            raise self._windows_error(
                result,
                f"Nie udało się zamontować {remote_name} jako {letter}",
            )

        confirmed = self.get_mapping(letter)
        if not confirmed:
            raise DriveMountError("Windows nie potwierdził utworzonego mapowania dysku.")
        return MountResult(letter, confirmed, persistent)

    def unmount(self, drive_letter: str, *, force: bool = True) -> bool:
        letter = normalize_drive_letter(drive_letter)
        if self.get_mapping(letter) is None:
            return False
        result = self._mpr.WNetCancelConnection2W(
            letter,
            _CONNECT_UPDATE_PROFILE,
            bool(force),
        )
        if result == _ERROR_NOT_CONNECTED:
            return False
        if result != 0:
            raise self._windows_error(result, f"Nie udało się odmontować {letter}")
        return True

    @staticmethod
    def _windows_error(code: int, prefix: str) -> DriveMountError:
        explanations = {
            _ERROR_ACCESS_DENIED: "odmowa dostępu",
            _ERROR_BAD_NETPATH: "nie znaleziono ścieżki sieciowej",
            _ERROR_BAD_NET_NAME: "nieprawidłowa nazwa zasobu sieciowego",
            _ERROR_ALREADY_ASSIGNED: "litera jest już zajęta",
            _ERROR_INVALID_PASSWORD: "nieprawidłowe dane logowania",
            _ERROR_SESSION_CREDENTIAL_CONFLICT: (
                "istnieje inne połączenie z tym hostem przy użyciu odmiennych poświadczeń; "
                "odłącz poprzednie mapowanie lub wyloguj sesję WebDAV"
            ),
        }
        detail = explanations.get(code)
        try:
            system_message = ctypes.FormatError(code).strip()
        except (AttributeError, OSError):
            system_message = ""
        parts = [prefix, f"kod Windows {code}"]
        if detail:
            parts.append(detail)
        if system_message and system_message.casefold() not in " ".join(parts).casefold():
            parts.append(system_message)
        return DriveMountError(": ".join(parts), winerror=code)
