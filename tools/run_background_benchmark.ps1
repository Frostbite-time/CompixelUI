param(
    [ValidatePattern('^[a-zA-Z0-9._-]+$')][string]$Minecraft = '1.21.1',
    [ValidateSet('opengl', 'vulkan', 'cpu')][string]$Backend = 'opengl',
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$Label = 'port',
    [ValidateRange(120, 1500)][int]$Frames = 120,
    [ValidateSet('bundled', 'external')][string]$KotlinMode = 'bundled',
    [string]$KotlinProviderJar,
    [string]$ValidationLayerPath,
    [switch]$Control
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot
$targets = ConvertFrom-StringData ([IO.File]::ReadAllText((Join-Path $repo 'gradle/minecraft-targets.properties')))
if ($Minecraft -notin $targets['targets'].Split(',')) { throw "Unknown Minecraft target: $Minecraft" }
$adapterDirectory = $targets["$Minecraft.project"]
$adapterSettings = ConvertFrom-StringData ([IO.File]::ReadAllText((Join-Path $repo "$adapterDirectory/gradle.properties")))
if ($Backend -eq 'vulkan' -and 'vulkan' -notin $adapterSettings['minecraft_backends'].Split(',')) {
    throw "Minecraft $Minecraft does not support the Vulkan backend"
}
$adapter = ':' + $adapterDirectory.Replace('/', ':')
$output = Join-Path $repo '.work'
New-Item -ItemType Directory -Force $output | Out-Null
$log = Join-Path $output "benchmark-$Minecraft-$Backend-$Label.log"

# Minecraft 26.2 unconditionally shows its window during initialization. A
# separate, never-activated Win32 desktop contains that startup and also works
# with SDL. The shared launcher never switches the interactive desktop.
$savedLayerEnvironment = @{}
foreach ($name in @('VK_LAYER_PATH', 'VK_LAYER_ENABLES', 'VK_LAYER_DUPLICATE_MESSAGE_LIMIT')) {
    $savedLayerEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$taskArguments = @("${adapter}:runBenchmark", '--console=plain', "-PcomposemcTargets=$Minecraft",
    "-PcomposemcBackend=$Backend", '-PcomposemcBenchmarkBackground=true',
    '-PcomposemcBenchmarkIsolatedDesktop=true', "-PcomposemcBenchmarkFrames=$Frames", "-PcomposemcBenchmarkLabel=$Label")
$taskArguments += "-PcomposemcKotlinMode=$KotlinMode"
if ($KotlinProviderJar) {
    $taskArguments += '-PcomposemcKotlinProviderJar=' + (Resolve-Path -LiteralPath $KotlinProviderJar).Path
}
if ($ValidationLayerPath) {
    if ($Backend -ne 'vulkan') { throw 'Validation layers require the Vulkan backend' }
    $env:VK_LAYER_PATH = (Resolve-Path $ValidationLayerPath).Path
    $env:VK_LAYER_ENABLES = 'VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT'
    $env:VK_LAYER_DUPLICATE_MESSAGE_LIMIT = '10000'
    $taskArguments += '-PcomposemcVulkanValidation=true'
}
if ($Control) { $taskArguments += '-PcomposemcBenchmarkControl=true' }
try {
    & (Join-Path $PSScriptRoot 'run_isolated_gradle.ps1') -ProjectDirectory $repo -GradleArguments $taskArguments -LogPath $log
} finally {
    foreach ($name in $savedLayerEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedLayerEnvironment[$name], 'Process')
    }
}
