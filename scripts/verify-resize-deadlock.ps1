param(
    [ValidateSet('Target', 'Full', 'Package', 'Compile', 'Probe', 'Hang')]
    [string]$Mode = 'Target',
    [ValidateRange(1, 100)][int]$Rounds = 1,
    [string]$JavaHome = 'D:\java\jdk21',
    [string]$Tests = 'ResizeAwareLineReaderTest,ResizeAwareLineReaderConcurrencyTest,FullscreenLifecycleTest,FullscreenBoundaryTest,FullscreenCommandFlowTest,CommandProcessorTest'
)
# Run with PowerShell 7 so Process.Kill(entireProcessTree) is available.
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 is required' }
$repo = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;$env:PATH"
$env:MAVEN_OPTS = '-Xmx256m -XX:ActiveProcessorCount=2'
$logRoot = Join-Path $repo ('target/resize-validation/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Force $logRoot | Out-Null
& "$JavaHome/bin/java.exe" -version
$maven = (Get-Command mvn.cmd).Source
for ($round = 1; $round -le $Rounds; $round++) {
    $prefix = Join-Path $logRoot "$Mode-$round"
    $limit = if ($Mode -in @('Full', 'Package')) { 900 } elseif ($Mode -in @('Probe', 'Hang')) { 10 } else { 180 }
    $arguments = @('-q', '"-DargLine=-Xmx384m -XX:ActiveProcessorCount=2"', '"-Dsurefire.exitTimeout=10"')
    $executable = $maven
    switch ($Mode) {
        'Target' { $arguments += @('test', "-Dtest=$Tests", '-Dsurefire.timeout=150') }
        'Full' { $arguments += @('test', '-Dsurefire.timeout=850') }
        'Package' { $arguments += @('package', '-DskipTests') }
        'Compile' { $arguments += @('test-compile', '-DskipTests') }
        { $_ -in @('Probe', 'Hang') } {
            $classpath = "$(Join-Path $repo 'target/test-classes');$(Join-Path $repo 'target/classes');$env:USERPROFILE/.m2/repository/org/jline/jline/3.30.16/jline-3.30.16.jar"
            $executable = "$JavaHome/bin/java.exe"
            $arguments = @('-Xmx128m', '-XX:ActiveProcessorCount=2', '-cp', "`"$classpath`"", 'com.acode.ui.ResizeDeadlockProbe')
            if ($Mode -eq 'Hang') { $arguments += 'hang' }
        }
    }
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    $startedAt = [DateTime]::UtcNow
    $process = Start-Process -FilePath $executable -ArgumentList $arguments -WorkingDirectory $repo -WindowStyle Hidden -PassThru -RedirectStandardOutput "$prefix.out.log" -RedirectStandardError "$prefix.err.log"
    Write-Output "$Mode round=$round pid=$($process.Id) limit=${limit}s logs=$prefix"
    if (-not $process.WaitForExit($limit * 1000)) {
        "TIMEOUT mode=$Mode round=$round pid=$($process.Id) logs=$prefix" | Tee-Object -FilePath "$prefix.timeout.txt"
        # Bounded diagnostics for Java children belonging to this invocation only.
        try {
            $all = @(Get-CimInstance Win32_Process -OperationTimeoutSec 2)
            $ids = [System.Collections.Generic.HashSet[int]]::new()
            [void]$ids.Add($process.Id)
            do {
                $count = $ids.Count
                foreach ($p in $all) { if ($ids.Contains([int]$p.ParentProcessId)) { [void]$ids.Add([int]$p.ProcessId) } }
            } while ($ids.Count -gt $count)
            $javaChild = $all | Where-Object { $ids.Contains([int]$_.ProcessId) -and $_.Name -eq 'java.exe' } | Select-Object -Last 1
            if ($javaChild) {
                $dumpPath = "$prefix.threads.json"
                $dump = Start-Process "$JavaHome/bin/jcmd.exe" -ArgumentList @($javaChild.ProcessId, 'Thread.dump_to_file', '-format=json', "`"$dumpPath`"") -WindowStyle Hidden -PassThru -RedirectStandardOutput "$prefix.jcmd.log" -RedirectStandardError "$prefix.jcmd.err.log"
                if (-not $dump.WaitForExit(5000)) { $dump.Kill($true); [void]$dump.WaitForExit(1000) }
            } else { 'No Java child found for diagnostics' | Set-Content "$prefix.diagnostic-error.txt" }
        } catch { $_.ToString() | Set-Content "$prefix.diagnostic-error.txt" }
        if (-not $process.HasExited) { $process.Kill($true) }
        if (-not $process.WaitForExit(10000)) { throw "Unable to stop owned process $($process.Id)" }
        "TIMEOUT_CLEANED pid=$($process.Id) seconds=$([math]::Round($timer.Elapsed.TotalSeconds, 2))" | Tee-Object -FilePath "$prefix.timeout.txt" -Append
        exit 124
    }
    $process.Refresh()
    Write-Output "$Mode round=$round exit=$($process.ExitCode) seconds=$([math]::Round($timer.Elapsed.TotalSeconds, 2))"
    if ($Mode -in @('Target', 'Full')) {
        $reports = @(Get-ChildItem (Join-Path $repo 'target/surefire-reports/TEST-*.xml') |
            Where-Object { $_.LastWriteTimeUtc -ge $startedAt })
        $summary = [ordered]@{ mode = $Mode; round = $round; exit = $process.ExitCode; seconds = $timer.Elapsed.TotalSeconds; tests = 0; failures = 0; errors = 0; skipped = 0 }
        foreach ($report in $reports) {
            [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw
            foreach ($field in @('tests', 'failures', 'errors', 'skipped')) { $summary[$field] += [int]$xml.testsuite.GetAttribute($field) }
            Copy-Item -LiteralPath $report.FullName -Destination (Join-Path $logRoot "$round-$($report.Name)")
        }
        $summary | ConvertTo-Json | Set-Content "$prefix.summary.json"
        Write-Output "tests=$($summary.tests) failures=$($summary.failures) errors=$($summary.errors) skipped=$($summary.skipped)"
    }
    if ($process.ExitCode -ne 0) {
        Get-Content "$prefix.out.log", "$prefix.err.log" -Tail 70
        exit $process.ExitCode
    }
}
