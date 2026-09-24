<#
.SYNOPSIS
Runs the performance suite hidden on an isolated desktop, for every Minecraft version by default.

.EXAMPLE
tools/run_background_benchmark.ps1
tools/run_background_benchmark.ps1 -Minecraft 26.3 -Backend vulkan -Label candidate -Frames 600 -Repeats 3
#>
param(
    [string[]]$Minecraft = @('all'),
    [ValidateSet('opengl', 'vulkan', 'cpu')][string]$Backend = 'opengl',
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$Label = 'baseline',
    [ValidateRange(120, 1500)][int]$Frames = 360,
    [ValidateRange(1, 5)][int]$Repeats = 2,
    [ValidateSet('bundled', 'external')][string]$KotlinMode = 'bundled',
    [string]$KotlinProviderJar,
    [string]$ValidationLayerPath,
    # Same startup, world and resource reload without any Compose renderer.
    [switch]$Control
)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'run_background_suite.ps1') -Suite benchmark @PSBoundParameters
