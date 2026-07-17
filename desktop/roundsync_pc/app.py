from __future__ import annotations

import queue
import tkinter as tk
from concurrent.futures import Future, ThreadPoolExecutor
from pathlib import Path
from tkinter import filedialog, messagebox, simpledialog, ttk
from typing import Callable, TypeVar

from roundsync_pc.discovery import DiscoveredDevice, DiscoveryClient
from roundsync_pc.webdav import WebDavClient, WebDavEntry, WebDavError


Result = TypeVar("Result")


class RoundSyncDesktopApp:
    POLL_INTERVAL_MS = 75

    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.root.title("Round Sync PC")
        self.root.geometry("1040x680")
        self.root.minsize(820, 520)

        self.client: WebDavClient | None = None
        self.current_path: tuple[str, ...] = ()
        self.devices: list[DiscoveredDevice] = []
        self.entries: dict[str, WebDavEntry] = {}
        self.executor = ThreadPoolExecutor(max_workers=2, thread_name_prefix="roundsync-pc")
        self.results: queue.Queue[
            tuple[Future[object], Callable[[object], None] | None]
        ] = queue.Queue()
        self.busy = False

        self.endpoint = tk.StringVar(value="http://ADRES-TELEFONU:8080/")
        self.username = tk.StringVar()
        self.password = tk.StringVar()
        self.device_selection = tk.StringVar()
        self.path_text = tk.StringVar(value="/")
        self.status_text = tk.StringVar(
            value="W telefonie uruchom Udostępnij → PC / LAN mode, następnie wybierz Wykryj."
        )

        self._build_ui()
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.root.after(self.POLL_INTERVAL_MS, self._poll_results)

    def _build_ui(self) -> None:
        self.root.columnconfigure(0, weight=1)
        self.root.rowconfigure(2, weight=1)

        connection = ttk.LabelFrame(self.root, text="Połączenie z telefonem", padding=10)
        connection.grid(row=0, column=0, sticky="ew", padx=10, pady=(10, 5))
        connection.columnconfigure(1, weight=1)
        connection.columnconfigure(3, weight=1)

        ttk.Button(connection, text="Wykryj w LAN", command=self.discover).grid(
            row=0, column=0, padx=(0, 8), pady=3
        )
        self.device_box = ttk.Combobox(
            connection,
            textvariable=self.device_selection,
            state="readonly",
        )
        self.device_box.grid(row=0, column=1, columnspan=3, sticky="ew", pady=3)
        self.device_box.bind("<<ComboboxSelected>>", self._device_selected)

        ttk.Label(connection, text="Adres WebDAV:").grid(row=1, column=0, sticky="w", pady=3)
        ttk.Entry(connection, textvariable=self.endpoint).grid(
            row=1, column=1, columnspan=3, sticky="ew", pady=3
        )

        ttk.Label(connection, text="Użytkownik:").grid(row=2, column=0, sticky="w", pady=3)
        ttk.Entry(connection, textvariable=self.username).grid(
            row=2, column=1, sticky="ew", padx=(0, 10), pady=3
        )
        ttk.Label(connection, text="Hasło:").grid(row=2, column=2, sticky="w", pady=3)
        ttk.Entry(connection, textvariable=self.password, show="●").grid(
            row=2, column=3, sticky="ew", pady=3
        )

        ttk.Button(connection, text="Połącz", command=self.connect).grid(
            row=3, column=3, sticky="e", pady=(8, 0)
        )

        toolbar = ttk.Frame(self.root, padding=(10, 5))
        toolbar.grid(row=1, column=0, sticky="ew")
        ttk.Button(toolbar, text="W górę", command=self.go_up).pack(side="left", padx=(0, 5))
        ttk.Button(toolbar, text="Odśwież", command=self.refresh).pack(side="left", padx=5)
        ttk.Button(toolbar, text="Wyślij pliki", command=self.upload).pack(side="left", padx=5)
        ttk.Button(toolbar, text="Pobierz", command=self.download).pack(side="left", padx=5)
        ttk.Button(toolbar, text="Nowy folder", command=self.create_directory).pack(side="left", padx=5)
        ttk.Button(toolbar, text="Usuń", command=self.delete).pack(side="left", padx=5)
        ttk.Label(toolbar, textvariable=self.path_text).pack(side="right", padx=5)

        content = ttk.Frame(self.root, padding=(10, 0, 10, 5))
        content.grid(row=2, column=0, sticky="nsew")
        content.columnconfigure(0, weight=1)
        content.rowconfigure(0, weight=1)

        self.tree = ttk.Treeview(
            content,
            columns=("type", "size", "modified"),
            show="tree headings",
            selectmode="extended",
        )
        self.tree.heading("#0", text="Nazwa")
        self.tree.heading("type", text="Typ")
        self.tree.heading("size", text="Rozmiar")
        self.tree.heading("modified", text="Modyfikacja")
        self.tree.column("#0", width=430, minwidth=180)
        self.tree.column("type", width=100, anchor="center")
        self.tree.column("size", width=120, anchor="e")
        self.tree.column("modified", width=230)
        self.tree.grid(row=0, column=0, sticky="nsew")
        self.tree.bind("<Double-1>", self._open_selected)

        scrollbar = ttk.Scrollbar(content, orient="vertical", command=self.tree.yview)
        scrollbar.grid(row=0, column=1, sticky="ns")
        self.tree.configure(yscrollcommand=scrollbar.set)

        status = ttk.Label(self.root, textvariable=self.status_text, relief="sunken", anchor="w")
        status.grid(row=3, column=0, sticky="ew")

    def discover(self) -> None:
        self._submit(
            "Wykrywanie urządzeń Round Sync w sieci…",
            DiscoveryClient(timeout=2.2).discover,
            self._discovery_finished,
        )

    def _discovery_finished(self, result: object) -> None:
        self.devices = list(result)  # type: ignore[arg-type]
        labels = [device.label for device in self.devices]
        self.device_box.configure(values=labels)
        if not self.devices:
            self.device_selection.set("")
            self.status_text.set("Nie znaleziono telefonu. Możesz podać adres WebDAV ręcznie.")
            messagebox.showinfo(
                "Brak urządzeń",
                "Nie znaleziono aktywnego Round Sync.\n\n"
                "Na telefonie otwórz katalog, wybierz Udostępnij, zaznacz PC / LAN mode "
                "i uruchom serwer. Oba urządzenia muszą być w tej samej sieci bez izolacji klientów.",
                parent=self.root,
            )
            return
        self.device_box.current(0)
        self._apply_device(self.devices[0])
        self.status_text.set(f"Znaleziono urządzenia: {len(self.devices)}. Wprowadź dane logowania i połącz.")

    def _device_selected(self, _event: tk.Event[tk.Misc]) -> None:
        index = self.device_box.current()
        if 0 <= index < len(self.devices):
            self._apply_device(self.devices[index])

    def _apply_device(self, device: DiscoveredDevice) -> None:
        self.endpoint.set(device.endpoint)
        requirement = "wymaga logowania" if device.authentication_required else "nie wymaga logowania"
        self.status_text.set(
            f"{device.name}, Round Sync {device.app_version}, {device.address}:{device.port}, {requirement}."
        )

    def connect(self) -> None:
        try:
            candidate = WebDavClient(
                self.endpoint.get(),
                self.username.get(),
                self.password.get(),
            )
        except ValueError as error:
            messagebox.showerror("Nieprawidłowy adres", str(error), parent=self.root)
            return

        def operation() -> tuple[WebDavClient, list[WebDavEntry]]:
            return candidate, candidate.list_directory(())

        self._submit("Łączenie z telefonem…", operation, self._connected)

    def _connected(self, result: object) -> None:
        client, entries = result  # type: ignore[misc]
        self.client = client
        self.current_path = ()
        self._render_entries(entries)
        self.status_text.set(f"Połączono z {self.client.host}:{self.client.port}.")

    def refresh(self) -> None:
        if self.client is None:
            self._not_connected()
            return
        path = self.current_path
        self._submit(
            "Pobieranie listy plików…",
            lambda: self.client.list_directory(path),
            self._render_entries,
        )

    def go_up(self) -> None:
        if self.client is None:
            self._not_connected()
            return
        if not self.current_path:
            return
        self.current_path = self.current_path[:-1]
        self.refresh()

    def _open_selected(self, _event: tk.Event[tk.Misc]) -> None:
        selected = self._selected_entries()
        if len(selected) != 1 or not selected[0].is_directory:
            return
        self.current_path = selected[0].path
        self.refresh()

    def upload(self) -> None:
        if self.client is None:
            self._not_connected()
            return
        sources = [Path(item) for item in filedialog.askopenfilenames(parent=self.root)]
        if not sources:
            return
        destination = self.current_path

        def operation() -> int:
            for source in sources:
                self.client.upload(source, (*destination, source.name))
            return len(sources)

        self._submit(
            f"Wysyłanie plików: {len(sources)}…",
            operation,
            lambda count: self._operation_and_refresh(f"Wysłano pliki: {count}."),
        )

    def download(self) -> None:
        if self.client is None:
            self._not_connected()
            return
        selected = self._selected_entries()
        if not selected:
            return
        if any(entry.is_directory for entry in selected):
            messagebox.showinfo(
                "Pobieranie",
                "W tej wersji wybierz pliki; rekursywne pobieranie folderów nie jest obsługiwane.",
                parent=self.root,
            )
            return
        destination_directory = filedialog.askdirectory(parent=self.root)
        if not destination_directory:
            return
        destination_root = Path(destination_directory)
        try:
            destinations = {
                entry: self._download_destination(destination_root, entry) for entry in selected
            }
        except ValueError as error:
            messagebox.showerror("Nieprawidłowa nazwa pliku", str(error), parent=self.root)
            return
        collisions = [entry for entry in selected if destinations[entry].exists()]
        if collisions and not messagebox.askyesno(
            "Nadpisać pliki?",
            f"Istniejące pliki do nadpisania: {len(collisions)}. Kontynuować?",
            parent=self.root,
        ):
            return

        def operation() -> int:
            for entry in selected:
                self.client.download(entry.path, destinations[entry])
            return len(selected)

        self._submit(
            f"Pobieranie plików: {len(selected)}…",
            operation,
            lambda count: self.status_text.set(f"Pobrano pliki: {count}."),
        )

    def create_directory(self) -> None:
        if self.client is None:
            self._not_connected()
            return
        name = simpledialog.askstring("Nowy folder", "Nazwa folderu:", parent=self.root)
        if name is None:
            return
        name = name.strip()
        if not name or name in {".", ".."} or any(character in name for character in "/\\\x00"):
            messagebox.showerror("Nieprawidłowa nazwa", "Podaj pojedynczą, poprawną nazwę folderu.", parent=self.root)
            return
        path = (*self.current_path, name)
        self._submit(
            "Tworzenie folderu…",
            lambda: self.client.create_directory(path),
            lambda _result: self._operation_and_refresh(f"Utworzono folder: {name}."),
        )

    def delete(self) -> None:
        if self.client is None:
            self._not_connected()
            return
        selected = self._selected_entries()
        if not selected:
            return
        if not messagebox.askyesno(
            "Potwierdzenie usunięcia",
            f"Trwale usunąć wybrane elementy: {len(selected)}?",
            icon="warning",
            parent=self.root,
        ):
            return

        def operation() -> int:
            for entry in selected:
                self.client.delete(entry.path, is_directory=entry.is_directory)
            return len(selected)

        self._submit(
            "Usuwanie…",
            operation,
            lambda count: self._operation_and_refresh(f"Usunięto elementy: {count}."),
        )

    def _operation_and_refresh(self, message: str) -> None:
        self.status_text.set(message)
        self.refresh()

    def _render_entries(self, result: object) -> None:
        entries = list(result)  # type: ignore[arg-type]
        self.tree.delete(*self.tree.get_children())
        self.entries.clear()
        for index, entry in enumerate(entries):
            item_id = f"entry-{index}"
            self.entries[item_id] = entry
            self.tree.insert(
                "",
                "end",
                iid=item_id,
                text=entry.name,
                values=(
                    "Folder" if entry.is_directory else "Plik",
                    "" if entry.is_directory else self._format_size(entry.size),
                    entry.modified,
                ),
            )
        self.path_text.set("/" + "/".join(self.current_path))
        self.status_text.set(f"Elementy: {len(entries)}.")

    def _selected_entries(self) -> list[WebDavEntry]:
        return [self.entries[item] for item in self.tree.selection() if item in self.entries]

    @staticmethod
    def _format_size(size: int | None) -> str:
        if size is None:
            return ""
        value = float(size)
        for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
            if value < 1024 or unit == "TiB":
                return f"{value:.0f} {unit}" if unit == "B" else f"{value:.1f} {unit}"
            value /= 1024
        return f"{size} B"

    @staticmethod
    def _download_destination(root: Path, entry: WebDavEntry) -> Path:
        if not entry.path:
            raise ValueError("Serwer zwrócił pustą nazwę pliku")
        filename = entry.path[-1]
        if not filename or filename in {".", ".."} or any(character in filename for character in "/\\\x00"):
            raise ValueError(f"Serwer zwrócił niedozwoloną nazwę pliku: {filename!r}")
        return root / filename

    def _submit(
        self,
        status: str,
        operation: Callable[[], Result],
        on_success: Callable[[Result], None] | None = None,
    ) -> None:
        if self.busy:
            self.status_text.set("Inna operacja jest nadal wykonywana.")
            return
        self.busy = True
        self.status_text.set(status)
        future: Future[Result] = self.executor.submit(operation)
        future.add_done_callback(
            lambda completed: self.results.put((completed, on_success))  # type: ignore[arg-type]
        )

    def _poll_results(self) -> None:
        try:
            while True:
                future, callback = self.results.get_nowait()
                self.busy = False
                try:
                    result = future.result()
                except Exception as error:
                    self._show_error(error)
                else:
                    if callback is not None:
                        callback(result)
        except queue.Empty:
            pass
        if self.root.winfo_exists():
            self.root.after(self.POLL_INTERVAL_MS, self._poll_results)

    def _show_error(self, error: Exception) -> None:
        if isinstance(error, WebDavError) and error.status == 401:
            text = "Serwer odrzucił dane logowania. Sprawdź użytkownika i hasło ustawione w telefonie."
        elif isinstance(error, WebDavError) and error.status == 403:
            text = "Serwer odmówił dostępu do tej operacji."
        else:
            text = str(error) or error.__class__.__name__
        self.status_text.set(f"Błąd: {text}")
        messagebox.showerror("Błąd Round Sync", text, parent=self.root)

    def _not_connected(self) -> None:
        messagebox.showinfo("Brak połączenia", "Najpierw połącz się z telefonem.", parent=self.root)

    def close(self) -> None:
        self.executor.shutdown(wait=False, cancel_futures=True)
        self.root.destroy()


def main() -> None:
    root = tk.Tk()
    try:
        style = ttk.Style(root)
        if "vista" in style.theme_names():
            style.theme_use("vista")
    except tk.TclError:
        pass
    RoundSyncDesktopApp(root)
    root.mainloop()
