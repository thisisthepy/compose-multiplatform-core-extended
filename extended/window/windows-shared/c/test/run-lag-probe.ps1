<#
.SYNOPSIS
Measures how the Windows window follows a dragged edge on this machine, on both of its routes.

.DESCRIPTION
Builds the window and its resize probe with cl, as the extended-window-windows-shared workflow
does, then drags the probe window's right edge with real input on the DirectComposition route
(DXC_DCOMP=1) and on the GDI route (the default, DXC_DCOMP=0): a drag with frames that cost nothing, one
with 30 ms frames, and one with 5 ms frames drawn ahead of each size. Each run prints, and the
summary file keeps, the faulty frames DWM composed, how far the edge lagged the pointer and
the window's memory at rest.

Then, on both routes, the judged drags (issue 223): the left edge, the top edge and the
top-left corner dragged in and out, and the window grown fast by its right edge, its bottom
edge, its bottom-right corner and its top-left corner, at a real hand's pace. The window's
border is drawn in pure blue (Windows 11 and later), and every composed frame is judged
against it: whether the picture slid (anchor, "shifted by N px") and whether the desktop
showed through inside the border or the picture was drawn for another size (see-through).
The summary file has a second table for them. On Windows 10 they say "not available".

Run it from "x64 Native Tools Command Prompt for VS 2022" (cl has to be on PATH):

    powershell -ExecutionPolicy Bypass -File extended\window\windows-shared\c\test\run-lag-probe.ps1

When win32_resize_probe.exe sits beside this script, as in the workflow's
lag-probe-windows-x64 artifact, it compiles nothing and needs no compiler: double-click
run-lag-probe.cmd, and the logs and lag-probe-summary.txt land in this script's folder.

It moves a window and the pointer on screen: leave the mouse and keyboard alone until it ends.

.PARAMETER Runs
How many times each drag, judged drags included, runs on each route. 3 unless given.

.PARAMETER Fast
One run of each drag: what CI runs, so the script keeps working.
#>
param(
    [int]$Runs = 3,
    [switch]$Fast
)

$ErrorActionPreference = 'Stop'
if ($Fast) { $Runs = 1 }

# A prebuilt probe beside the script (the workflow's artifact) runs as it is.
$prebuilt = Join-Path $PSScriptRoot 'win32_resize_probe.exe'
if (Test-Path $prebuilt) {
    $out = $PSScriptRoot
} else {
    # windows-shared, two folders above this script.
    $root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    $out = Join-Path $root 'out\lag-probe'
    New-Item -ItemType Directory -Force -Path $out | Out-Null

    if (-not (Get-Command cl -ErrorAction SilentlyContinue)) {
        Write-Error 'cl is not on PATH. Run this from "x64 Native Tools Command Prompt for VS 2022".'
        exit 2
    }

    function Invoke-Native([string]$what, [string[]]$arguments) {
        & $what @arguments
        if ($LASTEXITCODE -ne 0) {
            Write-Error "$what failed ($LASTEXITCODE): $($arguments -join ' ')"
            exit 3
        }
    }

    Push-Location $root
    try {
        # The same commands as the workflow, into this script's own folder.
        $o = "out\lag-probe"
        Invoke-Native cl @('/nologo', '/c', '/O2', '/std:c11', '/W3', "/Fo$o\win32_window.obj", 'c\win32_window.c')
        Invoke-Native cl @('/nologo', '/c', '/O2', '/std:c++17', '/GR-', '/EHs-c-', '/W3', "/Fo$o\win32_dcomp.obj", 'c\win32_dcomp.cpp')
        Invoke-Native cl @('/nologo', '/c', '/O2', '/std:c11', '/W3', "/Fo$o\win32_file_dialog.obj", 'c\win32_file_dialog.c')
        Invoke-Native cl @('/nologo', '/c', '/O2', '/std:c11', '/W3', "/Fo$o\win32_notifications.obj", 'c\win32_notifications.c')
        Invoke-Native lib @('/nologo', "/OUT:$o\dxc_win32_window.lib", "$o\win32_window.obj", "$o\win32_dcomp.obj",
            "$o\win32_file_dialog.obj", "$o\win32_notifications.obj")
        Invoke-Native cl @('/nologo', '/std:c11', '/W3', '/Ic', '/Icinterop\include', "/Fo$o\", 'c\test\win32_resize_probe.c',
            "/Fe$o\win32_resize_probe.exe", '/link', "$o\dxc_win32_window.lib", 'd3d12.lib', 'd3d11.lib', 'dxgi.lib',
            'dxguid.lib', 'dcomp.lib', 'dwmapi.lib', 'user32.lib', 'gdi32.lib', 'uiautomationcore.lib', 'oleaut32.lib',
            'imm32.lib', 'runtimeobject.lib', 'ole32.lib', 'shell32.lib', 'advapi32.lib')
    } finally {
        Pop-Location
    }
}

$probe = Join-Path $out 'win32_resize_probe.exe'
$drags = @(
    @{ Name = 'drag'; DrawMs = '' },
    @{ Name = 'drag-30ms'; DrawMs = '30' },
    @{ Name = 'drag245-ahead'; DrawMs = '5' }
)
$routes = @(
    @{ Name = 'dcomp'; Flag = '1' },
    @{ Name = 'gdi'; Flag = '0' }
)

function Find([string[]]$lines, [string]$pattern) {
    foreach ($line in $lines) {
        $match = [regex]::Match($line, $pattern)
        if ($match.Success) { return $match }
    }
    return $null
}

$rows = @()
$unfinished = 0
foreach ($route in $routes) {
    foreach ($drag in $drags) {
        for ($run = 1; $run -le $Runs; $run++) {
            $env:DXC_DCOMP = $route.Flag
            $env:PROBE_DRAW_MS = if ($drag.DrawMs) { $drag.DrawMs } else { $null }
            $log = Join-Path $out ("probe-{0}-{1}-{2}.log" -f $route.Name, $drag.Name, $run)
            Write-Host ("lag probe: {0} {1} run {2} of {3}" -f $route.Name, $drag.Name, $run, $Runs)
            # Through cmd, so the probe's standard error is written to the log as text.
            cmd /c "`"$probe`" drag > `"$log`" 2>&1"
            $lines = Get-Content $log
            $verdict = Find $lines 'probe: verdict'
            $phase = Find $lines 'probe: phase drag composed (\d+) frames, (\d+) faulty'
            $lag = Find $lines 'probe: drag lag moves=\d+ px_median=(\d+) px_p95=(\d+) px_max=(\d+) longest_hold_ms=(\d+)'
            $memory = Find $lines 'metrics memory probe-idle working_set_mb=([0-9.]+) private_mb=([0-9.]+).* route=(\w+)'
            if (-not $verdict) { $unfinished++ }
            $rows += [pscustomobject]@{
                Route = $route.Name
                Drag = $drag.Name
                Run = $run
                Composed = if ($phase) { $phase.Groups[1].Value } else { '-' }
                Faulty = if ($phase) { $phase.Groups[2].Value } else { '-' }
                LagMedianPx = if ($lag) { $lag.Groups[1].Value } else { '-' }
                LagP95Px = if ($lag) { $lag.Groups[2].Value } else { '-' }
                LagMaxPx = if ($lag) { $lag.Groups[3].Value } else { '-' }
                LongestHoldMs = if ($lag) { $lag.Groups[4].Value } else { '-' }
                IdleWorkingSetMb = if ($memory) { $memory.Groups[1].Value } else { '-' }
                IdlePrivateMb = if ($memory) { $memory.Groups[2].Value } else { '-' }
                WindowRoute = if ($memory) { $memory.Groups[3].Value } else { '-' }
                Finished = [bool]$verdict
            }
        }
    }
}
$env:PROBE_DRAW_MS = $null

# The judged drags: the same routes, and the window's per-step lines (DXC_REPORT_LATENCY) carry
# its left and top, the edge dragged and, on DirectComposition, whether the step's frame was
# committed before or after the rectangle.
$judged = @('drag-left', 'drag-top', 'drag-topleft', 'drag-fast')
$judgedRows = @()
foreach ($route in $routes) {
    foreach ($mode in $judged) {
        for ($run = 1; $run -le $Runs; $run++) {
            $env:DXC_DCOMP = $route.Flag
            $env:DXC_REPORT_LATENCY = '1'
            $log = Join-Path $out ("probe-{0}-{1}-{2}.log" -f $route.Name, $mode, $run)
            Write-Host ("lag probe: {0} {1} run {2} of {3}" -f $route.Name, $mode, $run, $Runs)
            cmd /c "`"$probe`" $mode > `"$log`" 2>&1"
            $lines = Get-Content $log
            $name = [regex]::Escape($mode)
            $verdict = Find $lines 'probe: verdict ([A-Z]+(?: [A-Z]+)?) \('
            $phase = Find $lines "probe: phase $name composed (\d+) frames, (\d+) faulty"
            $missing = Find $lines "probe: anchor $name not available"
            $anchor = Find $lines "probe: anchor $name judged (\d+) faulty (\d+) max shift (\d+) px"
            $see = Find $lines "probe: see-through $name judged (\d+) faulty (\d+) max (\d+) px at the (\w+) \(see-through (\d+), frame of another size (\d+), size code unreadable (\d+)\)"
            $pace = Find $lines "probe: $name pace moves=\d+ gap_ms median ([0-9.]+)"
            if (-not $verdict) { $unfinished++ }
            $judgedRows += [pscustomobject]@{
                Route = $route.Name
                Mode = $mode
                Run = $run
                Composed = if ($phase) { $phase.Groups[1].Value } else { '-' }
                Judged = if ($missing) { 'n/a' } elseif ($anchor) { $anchor.Groups[1].Value } else { '-' }
                AnchorFaulty = if ($anchor) { $anchor.Groups[2].Value } else { '-' }
                WorstShiftPx = if ($anchor) { $anchor.Groups[3].Value } else { '-' }
                SeeThroughFaulty = if ($see) { $see.Groups[2].Value } else { '-' }
                SeeThroughPx = if ($see) { "$($see.Groups[3].Value) $($see.Groups[4].Value)" } else { '-' }
                OtherSize = if ($see) { $see.Groups[6].Value } else { '-' }
                Unreadable = if ($see) { $see.Groups[7].Value } else { '-' }
                GapMedianMs = if ($pace) { $pace.Groups[1].Value } else { '-' }
                Verdict = if ($verdict) { $verdict.Groups[1].Value } else { 'unfinished' }
            }
        }
    }
}
$env:DXC_DCOMP = $null
$env:DXC_REPORT_LATENCY = $null

$gpus = (Get-CimInstance Win32_VideoController | ForEach-Object {
    "$($_.Name) ($($_.CurrentHorizontalResolution)x$($_.CurrentVerticalResolution) at $($_.CurrentRefreshRate) Hz)"
}) -join '; '
$os = Get-CimInstance Win32_OperatingSystem
$header = @(
    "windows-shared lag probe, $(Get-Date -Format 'yyyy-MM-dd HH:mm')",
    "Windows $($os.Version) build $($os.BuildNumber)",
    "GPU: $gpus",
    "Runs per drag and route: $Runs",
    ''
)
$table = $rows | Format-Table -AutoSize | Out-String -Width 200
$judgedTable = @(
    '',
    'Judged drags (issue 223): left, top and top-left drags in and out, and fast grows, judged against the',
    "window's border. AnchorFaulty: frames where the picture slid (WorstShiftPx the largest slide).",
    'SeeThroughFaulty: frames with the desktop showing through inside the border (SeeThroughPx the widest',
    'strip and its edge) or a picture drawn for another size (OtherSize) or not readable (Unreadable).',
    'n/a: this Windows cannot colour the border (Windows 10).',
    ($judgedRows | Format-Table -AutoSize | Out-String -Width 220)
)
$summary = Join-Path $out 'lag-probe-summary.txt'
($header + $table + $judgedTable) | Set-Content -Path $summary -Encoding utf8
Get-Content $summary
Write-Host "Summary written to $summary"
if ($unfinished -gt 0) {
    Write-Error "$unfinished probe runs did not finish; their logs are in $out"
    exit 1
}
exit 0
