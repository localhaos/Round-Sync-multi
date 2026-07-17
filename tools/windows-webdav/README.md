# Windows WebDAV drive mounter

This helper mounts the WebDAV server started by Round Sync as a Windows drive.

It does not contain a fixed IP address, drive letter, user name, or password.

## One-click usage

1. In Round Sync, start **Streaming → WebDAV** and keep the Android device in the same LAN as the Windows PC.
2. Run `Mount-RoundSync-WebDAV.cmd`.
3. Approve the administrator prompt.
4. Enter the WebDAV credentials.

The script:

- finds active private IPv4 interfaces;
- scans adjacent LAN addresses on TCP port `8080`;
- verifies candidates with WebDAV `OPTIONS` and `PROPFIND`;
- selects the only server automatically, or asks which server to use when several are found;
- selects the highest free drive letter from `Z:` down to `D:`;
- enables `BasicAuthLevel=2` only for an `http://` endpoint;
- starts or restarts the Windows `WebClient` service only when required;
- creates a persistent mapping through the Windows network provider.

## Selecting a specific drive letter

Run an elevated Windows PowerShell 5.1 console:

```powershell
.\Mount-RoundSync-WebDAV.ps1 -DriveLetter R
```

The script still discovers the current Android IP automatically.

## Explicit endpoint

Discovery can be bypassed:

```powershell
.\Mount-RoundSync-WebDAV.ps1 `
  -ServerUri 'http://192.168.10.248:8080/' `
  -DriveLetter R
```

The endpoint is only an example. No address is embedded in the scripts.

## Other options

Temporary mapping:

```powershell
.\Mount-RoundSync-WebDAV.ps1 -Temporary
```

Different WebDAV port:

```powershell
.\Mount-RoundSync-WebDAV.ps1 -Port 9090
```

Replace an existing network mapping on the requested letter:

```powershell
.\Mount-RoundSync-WebDAV.ps1 -DriveLetter R -Force
```

Non-interactive execution:

```powershell
$credential = Import-Clixml "$env:USERPROFILE\.roundsync-webdav-credential.xml"

.\Mount-RoundSync-WebDAV.ps1 `
  -DriveLetter R `
  -Credential $credential `
  -NonInteractive
```

Create the user-bound encrypted credential file once:

```powershell
Get-Credential | Export-Clixml "$env:USERPROFILE\.roundsync-webdav-credential.xml"
```

`Export-Clixml` protects the secret with Windows DPAPI for the current Windows user and computer.

## Security

Windows blocks Basic authentication over plain HTTP by default. For an `http://` WebDAV endpoint, the script sets:

```text
HKLM\SYSTEM\CurrentControlSet\Services\WebClient\Parameters\BasicAuthLevel = 2
```

Basic authentication over HTTP is not encrypted. Use it only on a trusted LAN. Prefer HTTPS when the WebDAV server is configured with TLS.

The script never stores credentials in its source code and does not invoke `net use` with the password exposed in the command line.
