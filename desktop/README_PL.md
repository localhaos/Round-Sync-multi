# Round Sync PC

Natywny klient LAN dla Windows, łączący się bezpośrednio z serwerem WebDAV uruchomionym przez Round Sync na Androidzie. Połączenie nie korzysta z chmury ani serwera pośredniczącego.

## Uruchomienie

1. Na telefonie otwórz w Round Sync zdalny lub lokalny katalog, który chcesz udostępnić.
2. Wybierz `Serve…` i zaznacz `PC / LAN mode`. Aplikacja ustawi WebDAV, dostęp LAN oraz wygeneruje tymczasową nazwę użytkownika i silne hasło; możesz je zmienić przed zatwierdzeniem.
3. Na Windows uruchom `RoundSync-PC.exe` z artefaktu workflow `Desktop client`.
4. Wybierz `Wykryj w LAN`, wskaż telefon, wpisz te same dane logowania i wybierz `Połącz`.

Klient obsługuje przeglądanie katalogów, wysyłanie i pobieranie plików, tworzenie katalogów oraz usuwanie. Jeżeli router blokuje broadcast UDP, wpisz ręcznie adres pokazany w powiadomieniu Androida, np. `http://192.168.1.25:8080/`.

## Wymagania i bezpieczeństwo

- telefon i PC muszą znajdować się w tej samej sieci IP;
- izolacja klientów Wi-Fi/AP isolation musi być wyłączona;
- discovery wykorzystuje UDP/21080, a WebDAV domyślnie TCP/8080;
- odpowiedź discovery nie zawiera nazwy użytkownika, hasła ani ścieżki udziału;
- poświadczenia są przechowywane wyłącznie w pamięci procesu klienta;
- standardowy serwer Round Sync używa HTTP i Basic Auth, dlatego tryb LAN należy uruchamiać wyłącznie w zaufanej sieci. Dla sieci niezaufanych użyj tunelu VPN.

Firewall Windows nie wymaga reguły przychodzącej dla serwera, ponieważ PC działa jako klient. Firewall lub polityka routera musi jednak dopuszczać odpowiedź UDP oraz połączenie wychodzące TCP do telefonu.

## Uruchomienie ze źródeł

Wymagany jest Python 3.10+ z Tk:

```powershell
cd desktop
py -3 -m roundsync_pc
```

Można również uruchomić `desktop\run_windows.bat`.

Testy nie wymagają dostępu do Internetu:

```powershell
cd desktop
py -3 -m unittest discover -s tests -v
```
