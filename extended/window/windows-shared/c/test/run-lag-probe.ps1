<#
.SYNOPSIS
Measures how the Windows window follows a dragged edge on this machine, on its three drag routes.

.DESCRIPTION
Builds the window and its resize probe with cl, as the extended-window-windows-shared workflow
does, then drags the probe window's right edge with real input on three routes: the
DirectComposition route with each drag step drawn on the CPU (dcomp-cpu-drag, the default),
the DirectComposition route with each step rendered on the GPU inside WM_NCCALCSIZE
(dcomp-gpu-drag, DXC_DCOMP_GPU_DRAG=1), and the GDI route (gdi, DXC_DCOMP=0). On each: a drag
with frames that cost nothing, one with 30 ms frames, and one with 5 ms frames drawn ahead of
each size. Each run prints, and the summary file keeps, the faulty frames DWM composed, how
far the edge lagged the pointer, the window's memory at rest, and, under the table, where the
time of a drag step went on the two DirectComposition routes.

Run it from "x64 Native Tools Command Prompt for VS 2022" (cl has to be on PATH):

    powershell -ExecutionPolicy Bypass -File extended\window\windows-shared\c\test\run-lag-probe.ps1

When win32_resize_probe.exe sits beside this script, as in the workflow's
lag-probe-windows-x64 artifact, it compiles nothing and needs no compiler: double-click
run-lag-probe.cmd, and the logs and lag-probe-summary.txt land in this script's folder.

It moves a window and the pointer on screen: leave the mouse and keyboard alone until it ends.

.PARAMETER Runs
How many times each drag runs on each route. 3 unless given.

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
    @{ Name = 'dcomp-cpu-drag'; Flag = $null; GpuDrag = $null },
    @{ Name = 'dcomp-gpu-drag'; Flag = $null; GpuDrag = '1' },
    @{ Name = 'gdi'; Flag = '0'; GpuDrag = $null }
)

function Find([string[]]$lines, [string]$pattern) {
    foreach ($line in $lines) {
        $match = [regex]::Match($line, $pattern)
        if ($match.Success) { return $match }
    }
    return $null
}

$rows = @()
$timings = @()
$unfinished = 0
foreach ($route in $routes) {
    foreach ($drag in $drags) {
        for ($run = 1; $run -le $Runs; $run++) {
            $env:DXC_DCOMP = $route.Flag
            $env:DXC_DCOMP_GPU_DRAG = $route.GpuDrag
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
            # How the drag's frames were drawn: every step of dcomp-gpu-drag has to be a GPU one.
            $frames = Find $lines 'probe: frames drawn cpu=(\d+) gpu=(\d+)'
            $gpuSteps = Find $lines 'dcomp: gpu drag timing steps=(\d+) drawn_ahead=(\d+)'
            foreach ($line in $lines) {
                if ($line -match 'compose-rust: dcomp: (gpu drag timing|drag timing|drag upload parts) (.*)$') {
                    $timings += ("{0} {1} run {2}: {3} {4}" -f $route.Name, $drag.Name, $run, $Matches[1], $Matches[2])
                }
            }
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
                CpuFrames = if ($frames) { $frames.Groups[1].Value } else { '-' }
                GpuFrames = if ($frames) { $frames.Groups[2].Value } else { '-' }
                GpuDragSteps = if ($gpuSteps) { $gpuSteps.Groups[2].Value } else { '-' }
                IdleWorkingSetMb = if ($memory) { $memory.Groups[1].Value } else { '-' }
                IdlePrivateMb = if ($memory) { $memory.Groups[2].Value } else { '-' }
                WindowRoute = if ($memory) { $memory.Groups[3].Value } else { '-' }
                Finished = [bool]$verdict
            }
        }
    }
}
$env:DXC_DCOMP = $null
$env:DXC_DCOMP_GPU_DRAG = $null
$env:PROBE_DRAW_MS = $null

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
$table = $rows | Format-Table -AutoSize | Out-String -Width 260
$notes = @(
    'Routes: dcomp-cpu-drag = DirectComposition, each drag step drawn on the CPU (the default);',
    'dcomp-gpu-drag = DirectComposition, each drag step rendered on the GPU in WM_NCCALCSIZE',
    '(DXC_DCOMP_GPU_DRAG=1); gdi = the GDI route (DXC_DCOMP=0).',
    'GpuDragSteps: steps of the drag rendered on the GPU ahead of their size (dcomp-gpu-drag only).',
    '',
    'Where a drag step went, per run (ms):'
)
$summary = Join-Path $out 'lag-probe-summary.txt'
($header + $table + $notes + $timings) | Set-Content -Path $summary -Encoding utf8
Get-Content $summary
Write-Host "Summary written to $summary"
if ($unfinished -gt 0) {
    Write-Error "$unfinished probe runs did not finish; their logs are in $out"
    exit 1
}
exit 0
