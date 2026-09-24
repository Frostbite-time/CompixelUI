<#
.SYNOPSIS
Runs the correctness suite hidden on an isolated desktop, for every Minecraft version by default.

.EXAMPLE
tools/run_background_acceptance.ps1
tools/run_background_acceptance.ps1 -Minecraft 1.20.1,26.3 -Backend cpu
#>
param(
    [string[]]$Minecraft = @('all'),
    [ValidateSet('opengl', 'vulkan', 'cpu')][string]$Backend = 'opengl',
    [ValidateSet('bundled', 'external')][string]$KotlinMode = 'bundled',
    [string]$KotlinProviderJar,
    [string]$ValidationLayerPath
)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'run_background_suite.ps1') -Suite acceptance @PSBoundParameters
