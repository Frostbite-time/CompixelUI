<#
.SYNOPSIS
Runs the acceptance or benchmark client suite, hidden, for one, several or all Minecraft versions.

.DESCRIPTION
Each version runs in its own fresh Gradle process on a separate, never-activated Win32 desktop, so the
game never appears on, takes focus from, or plays sound on the interactive desktop. Versions run one
after another, never in parallel; a failed version is reported and the remaining versions still run.
Every version prepares its own fresh flat creative test world before the suite starts.

.EXAMPLE
tools/run_background_suite.ps1 -Suite acceptance

.EXAMPLE
tools/run_background_suite.ps1 -Suite benchmark -Minecraft 26.2,26.3 -Backend vulkan -Label candidate
#>
param(
    [Parameter(Mandatory)][ValidateSet('acceptance', 'benchmark')][string]$Suite,
    # 'all', or targets from gradle/minecraft-targets.properties, as a list or comma-separated.
    [ValidatePattern('^[0-9A-Za-z._,-]+$')][string[]]$Minecraft = @('all'),
    [ValidateSet('opengl', 'vulkan', 'cpu')][string]$Backend = 'opengl',
    [ValidateSet('bundled', 'external')][string]$KotlinMode = 'bundled',
    [string]$KotlinProviderJar,
    # Vulkan validation layers directory; enables validation and checks the log for unexpected errors.
    [string]$ValidationLayerPath,
    # Benchmark only.
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$Label = 'baseline',
    [ValidateRange(120, 1500)][int]$Frames = 360,
    [ValidateRange(1, 5)][int]$Repeats = 2,
    [switch]$Control
)
$ErrorActionPreference = 'Stop'
if ($Suite -ne 'benchmark') {
    foreach ($name in @('Label', 'Frames', 'Repeats', 'Control')) {
        if ($PSBoundParameters.ContainsKey($name)) { throw "-$name only applies to -Suite benchmark" }
    }
}
if ($KotlinMode -eq 'bundled' -and $KotlinProviderJar) { throw 'A Kotlin provider requires -KotlinMode external' }
if ($ValidationLayerPath -and $Backend -ne 'vulkan') { throw 'Validation layers require -Backend vulkan' }

$repo = Split-Path $PSScriptRoot
$targets = ConvertFrom-StringData ([IO.File]::ReadAllText((Join-Path $repo 'gradle/minecraft-targets.properties')))
$known = $targets['targets'].Split(',')
$names = @($Minecraft | ForEach-Object { $_.Split(',') } | Where-Object { $_ })
$all = $names -contains 'all'
if ($all -and $names.Count -gt 1) { throw "-Minecraft all cannot be combined with other targets" }
$requested = if ($all) { $known } else { $names }
$versions = @()
foreach ($version in $requested) {
    if ($version -notin $known) { throw "Unknown Minecraft target: $version; known targets: $($known -join ', ')" }
    $adapterSettings = ConvertFrom-StringData ([IO.File]::ReadAllText((Join-Path $repo "$($targets["$version.project"])/gradle.properties")))
    if ($Backend -eq 'vulkan' -and 'vulkan' -notin $adapterSettings['minecraft_backends'].Split(',')) {
        if (!$all) { throw "Minecraft $version does not support the Vulkan backend" }
        Write-Output "Skipping Minecraft ${version}: it has no Vulkan backend"
        continue
    }
    $versions += $version
}
if (!$versions) { throw 'No Minecraft version to run' }

$task = 'run' + $Suite.Substring(0, 1).ToUpper() + $Suite.Substring(1)
$directory = if ($Suite -eq 'benchmark') { "benchmark-$Backend-background-$Label" } else { "acceptance-$Backend-background" }
# The first run of a version also builds it; the game part follows the Gradle task's own limit.
$timeout = if ($Suite -eq 'benchmark') { 45 + [Math]::Ceiling(25 * $Repeats * (136 + $Frames) / 3600.0 * 1.5) } else { 60 }
$logs = Join-Path $repo '.work/suites'
New-Item -ItemType Directory -Force $logs | Out-Null

$savedLayerEnvironment = @{}
foreach ($name in @('VK_LAYER_PATH', 'VK_LAYER_ENABLES', 'VK_LAYER_DUPLICATE_MESSAGE_LIMIT')) {
    $savedLayerEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$results = @()
try {
    if ($ValidationLayerPath) {
        $env:VK_LAYER_PATH = (Resolve-Path -LiteralPath $ValidationLayerPath).Path
        $env:VK_LAYER_ENABLES = 'VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT'
        $env:VK_LAYER_DUPLICATE_MESSAGE_LIMIT = '10000'
    }
    foreach ($version in $versions) {
        $adapterDirectory = $targets["$version.project"]
        $adapter = ':' + $adapterDirectory.Replace('/', ':')
        $arguments = @("${adapter}:$task", '--console=plain', "-PcompixelTargets=$version", "-PcompixelBackend=$Backend",
            '-PcompixelBackground=true', '-PcompixelIsolatedDesktop=true', "-PcompixelKotlinMode=$KotlinMode")
        if ($KotlinProviderJar) { $arguments += '-PcompixelKotlinProviderJar=' + (Resolve-Path -LiteralPath $KotlinProviderJar).Path }
        if ($ValidationLayerPath) { $arguments += '-PcompixelVulkanValidation=true' }
        if ($Suite -eq 'benchmark') {
            $arguments += "-PcompixelLabel=$Label", "-PcompixelBenchmarkFrames=$Frames", "-PcompixelBenchmarkRepeats=$Repeats"
            if ($Control) { $arguments += '-PcompixelBenchmarkControl=true' }
        }
        $name = if ($Suite -eq 'benchmark') { "$Suite-$version-$Backend-$Label" } else { "$Suite-$version-$Backend" }
        $log = Join-Path $logs "$name.log"
        $report = Join-Path $repo "$adapterDirectory/build/$directory/compixel-$Suite.txt"
        # A build failure before the run must not leave a previous run's report as this run's summary.
        Remove-Item -LiteralPath $report -Force -ErrorAction SilentlyContinue
        $failure = $null
        try {
            & (Join-Path $PSScriptRoot 'run_isolated_gradle.ps1') -ProjectDirectory $repo -GradleArguments $arguments `
                -LogPath $log -TimeoutMinutes $timeout
        } catch { $failure = $_.Exception.Message }
        $summary = if (Test-Path -LiteralPath $report) { (Get-Content -LiteralPath $report -TotalCount 1) } else { 'no report' }
        $passed = !$failure -and $summary.StartsWith('PASS')
        $results += [pscustomobject]@{ Minecraft = $version; Result = $(if ($passed) { 'PASS' } else { 'FAIL' }); Report = $summary; Log = $log }
        if ($passed -and $Suite -eq 'benchmark' -and !$Control) {
            Write-Output "Benchmark results: $(Join-Path $repo "$adapterDirectory/build/$directory/benchmark-results/report.json")"
        }
    }
} finally {
    foreach ($name in $savedLayerEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedLayerEnvironment[$name], 'Process')
    }
}
$results | Format-Table -AutoSize -Wrap | Out-String -Width 240 | Write-Output
$failed = @($results | Where-Object Result -ne 'PASS')
if ($failed) { throw "$($failed.Count) of $($results.Count) $Suite runs failed: $(($failed | ForEach-Object Minecraft) -join ', ')" }
Write-Output "All $($results.Count) $Suite runs passed ($Backend)."
