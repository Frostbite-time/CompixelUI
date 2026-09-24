<#
.SYNOPSIS
Runs a client suite in a real Forge 1.20.1 installation, hidden on an isolated desktop.

.DESCRIPTION
Forge 1.20.1 is the only target whose release archive is remapped (SRG) for production, so its
Gradle runs cannot load the archive players install. This script installs Forge, stages the release
and development archives and runs the same acceptance or benchmark suite as the Gradle tasks.
#>
param(
    [Parameter(Mandatory)][string]$JavaHome,
    [ValidateSet('acceptance', 'benchmark')][string]$Suite = 'acceptance',
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$Label = 'production',
    [ValidateSet('bundled', 'external')][string]$KotlinMode = 'bundled',
    [string]$KotlinProviderJar,
    [ValidateRange(120, 1500)][int]$Frames = 360,
    [ValidateRange(1, 5)][int]$Repeats = 2,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
if ($KotlinMode -eq 'bundled' -and $KotlinProviderJar) { throw 'A Kotlin provider requires -KotlinMode external' }
$repo = Split-Path $PSScriptRoot
$java = Join-Path (Resolve-Path $JavaHome).Path 'bin/java.exe'
$targets = ConvertFrom-StringData ([IO.File]::ReadAllText((Join-Path $repo 'gradle/minecraft-targets.properties')))
$properties = ConvertFrom-StringData ([IO.File]::ReadAllText((Join-Path $repo 'gradle.properties')))
$mcVersion = '1.20.1'
$adapterDirectory = $targets["$mcVersion.project"]
$adapterProject = ':' + $adapterDirectory.Replace('/', ':')
$adapterSettings = ConvertFrom-StringData ([IO.File]::ReadAllText((Join-Path $repo "$adapterDirectory/gradle.properties")))
$forgeVersion = $adapterSettings['forge_version']
$forgeCoordinate = "$mcVersion-$forgeVersion"
$versionName = "$mcVersion-forge-$forgeVersion"
$launcher = Join-Path $repo '.work/forge-production'
$game = Join-Path $repo "$adapterDirectory/build/production-$Suite-$Label-$KotlinMode"
$libraryRoot = Join-Path $launcher 'libraries'
$assets = Join-Path $env:USERPROFILE '.gradle/caches/neoformruntime/assets'
New-Item -ItemType Directory -Force $launcher,$game,$libraryRoot | Out-Null

function Fetch([string]$Url, [string]$Path, [string]$Sha1) {
    if ((Test-Path -LiteralPath $Path) -and (!$Sha1 -or (Get-FileHash $Path -Algorithm SHA1).Hash -eq $Sha1)) { return }
    New-Item -ItemType Directory -Force (Split-Path $Path) | Out-Null
    Invoke-WebRequest $Url -OutFile $Path
    if ($Sha1 -and (Get-FileHash $Path -Algorithm SHA1).Hash -ne $Sha1) { throw "Checksum mismatch: $Path" }
}
function Allowed($rules) {
    if (!$rules) { return $true }
    $allow = $false
    foreach ($rule in $rules) {
        if ($rule.os.name -and $rule.os.name -ne 'windows') { continue }
        if ($rule.os.arch -and $rule.os.arch -notin @('amd64','x86_64')) { continue }
        if ($rule.os.version -and [Environment]::OSVersion.Version.ToString() -notmatch $rule.os.version) { continue }
        $match = $true
        foreach ($feature in $rule.features.PSObject.Properties) {
            $enabled = $feature.Name -eq 'has_custom_resolution'
            if ($enabled -ne $feature.Value) { $match = $false }
        }
        if ($match) { $allow = $rule.action -eq 'allow' }
    }
    return $allow
}

if (!$SkipBuild) {
    Push-Location $repo
    try {
        & .\gradlew.bat '-PcomposemcTargets=1.20.1' "${adapterProject}:build" "${adapterProject}:downloadAssets" --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Forge production build failed' }
    } finally { Pop-Location }
}
$manifest = Invoke-RestMethod 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'
$metadata = $manifest.versions | Where-Object id -eq $mcVersion
$baseFile = Join-Path $launcher "versions/$mcVersion/$mcVersion.json"
Fetch $metadata.url $baseFile ''
$vanilla = Get-Content $baseFile -Raw | ConvertFrom-Json
$baseJar = Join-Path $launcher "versions/$mcVersion/$mcVersion.jar"
Fetch $vanilla.downloads.client.url $baseJar $vanilla.downloads.client.sha1
$profileFile = Join-Path $launcher "versions/$versionName/$versionName.json"
if (!(Test-Path -LiteralPath $profileFile)) {
    [IO.File]::WriteAllText((Join-Path $launcher 'launcher_profiles.json'), '{"profiles":{}}')
    $installer = Join-Path $launcher 'installer.jar'
    Fetch "https://maven.minecraftforge.net/net/minecraftforge/forge/$forgeCoordinate/forge-$forgeCoordinate-installer.jar" $installer ''
    & $java '-Djava.awt.headless=true' -jar $installer --installClient $launcher
    if ($LASTEXITCODE -ne 0) { throw 'Forge installation failed' }
}
$forge = Get-Content $profileFile -Raw | ConvertFrom-Json
$libraries = [ordered]@{}
foreach ($lib in @($vanilla.libraries) + @($forge.libraries)) {
    if (!(Allowed $lib.rules)) { continue }
    $parts = $lib.name.Split(':')
    $classifier = if ($parts.Count -gt 3) { $parts[3] } else { '' }
    $libraries["$($parts[0]):$($parts[1]):$classifier"] = $lib
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$classpath = [Collections.Generic.List[string]]::new()
$natives = Join-Path $game 'natives'
New-Item -ItemType Directory -Force $natives | Out-Null
foreach ($lib in $libraries.Values) {
    $artifact = $lib.downloads.artifact
    $path = Join-Path $libraryRoot $artifact.path
    if (!(Test-Path -LiteralPath $path)) {
        $parts = $lib.name.Split(':')
        $cache = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$($parts[0])/$($parts[1])/$($parts[2])"
        $cached = Get-ChildItem $cache -Recurse -File -Filter (Split-Path $path -Leaf) -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($cached -and (!$artifact.sha1 -or (Get-FileHash $cached.FullName -Algorithm SHA1).Hash -eq $artifact.sha1)) {
            New-Item -ItemType Directory -Force (Split-Path $path) | Out-Null
            Copy-Item -LiteralPath $cached.FullName -Destination $path
        }
    }
    Fetch $artifact.url $path $artifact.sha1
    $classpath.Add($path)
    if ($lib.name -match 'natives-windows') {
        $zip = [IO.Compression.ZipFile]::OpenRead($path)
        try {
            foreach ($entry in $zip.Entries) {
                if ($entry.Name.EndsWith('.dll')) {
                    [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,(Join-Path $natives $entry.Name),$true)
                }
            }
        } finally { $zip.Dispose() }
    }
}
# Match the Forge ignore-list's version JAR name while retaining vanilla resources.
$launchJar = Join-Path $launcher "versions/$versionName/$versionName.jar"
Copy-Item -LiteralPath $baseJar -Destination $launchJar -Force
$classpath.Add($launchJar)
if (!(Test-Path (Join-Path $assets "indexes/$($vanilla.assetIndex.id).json"))) { throw "Run ${adapterProject}:downloadAssets first" }

$mods = Join-Path $game 'mods'
New-Item -ItemType Directory -Force $mods,(Join-Path $game 'config') | Out-Null
# This generated test directory is staged from scratch, like Gradle's Sync task.
foreach ($old in Get-ChildItem -LiteralPath $mods -File -Filter '*.jar') { Remove-Item -LiteralPath $old.FullName }
$librarySuffix = if ($KotlinMode -eq 'bundled') { '-with-kotlin' } else { '' }
foreach ($suffix in @($librarySuffix,'-development')) {
    $jar = Join-Path $repo "$adapterDirectory/build/libs/composemc-forge-1.20.1-$($properties.mod_version)$suffix.jar"
    Copy-Item -LiteralPath $jar -Destination $mods
}
if ($KotlinProviderJar) { Copy-Item -LiteralPath (Resolve-Path -LiteralPath $KotlinProviderJar).Path -Destination $mods -Force }
[IO.File]::WriteAllText((Join-Path $game 'config/fml.toml'), "earlyWindowControl=false`n")
$substitutions = @{
    auth_player_name='ComposeProbe';version_name=$versionName;game_directory=$game
    assets_root=$assets;assets_index_name=$vanilla.assetIndex.id
    auth_uuid='d399082ec2044e01b4bc916b6f344f97';auth_access_token='0';clientid='0';auth_xuid='0'
    user_type='legacy';version_type='release';resolution_width='1280';resolution_height='960'
    natives_directory=$natives;launcher_name='composemc-validation';launcher_version='1'
    classpath=($classpath -join [IO.Path]::PathSeparator);library_directory=$libraryRoot;classpath_separator=[IO.Path]::PathSeparator
}
function Arguments($items) {
    foreach ($item in $items) {
        $values = if ($item -is [string]) { @($item) } elseif (Allowed $item.rules) { @($item.value) } else { @() }
        foreach ($value in $values) {
            $expanded = [string]$value
            foreach ($key in $substitutions.Keys) { $expanded=$expanded.Replace('${'+$key+'}',[string]$substitutions[$key]) }
            if ($expanded -match '\$\{') { throw "Unresolved launch argument: $expanded" }
            $expanded
        }
    }
}
$suiteArguments = @("-Dcomposemc.suite=$Suite", '-Dcomposemc.suite.background=true', '-Dcomposemc.suite.isolated=true',
    '-Dcomposemc.suite.production=true', '-Dcomposemc.backend=opengl')
if ($Suite -eq 'benchmark') {
    $suiteArguments += '-Dcomposemc.profile=true', '-Dcomposemc.allocations=true', "-Dcomposemc.benchmark.frames=$Frames",
        "-Dcomposemc.benchmark.repeats=$Repeats", "-Dcomposemc.benchmark.label=$Label"
}
$arguments = @('-Xmx3G') + $suiteArguments +
    @(Arguments $vanilla.arguments.jvm) + @(Arguments $forge.arguments.jvm) + @($forge.mainClass) +
    @(Arguments $vanilla.arguments.game) + @(Arguments $forge.arguments.game)
$argumentFile = Join-Path $game 'production.args'
[IO.File]::WriteAllLines($argumentFile, @($arguments | ForEach-Object { '"' + $_.Replace('\','/').Replace('"','\"') + '"' }), [Text.UTF8Encoding]::new($false))
$resultFile = Join-Path $game "composemc-$Suite.txt"
if (Test-Path -LiteralPath $resultFile) { Remove-Item -LiteralPath $resultFile }
$log = Join-Path $repo ".work/forge-production-$Suite-$Label.log"
. (Join-Path $PSScriptRoot 'windows_isolated_desktop.ps1')
$desktopName='composemc-isolated-'+[Guid]::NewGuid().ToString('N')
$command='"'+$env:ComSpec+'" /d /s /c ""'+$java+'" @"'+$argumentFile+'" > "'+$log+'" 2>&1"'
$timeout = if ($Suite -eq 'benchmark') { 15 + [Math]::Ceiling(25 * $Repeats * (136 + $Frames) / 3600.0 * 1.5) } else { 30 }
Write-Output "Running the $Suite suite in installed Forge $forgeCoordinate on an isolated desktop. Log: $log"
$code=[ComposeIsolatedDesktop]::Run($env:ComSpec,$command,$game,$desktopName,$timeout)
Get-Content $log -Tail 15
if ($code -ne 0 -or !(Test-Path $resultFile) -or !(Get-Content $resultFile -Raw).StartsWith('PASS')) {
    throw "Production $Suite failed ($code); inspect $log and $resultFile"
}
Get-Content $resultFile
