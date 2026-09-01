[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string] $Command = "HEALTH",
    [Parameter(Position = 1)]
    [string] $Arguments = "{}",
    [string] $Serial = "ZY22HDLNVF",
    [int] $Port = 43137,
    [int] $TimeoutSeconds = 900,
    [string] $OutputDirectory
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$androidUserHome = Join-Path $projectRoot ".android-codex"
$packageName = "com.rpgos.app"
$normalizedCommand = $Command.Trim().ToUpperInvariant()

if ([string]::IsNullOrWhiteSpace($env:ANDROID_USER_HOME)) {
    $env:ANDROID_USER_HOME = $androidUserHome
}
if ([string]::IsNullOrWhiteSpace($env:ANDROID_SDK_HOME)) {
    $env:ANDROID_SDK_HOME = Split-Path -Parent $androidUserHome
}

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $adb)) {
    throw "Nie znaleziono ADB: $adb"
}

function Assert-AdbSuccess([string] $Message) {
    if ($LASTEXITCODE -ne 0) {
        throw $Message
    }
}

function Initialize-LabForward {
    $null = & $adb -s $Serial forward "tcp:$Port" "localabstract:rpgos_lab_bridge"
    Assert-AdbSuccess "Nie udało się utworzyć połączenia ADB z bridge'em."
}

function Invoke-LabBridge([string] $BridgeCommand, [object] $BridgeArguments) {
    Initialize-LabForward
    $request = [ordered]@{
        protocol = "RPGOS_LAB_V1"
        request_uid = "LAB:$([Guid]::NewGuid().ToString())"
        command = $BridgeCommand.ToUpperInvariant()
        arguments = $BridgeArguments
    }
    $wire = $request | ConvertTo-Json -Depth 40 -Compress
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $client.ReceiveTimeout = $TimeoutSeconds * 1000
        $client.SendTimeout = 30000
        $client.Connect("127.0.0.1", $Port)
        $stream = $client.GetStream()
        $writer = [System.IO.StreamWriter]::new($stream, [System.Text.UTF8Encoding]::new($false), 4096, $true)
        $reader = [System.IO.StreamReader]::new($stream, [System.Text.UTF8Encoding]::new($false), $false, 4096, $true)
        $writer.NewLine = "`n"
        $writer.WriteLine($wire)
        $writer.Flush()
        $line = $reader.ReadLine()
        if ([string]::IsNullOrWhiteSpace($line)) {
            throw "Bridge zamknął połączenie bez odpowiedzi."
        }
        return $line | ConvertFrom-Json
    }
    finally {
        $client.Dispose()
    }
}

function Resolve-ArtifactDirectory([string] $Kind) {
    if (-not [string]::IsNullOrWhiteSpace($OutputDirectory)) {
        $resolved = [System.IO.Path]::GetFullPath($OutputDirectory)
    }
    else {
        $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
        $resolved = Join-Path $projectRoot "build\lab-artifacts\$Kind-$stamp"
    }
    $null = New-Item -ItemType Directory -Path $resolved -Force
    return $resolved
}

function Save-AdbScreenshot([string] $Path) {
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $adb
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.ArgumentList.Add("-s")
    $startInfo.ArgumentList.Add($Serial)
    $startInfo.ArgumentList.Add("exec-out")
    $startInfo.ArgumentList.Add("screencap")
    $startInfo.ArgumentList.Add("-p")
    $process = [System.Diagnostics.Process]::Start($startInfo)
    try {
        $file = [System.IO.File]::Open($Path, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write)
        try {
            $process.StandardOutput.BaseStream.CopyTo($file)
        }
        finally {
            $file.Dispose()
        }
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            throw "Nie udało się wykonać zrzutu ekranu: $($process.StandardError.ReadToEnd())"
        }
    }
    finally {
        $process.Dispose()
    }
}

switch ($normalizedCommand) {
    "HOST_HELP" {
        [ordered]@{
            bridge_stage = 3
            host_commands = @("HOST_HELP", "HOST_RESTART", "HOST_SCREENSHOT", "HOST_FAILURE_BUNDLE", "HOST_CODEX_START", "HOST_CODEX_STOP", "HOST_CODEX_STATUS")
            bridge_hint = ".\tools\rpgos-lab.ps1 GET_CAPABILITIES"
        } | ConvertTo-Json -Depth 10
        return
    }
    "HOST_CODEX_START" {
        & (Join-Path $PSScriptRoot "rpgos-codex-host.ps1") -Mode Start -Serial $Serial -Port $Port
        return
    }
    "HOST_CODEX_STOP" {
        & (Join-Path $PSScriptRoot "rpgos-codex-host.ps1") -Mode Stop -Serial $Serial -Port $Port
        return
    }
    "HOST_CODEX_STATUS" {
        & (Join-Path $PSScriptRoot "rpgos-codex-host.ps1") -Mode Status -Serial $Serial -Port $Port
        return
    }
    "HOST_RESTART" {
        $null = & $adb -s $Serial shell am force-stop $packageName
        Assert-AdbSuccess "Nie udało się zatrzymać aplikacji."
        $null = & $adb -s $Serial shell monkey -p $packageName -c android.intent.category.LAUNCHER 1
        Assert-AdbSuccess "Nie udało się uruchomić aplikacji."
        Start-Sleep -Milliseconds 1500
        Invoke-LabBridge "HEALTH" @{} | ConvertTo-Json -Depth 40
        return
    }
    "HOST_SCREENSHOT" {
        $directory = Resolve-ArtifactDirectory "screenshot"
        $path = Join-Path $directory "screen.png"
        Save-AdbScreenshot $path
        [ordered]@{
            state = "SUCCESS"
            screenshot = $path
            byte_size = (Get-Item -LiteralPath $path).Length
        } | ConvertTo-Json -Depth 10
        return
    }
    "HOST_FAILURE_BUNDLE" {
        $directory = Resolve-ArtifactDirectory "failure"
        $parsedArguments = $Arguments | ConvertFrom-Json
        $bridgeBundle = Invoke-LabBridge "EXPORT_FAILURE_BUNDLE" $parsedArguments
        [System.IO.File]::WriteAllText(
            (Join-Path $directory "bridge.json"),
            ($bridgeBundle | ConvertTo-Json -Depth 60),
            [System.Text.UTF8Encoding]::new($false)
        )
        $logcat = & $adb -s $Serial logcat -d -t 4000
        [System.IO.File]::WriteAllLines((Join-Path $directory "logcat.txt"), [string[]] $logcat, [System.Text.UTF8Encoding]::new($false))
        $meminfo = & $adb -s $Serial shell dumpsys meminfo $packageName
        [System.IO.File]::WriteAllLines((Join-Path $directory "meminfo.txt"), [string[]] $meminfo, [System.Text.UTF8Encoding]::new($false))
        $properties = & $adb -s $Serial shell getprop
        [System.IO.File]::WriteAllLines((Join-Path $directory "device-properties.txt"), [string[]] $properties, [System.Text.UTF8Encoding]::new($false))
        Save-AdbScreenshot (Join-Path $directory "screen.png")
        [ordered]@{
            state = "SUCCESS"
            directory = $directory
            files = @(Get-ChildItem -LiteralPath $directory -File | Sort-Object Name | ForEach-Object {
                [ordered]@{ name = $_.Name; byte_size = $_.Length }
            })
        } | ConvertTo-Json -Depth 20
        return
    }
}

$parsedArguments = $Arguments | ConvertFrom-Json
Invoke-LabBridge $normalizedCommand $parsedArguments | ConvertTo-Json -Depth 60
