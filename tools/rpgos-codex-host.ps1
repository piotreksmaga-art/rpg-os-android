[CmdletBinding()]
param(
    [ValidateSet("Start", "Stop", "Status", "Supervisor", "Worker")]
    [string] $Mode = "Start",
    [ValidateSet("GAME_MASTER", "DIRECTOR")]
    [string] $Lane = "GAME_MASTER",
    [string] $Serial = "ZY22HDLNVF",
    [int] $Port = 43137,
    [string] $SessionUid,
    [string] $Model = "gpt-5.6-sol",
    [ValidateSet("QUALITY", "STRESS")]
    [string] $ExecutionProfile = "QUALITY"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$labClient = Join-Path $PSScriptRoot "rpgos-lab.ps1"
$stateRoot = Join-Path $projectRoot "build\lab-codex-host"
$stateFile = Join-Path $stateRoot "host-state.json"
$logRoot = Join-Path $stateRoot "logs"
$workerRoot = Join-Path $env:TEMP "RpgOsCodexHost"
$currentScript = $MyInvocation.MyCommand.Path

function ConvertTo-HashtableDeep([object] $Value) {
    if ($null -eq $Value) { return $null }
    if ($Value -is [System.Management.Automation.PSCustomObject]) {
        $result = [ordered]@{}
        foreach ($property in $Value.PSObject.Properties) {
            $result[$property.Name] = ConvertTo-HashtableDeep $property.Value
        }
        return $result
    }
    if ($Value -is [System.Collections.IEnumerable] -and $Value -isnot [string]) {
        # PowerShell normally enumerates a function's array result.  Without the unary comma a
        # JSON array containing exactly one value crosses the lab bridge as that scalar value
        # (for example ["AREA"] became "AREA"), even though Codex satisfied the output schema.
        # Keep the array as one pipeline object so ConvertTo-Json preserves its wire shape.
        return ,@($Value | ForEach-Object { ConvertTo-HashtableDeep $_ })
    }
    return $Value
}

function Invoke-Lab([string] $Command, [object] $Arguments) {
    $json = $Arguments | ConvertTo-Json -Depth 100 -Compress
    $raw = & $labClient $Command $json -Serial $Serial -Port $Port -TimeoutSeconds 330
    if ($LASTEXITCODE -ne 0) { throw "LAB_CLIENT_FAILED:$Command" }
    $response = $raw | ConvertFrom-Json
    if ($response.state -ne "SUCCESS") { throw [string] $response.reason_uid }
    return $response.payload
}

function Read-State {
    if (-not (Test-Path -LiteralPath $stateFile)) { return $null }
    return Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
}

function Test-ProcessAlive([int] $ProcessId) {
    if ($ProcessId -le 0) { return $false }
    return $null -ne (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)
}

function Start-HiddenPowerShell([string[]] $Arguments, [string] $StdoutPath, [string] $StderrPath) {
    $executable = (Get-Process -Id $PID).Path
    return Start-Process -FilePath $executable -ArgumentList $Arguments -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $StdoutPath -RedirectStandardError $StderrPath
}

function Write-HostState([object] $State) {
    $null = New-Item -ItemType Directory -Path $stateRoot -Force
    [System.IO.File]::WriteAllText(
        $stateFile,
        ($State | ConvertTo-Json -Depth 20),
        [System.Text.UTF8Encoding]::new($false)
    )
}

function Stop-LabHostProcesses {
    $escapedScript = [Regex]::Escape($currentScript)
    $processes = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
        $_.ProcessId -ne $PID -and
        -not [string]::IsNullOrWhiteSpace($_.CommandLine) -and
        $_.CommandLine -match $escapedScript
    }
    foreach ($process in $processes) {
        Stop-Process -Id ([int] $process.ProcessId) -Force -ErrorAction SilentlyContinue
    }
}

function Get-ReasoningEffort([string] $Workload) {
    # QUALITY is used for player-facing acceptance. STRESS exercises the same schemas,
    # provider queues and production Core path with cheaper reasoning for long runs.
    if ($ExecutionProfile -eq "STRESS") { return "low" }
    if ($Workload -in @("INTENT_INTERPRETATION", "NARRATIVE_RENDER")) { return "medium" }
    return "high"
}

function New-CodexPrompt([object] $Claim) {
    $payloadJson = $Claim.request_payload | ConvertTo-Json -Depth 100 -Compress
    return @"
Jesteś bezstanowym dostawcą AI wewnątrz laboratoryjnej wersji RPG OS.
Wykonaj wyłącznie zadanie opisane w poniższym autoryzowanym żądaniu.
Nie używaj narzędzi, nie czytaj plików, nie przeszukuj Internetu i nie odwołuj się do repozytorium ani historii czatu.
Core RPG OS pozostaje jedynym źródłem prawdy. Nie wymyślaj wykonanych commitów ani skutków mechanicznych poza formatem żądania.
Zwróć wyłącznie jeden obiekt JSON zgodny z przekazanym schematem odpowiedzi. Bez Markdownu, komentarzy i tekstu poza JSON.

WORKLOAD: $($Claim.workload)
REQUEST_UID: $($Claim.ai_request_uid)
AUTHORIZED_REQUEST_JSON:
$payloadJson
"@
}

function Invoke-CodexRequest([object] $Claim) {
    $requestUid = [string] $Claim.ai_request_uid
    $safeUid = $requestUid -replace '[^A-Za-z0-9._-]', '_'
    $safeSessionUid = $SessionUid -replace '[^A-Za-z0-9._-]', '_'
    $requestDirectory = Join-Path (Join-Path (Join-Path $workerRoot $safeSessionUid) $Lane) $safeUid
    $null = New-Item -ItemType Directory -Path $requestDirectory -Force
    $schemaPath = Join-Path $requestDirectory "output-schema.json"
    $outputPath = Join-Path $requestDirectory "output.json"
    $stdoutPath = Join-Path $requestDirectory "codex.stdout.log"
    $stderrPath = Join-Path $requestDirectory "codex.stderr.log"
    [System.IO.File]::WriteAllText($schemaPath, ($Claim.output_schema | ConvertTo-Json -Depth 100 -Compress), [System.Text.UTF8Encoding]::new($false))

    $codex = (Get-Command codex -ErrorAction Stop).Source
    $effort = Get-ReasoningEffort ([string] $Claim.workload)
    $arguments = @(
        "exec", "--ephemeral", "--skip-git-repo-check", "--ignore-user-config", "--ignore-rules",
        "--sandbox", "read-only", "--color", "never", "--model", $Model,
        "--cd", $requestDirectory, "--output-schema", $schemaPath, "--output-last-message", $outputPath,
        "-c", "model_reasoning_effort=`"$effort`"", "-c", "approval_policy=`"never`"", "-"
    )
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $codex
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $utf8 = [System.Text.UTF8Encoding]::new($false)
    $startInfo.StandardInputEncoding = $utf8
    $startInfo.StandardOutputEncoding = $utf8
    $startInfo.StandardErrorEncoding = $utf8
    foreach ($argument in $arguments) { $startInfo.ArgumentList.Add($argument) }
    $process = [System.Diagnostics.Process]::Start($startInfo)
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $prompt = New-CodexPrompt $Claim
    $process.StandardInput.Write($prompt)
    $process.StandardInput.Close()

    $remainingMillis = [Math]::Max(1000L, ([long] $Claim.deadline_at_epoch_ms - [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()))
    if (-not $process.WaitForExit([int] [Math]::Min($remainingMillis, [int]::MaxValue))) {
        $process.Kill($true)
        $process.WaitForExit()
        throw "LAB_CODEX_PROCESS_TIMEOUT"
    }
    [System.IO.File]::WriteAllText($stdoutPath, $stdoutTask.GetAwaiter().GetResult(), [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::WriteAllText($stderrPath, $stderrTask.GetAwaiter().GetResult(), [System.Text.UTF8Encoding]::new($false))
    if ($process.ExitCode -ne 0) { throw "LAB_CODEX_EXEC_EXIT_$($process.ExitCode)" }
    if (-not (Test-Path -LiteralPath $outputPath)) { throw "LAB_CODEX_OUTPUT_MISSING" }
    $outputText = Get-Content -LiteralPath $outputPath -Raw
    try { return $outputText | ConvertFrom-Json }
    catch { throw "LAB_CODEX_OUTPUT_NOT_JSON:$($_.Exception.Message)" }
}

function Run-Worker {
    while ($true) {
        try {
            $claim = Invoke-Lab "CLAIM_AI_REQUEST" ([ordered]@{ session_uid = $SessionUid; lane = $Lane; wait_ms = 10000 })
            if (-not $claim.available) { continue }
            try {
                $structured = Invoke-CodexRequest $claim
                $null = Invoke-Lab "COMPLETE_AI_REQUEST" ([ordered]@{
                    session_uid = $SessionUid
                    ai_request_uid = [string] $claim.ai_request_uid
                    trace_uid = "CODEX-EXEC:$([Guid]::NewGuid())"
                    structured_payload = ConvertTo-HashtableDeep $structured
                })
            }
            catch {
                $reason = ("LAB_CODEX_WORKER:" + $_.Exception.Message).Substring(0, [Math]::Min(500, ("LAB_CODEX_WORKER:" + $_.Exception.Message).Length))
                try {
                    $null = Invoke-Lab "FAIL_AI_REQUEST" ([ordered]@{
                        session_uid = $SessionUid
                        ai_request_uid = [string] $claim.ai_request_uid
                        reason_uid = $reason
                        retryable = $true
                    })
                }
                catch { Write-Error $_ }
            }
        }
        catch {
            Write-Error $_
            Start-Sleep -Seconds 2
        }
    }
}

function Start-Worker([string] $WorkerLane) {
    $stdout = Join-Path $logRoot "$($WorkerLane.ToLowerInvariant()).stdout.log"
    $stderr = Join-Path $logRoot "$($WorkerLane.ToLowerInvariant()).stderr.log"
    $arguments = @(
        "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $currentScript,
        "-Mode", "Worker", "-Lane", $WorkerLane, "-Serial", $Serial, "-Port", [string] $Port,
        "-SessionUid", $SessionUid, "-Model", $Model, "-ExecutionProfile", $ExecutionProfile
    )
    return Start-HiddenPowerShell $arguments $stdout $stderr
}

function Connect-LabProvider {
    $null = Invoke-Lab "REGISTER_CODEX_HOST" ([ordered]@{ session_uid = $SessionUid; host_uid = "RPGOS_LAB_HOST"; model_uid = $Model })
    $null = Invoke-Lab "SET_LAB_AI_ASSIGNMENTS" ([ordered]@{ game_master = "PINNED"; director = "PINNED" })
}

function Run-Supervisor {
    $null = Get-Command codex -ErrorAction Stop
    $null = New-Item -ItemType Directory -Path $logRoot -Force
    Connect-LabProvider
    $workers = [ordered]@{ GAME_MASTER = Start-Worker "GAME_MASTER"; DIRECTOR = Start-Worker "DIRECTOR" }
    Write-HostState ([ordered]@{
        session_uid = $SessionUid; supervisor_pid = $PID; model = $Model; execution_profile = $ExecutionProfile; serial = $Serial; port = $Port
        game_master_pid = $workers.GAME_MASTER.Id; director_pid = $workers.DIRECTOR.Id
        started_at = [DateTimeOffset]::Now.ToString("o")
    })
    try {
        while ($true) {
            try {
                $null = Invoke-Lab "CODEX_HOST_HEARTBEAT" ([ordered]@{ session_uid = $SessionUid })
            }
            catch {
                [Console]::Error.WriteLine("LAB_CODEX_RECONNECT: $($_.Exception.Message)")
                Start-Sleep -Seconds 2
                try { Connect-LabProvider }
                catch {
                    [Console]::Error.WriteLine("LAB_CODEX_RECONNECT_FAILED: $($_.Exception.Message)")
                    continue
                }
            }
            foreach ($workerLane in @("GAME_MASTER", "DIRECTOR")) {
                if ($workers[$workerLane].HasExited) {
                    $workers[$workerLane] = Start-Worker $workerLane
                    Write-HostState ([ordered]@{
                        session_uid = $SessionUid; supervisor_pid = $PID; model = $Model; execution_profile = $ExecutionProfile; serial = $Serial; port = $Port
                        game_master_pid = $workers.GAME_MASTER.Id; director_pid = $workers.DIRECTOR.Id
                        started_at = [DateTimeOffset]::Now.ToString("o")
                    })
                }
            }
            Start-Sleep -Seconds 5
        }
    }
    finally {
        foreach ($worker in $workers.Values) {
            if (-not $worker.HasExited) { $worker.Kill($true) }
            $worker.Dispose()
        }
    }
}

switch ($Mode) {
    "Start" {
        $existing = Read-State
        if ($null -ne $existing -and (Test-ProcessAlive ([int] $existing.supervisor_pid))) {
            [ordered]@{ state = "ALREADY_RUNNING"; host = $existing } | ConvertTo-Json -Depth 20
            return
        }
        Stop-LabHostProcesses
        $null = New-Item -ItemType Directory -Path $logRoot -Force
        $newSession = "LAB-CODEX-HOST:$([Guid]::NewGuid())"
        $arguments = @(
            "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $currentScript,
            "-Mode", "Supervisor", "-Serial", $Serial, "-Port", [string] $Port,
            "-SessionUid", $newSession, "-Model", $Model, "-ExecutionProfile", $ExecutionProfile
        )
        $process = Start-HiddenPowerShell $arguments (Join-Path $logRoot "supervisor.stdout.log") (Join-Path $logRoot "supervisor.stderr.log")
        Start-Sleep -Seconds 2
        if ($process.HasExited) { throw "LAB_CODEX_SUPERVISOR_START_FAILED; sprawdź $logRoot" }
        [ordered]@{ state = "STARTED"; supervisor_pid = $process.Id; session_uid = $newSession; log_directory = $logRoot } | ConvertTo-Json -Depth 20
        return
    }
    "Stop" {
        $existing = Read-State
        if ($null -eq $existing) { [ordered]@{ state = "NOT_RUNNING" } | ConvertTo-Json; return }
        foreach ($processId in @($existing.game_master_pid, $existing.director_pid, $existing.supervisor_pid)) {
            if (Test-ProcessAlive ([int] $processId)) { Stop-Process -Id ([int] $processId) -Force }
        }
        Stop-LabHostProcesses
        [ordered]@{ state = "STOPPED"; session_uid = $existing.session_uid } | ConvertTo-Json
        return
    }
    "Status" {
        $existing = Read-State
        if ($null -eq $existing) { [ordered]@{ state = "NOT_RUNNING" } | ConvertTo-Json; return }
        [ordered]@{
            state = if (Test-ProcessAlive ([int] $existing.supervisor_pid)) { "RUNNING" } else { "STOPPED" }
            host = $existing
            provider = try { Invoke-Lab "GET_CODEX_PROVIDER_STATE" @{} } catch { [ordered]@{ error = $_.Exception.Message } }
        } | ConvertTo-Json -Depth 40
        return
    }
    "Supervisor" { Run-Supervisor; return }
    "Worker" { Run-Worker; return }
}
