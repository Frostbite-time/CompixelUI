<#
.SYNOPSIS
Runs the correctness suite hidden on an isolated desktop, for every Minecraft version by default.

.EXAMPLE
tools/run_background_acceptance.ps1 -Minecraft 1.21.1 -KotlinProviderJar path\to\kff-5.12.0.jar
tools/run_background_acceptance.ps1 -Minecraft 1.20.1,1.21.1 -KotlinProviderJar 1.20.1=path\to\kff-4.12.0.jar,1.21.1=path\to\kff-5.12.0.jar
#>
param(
    [string[]]$Minecraft = @('all'),
    [ValidateSet('opengl', 'vulkan', 'cpu')][string]$Backend = 'opengl',
    [string[]]$KotlinProviderJar,
    [string]$ValidationLayerPath
)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'run_background_suite.ps1') -Suite acceptance @PSBoundParameters
