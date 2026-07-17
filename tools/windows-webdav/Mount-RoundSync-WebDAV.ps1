#Requires -Version 5.1
<#
.SYNOPSIS
Discovers a Round Sync WebDAV server in the local network and mounts it as a Windows drive.

.DESCRIPTION
The script contains no fixed IP address, drive letter, user name, or password.
When ServerUri is omitted, active private IPv4 networks are scanned on Port and
each candidate is verified with WebDAV OPTIONS/PROPFIND requests.

When DriveLetter is omitted, the highest free letter from Z: down to D: is used.
Plain HTTP WebDAV requires Windows WebClient BasicAuthLevel=2; the setting is
changed only when the selected endpoint uses HTTP.

Run from an elevated Windows PowerShell 5.1 session or use the accompanying CMD launcher.
#>

[CmdletBinding()]
param(
    [ValidatePattern('^[D-Za-z]:?$')]
    [string] $DriveLetter,

    [ValidateNotNull()]
    [uri] $ServerUri,

    [ValidateRange(1, 65535)]
    [int] $Port = 8080,

    [ValidateRange(100, 5000)]
    [int] $ConnectTimeoutMs = 700,

    [ValidateRange(1, 30)]
    [int] $HttpTimeoutSeconds = 3,

    [System.Management.Automation.PSCredential] $Credential,

    [switch] $Temporary,

    [switch] $Force,

    [switch] $NonInteractive
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Write-Step {
    param([Parameter(Mandatory)][string] $Message)
    Write-Host ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $Message)
}

function Test-IsAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Normalize-DriveLetter {
    param([Parameter(Mandatory)][string] $Value)

    $normalized = $Value.Trim().TrimEnd(':').ToUpperInvariant()
    if ($normalized -notmatch '^[D-Z]$') {
        throw "Nieprawidłowa litera dysku '$Value'. Dozwolony zakres: D-Z."
    }

    return "$normalized`:"
}

function Get-UsedDriveLetters {
    $letters = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase
    )

    foreach ($drive in [System.IO.DriveInfo]::GetDrives()) {
        if ($drive.Name -match '^[A-Za-z]:\\$') {
            [void] $letters.Add($drive.Name.Substring(0, 2).ToUpperInvariant())
        }
    }

    try {
        Get-CimInstance -ClassName Win32_LogicalDisk -ErrorAction Stop |
            ForEach-Object {
                if ($_.DeviceID -match '^[A-Za-z]:$') {
                    [void] $letters.Add($_.DeviceID.ToUpperInvariant())
                }
            }
    }
    catch {
        Write-Verbose "Win32_LogicalDisk unavailable: $($_.Exception.Message)"
    }

    return ,$letters
}

function Resolve-DriveLetter {
    param([string] $RequestedLetter)

    $used = Get-UsedDriveLetters

    if (-not [string]::IsNullOrWhiteSpace($RequestedLetter)) {
        return Normalize-DriveLetter -Value $RequestedLetter
    }

    for ($code = [int][char]'Z'; $code -ge [int][char]'D'; $code--) {
        $candidate = "$([char]$code):"
        if (-not $used.Contains($candidate)) {
            return $candidate
        }
    }

    throw 'Brak wolnej litery dysku w zakresie D-Z.'
}

function Test-IsPrivateIPv4 {
    param([Parameter(Mandatory)][string] $Address)

    $parts = $Address.Split('.')
    if ($parts.Count -ne 4) {
        return $false
    }

    $a = [int] $parts[0]
    $b = [int] $parts[1]

    return (
        $a -eq 10 -or
        ($a -eq 172 -and $b -ge 16 -and $b -le 31) -or
        ($a -eq 192 -and $b -eq 168)
    )
}

function Get-LocalPrivateIPv4Addresses {
    $result = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase
    )

    if (Get-Command Get-NetIPAddress -ErrorAction SilentlyContinue) {
        Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object {
                $_.IPAddress -and
                $_.IPAddress -ne '127.0.0.1' -and
                $_.IPAddress -notlike '169.254.*' -and
                $_.AddressState -ne 'Duplicate' -and
                (Test-IsPrivateIPv4 -Address $_.IPAddress)
            } |
            ForEach-Object { [void] $result.Add($_.IPAddress) }
    }

    if ($result.Count -eq 0) {
        foreach ($networkInterface in [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
            if ($networkInterface.OperationalStatus -ne [System.Net.NetworkInformation.OperationalStatus]::Up) {
                continue
            }

            foreach ($unicast in $networkInterface.GetIPProperties().UnicastAddresses) {
                if ($unicast.Address.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) {
                    continue
                }

                $address = $unicast.Address.ToString()
                if (Test-IsPrivateIPv4 -Address $address) {
                    [void] $result.Add($address)
                }
            }
        }
    }

    return @($result)
}

function Get-DiscoveryAddresses {
    $localAddresses = @(Get-LocalPrivateIPv4Addresses)
    if ($localAddresses.Count -eq 0) {
        throw 'Nie znaleziono aktywnego prywatnego interfejsu IPv4.'
    }

    $targets = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase
    )

    # Scan the directly adjacent /24 for every active private interface.
    # This caps discovery time even on /8 or /16 enterprise/VPN routes.
    foreach ($localAddress in $localAddresses) {
        $parts = $localAddress.Split('.')
        $prefix = "$($parts[0]).$($parts[1]).$($parts[2])"
        for ($hostId = 1; $hostId -le 254; $hostId++) {
            $candidate = "$prefix.$hostId"
            if ($candidate -ne $localAddress) {
                [void] $targets.Add($candidate)
            }
        }
    }

    # Add known neighbours and default gateways, including devices outside the
    # local /24 when the actual network has a wider prefix.
    if (Get-Command Get-NetNeighbor -ErrorAction SilentlyContinue) {
        Get-NetNeighbor -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object {
                $_.IPAddress -and
                $_.State -notin @('Unreachable', 'Incomplete') -and
                (Test-IsPrivateIPv4 -Address $_.IPAddress) -and
                $_.IPAddress -notin $localAddresses
            } |
            ForEach-Object { [void] $targets.Add($_.IPAddress) }
    }

    if (Get-Command Get-NetRoute -ErrorAction SilentlyContinue) {
        Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue |
            Sort-Object RouteMetric |
            ForEach-Object {
                if ($_.NextHop -and (Test-IsPrivateIPv4 -Address $_.NextHop)) {
                    [void] $targets.Add($_.NextHop)
                }
            }
    }

    return @($targets)
}

function Find-OpenTcpEndpoints {
    param(
        [Parameter(Mandatory)][string[]] $Addresses,
        [Parameter(Mandatory)][int] $TargetPort,
        [Parameter(Mandatory)][int] $TimeoutMs
    )

    $probes = [System.Collections.Generic.List[object]]::new()

    foreach ($address in $Addresses) {
        $client = [System.Net.Sockets.TcpClient]::new()
        try {
            $task = $client.ConnectAsync($address, $TargetPort)
            $probes.Add([pscustomobject]@{
                Address = $address
                Client  = $client
                Task    = $task
            })
        }
        catch {
            $client.Dispose()
        }
    }

    Start-Sleep -Milliseconds $TimeoutMs

    $open = [System.Collections.Generic.List[string]]::new()
    foreach ($probe in $probes) {
        try {
            if ($probe.Task.IsCompleted -and -not $probe.Task.IsFaulted -and $probe.Client.Connected) {
                $open.Add($probe.Address)
            }
        }
        finally {
            $probe.Client.Dispose()
        }
    }

    return @($open)
}

function New-WebDavHttpClient {
    param(
        [Parameter(Mandatory)][System.Management.Automation.PSCredential] $AuthCredential,
        [Parameter(Mandatory)][int] $TimeoutSeconds
    )

    Add-Type -AssemblyName System.Net.Http

    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.AllowAutoRedirect = $false
    $handler.PreAuthenticate = $true
    $handler.Credentials = $AuthCredential.GetNetworkCredential()

    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)
    $client.DefaultRequestHeaders.UserAgent.ParseAdd('RoundSync-Windows-Mounter/1.0')
    return $client
}

function Test-WebDavEndpoint {
    param(
        [Parameter(Mandatory)][uri] $Uri,
        [Parameter(Mandatory)][System.Management.Automation.PSCredential] $AuthCredential,
        [Parameter(Mandatory)][int] $TimeoutSeconds
    )

    $client = New-WebDavHttpClient -AuthCredential $AuthCredential -TimeoutSeconds $TimeoutSeconds
    try {
        try {
            $optionsRequest = [System.Net.Http.HttpRequestMessage]::new(
                [System.Net.Http.HttpMethod]::new('OPTIONS'),
                $Uri
            )
            $optionsResponse = $client.SendAsync($optionsRequest).GetAwaiter().GetResult()
            try {
                if ($optionsResponse.Headers.Contains('DAV')) {
                    return $true
                }

                $allowValues = $null
                if (
                    $optionsResponse.Headers.TryGetValues('Allow', [ref] $allowValues) -and
                    (($allowValues -join ',') -match '(^|,|\s)PROPFIND($|,|\s)')
                ) {
                    return $true
                }
            }
            finally {
                $optionsResponse.Dispose()
                $optionsRequest.Dispose()
            }
        }
        catch {
            Write-Verbose "OPTIONS $Uri failed: $($_.Exception.Message)"
        }

        try {
            $propfindRequest = [System.Net.Http.HttpRequestMessage]::new(
                [System.Net.Http.HttpMethod]::new('PROPFIND'),
                $Uri
            )
            $propfindRequest.Headers.TryAddWithoutValidation('Depth', '0') | Out-Null
            $propfindRequest.Content = [System.Net.Http.StringContent]::new(
                '<?xml version="1.0" encoding="utf-8"?><propfind xmlns="DAV:"><prop><resourcetype/></prop></propfind>',
                [System.Text.Encoding]::UTF8,
                'application/xml'
            )

            $propfindResponse = $client.SendAsync($propfindRequest).GetAwaiter().GetResult()
            try {
                return (
                    [int] $propfindResponse.StatusCode -eq 207 -or
                    $propfindResponse.Headers.Contains('DAV')
                )
            }
            finally {
                $propfindResponse.Dispose()
                $propfindRequest.Dispose()
            }
        }
        catch {
            Write-Verbose "PROPFIND $Uri failed: $($_.Exception.Message)"
            return $false
        }
    }
    finally {
        $client.Dispose()
    }
}

function Ensure-TrailingSlashUri {
    param([Parameter(Mandatory)][uri] $Uri)

    $builder = [System.UriBuilder]::new($Uri)
    if ([string]::IsNullOrEmpty($builder.Path)) {
        $builder.Path = '/'
    }
    elseif (-not $builder.Path.EndsWith('/')) {
        $builder.Path += '/'
    }

    return $builder.Uri
}

function Select-DiscoveredServer {
    param([Parameter(Mandatory)][uri[]] $Candidates)

    if ($Candidates.Count -eq 1) {
        return $Candidates[0]
    }

    if ($NonInteractive) {
        throw "Wykryto wiele serwerów WebDAV ($($Candidates.Count)). Podaj jednoznaczny -ServerUri."
    }

    Write-Host ''
    Write-Host 'Wykryte serwery WebDAV:'
    for ($index = 0; $index -lt $Candidates.Count; $index++) {
        Write-Host ("  [{0}] {1}" -f ($index + 1), $Candidates[$index].AbsoluteUri)
    }

    while ($true) {
        $selection = Read-Host "Wybierz serwer [1-$($Candidates.Count)]"
        $parsed = 0
        if ([int]::TryParse($selection, [ref] $parsed) -and $parsed -ge 1 -and $parsed -le $Candidates.Count) {
            return $Candidates[$parsed - 1]
        }
    }
}

function Discover-WebDavServer {
    param(
        [Parameter(Mandatory)][int] $TargetPort,
        [Parameter(Mandatory)][System.Management.Automation.PSCredential] $AuthCredential
    )

    Write-Step "Wykrywanie urządzeń w aktywnych sieciach prywatnych..."
    $addresses = @(Get-DiscoveryAddresses)
    Write-Verbose "Discovery targets: $($addresses.Count)"

    $openAddresses = @(
        Find-OpenTcpEndpoints -Addresses $addresses -TargetPort $TargetPort -TimeoutMs $ConnectTimeoutMs
    )

    if ($openAddresses.Count -eq 0) {
        throw "Nie znaleziono hosta z otwartym portem TCP $TargetPort."
    }

    Write-Step "Weryfikacja WebDAV na $($openAddresses.Count) hostach..."
    $verified = [System.Collections.Generic.List[uri]]::new()

    foreach ($address in $openAddresses) {
        $candidate = [uri] "http://$address`:$TargetPort/"
        if (Test-WebDavEndpoint -Uri $candidate -AuthCredential $AuthCredential -TimeoutSeconds $HttpTimeoutSeconds) {
            $verified.Add($candidate)
        }
    }

    if ($verified.Count -eq 0) {
        throw "Port $TargetPort jest osiągalny, ale nie znaleziono zgodnego serwera WebDAV. Sprawdź tryb Streaming/WebDAV i poświadczenia."
    }

    return Select-DiscoveredServer -Candidates @($verified)
}

function Ensure-WebClientConfiguration {
    param([Parameter(Mandatory)][uri] $Uri)

    if (-not (Test-IsAdministrator)) {
        throw 'Operacja wymaga uprawnień administratora. Uruchom plik CMD albo PowerShell jako Administrator.'
    }

    $registryPath = 'HKLM:\SYSTEM\CurrentControlSet\Services\WebClient\Parameters'
    $configurationChanged = $false

    if ($Uri.Scheme -eq 'http') {
        Write-Warning 'Połączenie używa HTTP. Uwierzytelnianie Basic przesyła dane logowania bez szyfrowania; używaj wyłącznie zaufanej sieci LAN.'
        $registryItem = Get-ItemProperty -Path $registryPath -Name BasicAuthLevel -ErrorAction SilentlyContinue
        $currentValue = if ($null -ne $registryItem -and $registryItem.PSObject.Properties['BasicAuthLevel']) {
            [int] $registryItem.BasicAuthLevel
        }
        else {
            $null
        }

        if ($currentValue -ne 2) {
            Write-Step 'Włączanie Basic Auth dla HTTP WebDAV...'
            New-ItemProperty -Path $registryPath -Name BasicAuthLevel -PropertyType DWord -Value 2 -Force | Out-Null
            $configurationChanged = $true
        }
    }

    $service = Get-CimInstance -ClassName Win32_Service -Filter "Name='WebClient'"
    if ($null -eq $service) {
        throw 'Usługa systemowa WebClient nie jest dostępna.'
    }

    if ($service.StartMode -eq 'Disabled') {
        Write-Step 'Ustawianie trybu uruchamiania usługi WebClient na Manual...'
        Set-Service -Name WebClient -StartupType Manual
        $configurationChanged = $true
    }

    $serviceStatus = (Get-Service -Name WebClient).Status
    if ($configurationChanged -and $serviceStatus -eq [System.ServiceProcess.ServiceControllerStatus]::Running) {
        Write-Step 'Restartowanie usługi WebClient...'
        Restart-Service -Name WebClient -Force
    }
    elseif ($serviceStatus -ne [System.ServiceProcess.ServiceControllerStatus]::Running) {
        Write-Step 'Uruchamianie usługi WebClient...'
        Start-Service -Name WebClient
    }
}

function Ensure-NetworkNativeMethods {
    if ('RoundSync.NativeMethods' -as [type]) {
        return
    }

    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;

namespace RoundSync
{
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct NETRESOURCE
    {
        public int dwScope;
        public int dwType;
        public int dwDisplayType;
        public int dwUsage;
        public string lpLocalName;
        public string lpRemoteName;
        public string lpComment;
        public string lpProvider;
    }

    public static class NativeMethods
    {
        [DllImport("mpr.dll", CharSet = CharSet.Unicode)]
        public static extern int WNetAddConnection2(
            ref NETRESOURCE netResource,
            string password,
            string username,
            int flags);

        [DllImport("mpr.dll", CharSet = CharSet.Unicode)]
        public static extern int WNetCancelConnection2(
            string name,
            int flags,
            bool force);

        [DllImport("mpr.dll", CharSet = CharSet.Unicode)]
        public static extern int WNetGetConnection(
            string localName,
            StringBuilder remoteName,
            ref int length);
    }
}
'@
}

function Get-NetworkRemoteName {
    param([Parameter(Mandatory)][string] $LocalName)

    Ensure-NetworkNativeMethods

    $capacity = 2048
    $buffer = [System.Text.StringBuilder]::new($capacity)
    $result = [RoundSync.NativeMethods]::WNetGetConnection($LocalName, $buffer, [ref] $capacity)

    switch ($result) {
        0 { return $buffer.ToString() }
        2250 { return $null } # ERROR_NOT_CONNECTED
        default {
            throw [System.ComponentModel.Win32Exception]::new($result)
        }
    }
}

function Find-ExistingDriveForRemote {
    param([Parameter(Mandatory)][uri] $RemoteUri)

    for ($code = [int][char]'Z'; $code -ge [int][char]'D'; $code--) {
        $candidate = "$([char]$code):"
        try {
            $remoteName = Get-NetworkRemoteName -LocalName $candidate
            if (
                $remoteName -and
                $remoteName.TrimEnd('/') -eq $RemoteUri.AbsoluteUri.TrimEnd('/')
            ) {
                return $candidate
            }
        }
        catch {
            Write-Verbose "Cannot inspect ${candidate}: $($_.Exception.Message)"
        }
    }

    return $null
}

function Remove-NetworkDrive {
    param([Parameter(Mandatory)][string] $LocalName)

    Ensure-NetworkNativeMethods

    $result = [RoundSync.NativeMethods]::WNetCancelConnection2($LocalName, 1, $true)
    if ($result -notin @(0, 2250)) {
        throw [System.ComponentModel.Win32Exception]::new($result)
    }
}

function Add-NetworkDrive {
    param(
        [Parameter(Mandatory)][string] $LocalName,
        [Parameter(Mandatory)][uri] $RemoteUri,
        [Parameter(Mandatory)][System.Management.Automation.PSCredential] $AuthCredential,
        [Parameter(Mandatory)][bool] $Persist
    )

    Ensure-NetworkNativeMethods

    $resource = [RoundSync.NETRESOURCE]::new()
    $resource.dwType = 1 # RESOURCETYPE_DISK
    $resource.lpLocalName = $LocalName
    $resource.lpRemoteName = $RemoteUri.AbsoluteUri

    $networkCredential = $AuthCredential.GetNetworkCredential()
    $flags = if ($Persist) { 1 } else { 0 } # CONNECT_UPDATE_PROFILE

    try {
        $result = [RoundSync.NativeMethods]::WNetAddConnection2(
            [ref] $resource,
            $networkCredential.Password,
            $AuthCredential.UserName,
            $flags
        )
    }
    finally {
        $networkCredential.Password = ''
    }

    if ($result -ne 0) {
        throw [System.ComponentModel.Win32Exception]::new(
            $result,
            "Nie udało się zamontować $($RemoteUri.AbsoluteUri) jako $LocalName"
        )
    }
}

try {
    Write-Step 'Round Sync WebDAV Drive Mounter'

    if ($null -eq $Credential) {
        if ($NonInteractive) {
            throw 'W trybie -NonInteractive wymagany jest parametr -Credential.'
        }
        $Credential = Get-Credential -Message 'Podaj dane logowania serwera WebDAV'
    }

    $resolvedUri = if ($null -ne $ServerUri) {
        Ensure-TrailingSlashUri -Uri $ServerUri
    }
    else {
        Discover-WebDavServer -TargetPort $Port -AuthCredential $Credential
    }

    if ($resolvedUri.Scheme -notin @('http', 'https')) {
        throw "Nieobsługiwany schemat URI '$($resolvedUri.Scheme)'. Dozwolone: http, https."
    }

    Write-Step "Wybrany serwer: $($resolvedUri.AbsoluteUri)"
    Ensure-WebClientConfiguration -Uri $resolvedUri

    if ([string]::IsNullOrWhiteSpace($DriveLetter)) {
        $alreadyMountedAs = Find-ExistingDriveForRemote -RemoteUri $resolvedUri
        if ($alreadyMountedAs) {
            Write-Step "$($resolvedUri.AbsoluteUri) jest już zamontowany jako $alreadyMountedAs."
            exit 0
        }
    }

    $resolvedDriveLetter = Resolve-DriveLetter -RequestedLetter $DriveLetter
    $existingRemote = Get-NetworkRemoteName -LocalName $resolvedDriveLetter

    if ($existingRemote) {
        if ($existingRemote.TrimEnd('/') -eq $resolvedUri.AbsoluteUri.TrimEnd('/')) {
            Write-Step "$resolvedDriveLetter jest już poprawnie zamontowany."
            exit 0
        }

        if (-not $Force) {
            throw "$resolvedDriveLetter jest już mapowaniem '$existingRemote'. Użyj innej litery albo -Force."
        }

        Write-Step "Usuwanie istniejącego mapowania $resolvedDriveLetter..."
        Remove-NetworkDrive -LocalName $resolvedDriveLetter
    }
    elseif ((Get-UsedDriveLetters).Contains($resolvedDriveLetter)) {
        throw "$resolvedDriveLetter jest zajęty przez dysk lokalny lub inne urządzenie i nie może zostać zastąpiony."
    }

    Write-Step "Montowanie $($resolvedUri.AbsoluteUri) jako $resolvedDriveLetter..."
    Add-NetworkDrive `
        -LocalName $resolvedDriveLetter `
        -RemoteUri $resolvedUri `
        -AuthCredential $Credential `
        -Persist (-not $Temporary)

    $mountedRemote = Get-NetworkRemoteName -LocalName $resolvedDriveLetter
    if (-not $mountedRemote) {
        throw 'System nie potwierdził utworzonego mapowania.'
    }

    Write-Host ''
    Write-Host 'Sukces.'
    Write-Host "Dysk:      $resolvedDriveLetter"
    Write-Host "Serwer:    $mountedRemote"
    Write-Host "Trwałość:  $(if ($Temporary) { 'do wylogowania/restartu' } else { 'persistent' })"
    exit 0
}
catch {
    Write-Error -ErrorRecord $_ -ErrorAction Continue
    exit 1
}
