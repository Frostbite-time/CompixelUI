param(
    [Parameter(Mandatory)][string]$ProjectDirectory,
    [Parameter(Mandatory)][string[]]$GradleArguments,
    [Parameter(Mandatory)][string]$LogPath
)
$ErrorActionPreference = 'Stop'
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { throw 'Desktop isolation requires Windows' }
$taskProject = (Resolve-Path -LiteralPath $ProjectDirectory).Path
$taskLog = [IO.Path]::GetFullPath($LogPath)
if (!(Test-Path -LiteralPath (Join-Path $taskProject 'gradlew.bat'))) { throw 'Gradle wrapper not found' }
$taskLaunchDirectory = Join-Path $taskProject '.work/isolated-gradle'
New-Item -ItemType Directory -Force -Path $taskLaunchDirectory,([IO.Path]::GetDirectoryName($taskLog)) | Out-Null
$taskId = [Guid]::NewGuid().ToString('N')
$taskConfiguration = Join-Path $taskLaunchDirectory "$taskId.json"
$taskRunner = Join-Path $taskLaunchDirectory "$taskId.ps1"
@{ project=$taskProject; arguments=$GradleArguments; log=$taskLog } |
    ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $taskConfiguration -Encoding UTF8

# Arguments are data in a JSON file, never interpolated into PowerShell command text.
@'
param([string]$Configuration)
$ErrorActionPreference = 'Stop'
$taskSettings = Get-Content -LiteralPath $Configuration -Raw | ConvertFrom-Json
Set-Location -LiteralPath $taskSettings.project
# A reused daemon could launch the game back on the interactive desktop.
$taskArguments = @('--no-daemon') + @($taskSettings.arguments)
& (Join-Path $taskSettings.project 'gradlew.bat') @taskArguments *> $taskSettings.log
exit $LASTEXITCODE
'@ | Set-Content -LiteralPath $taskRunner -Encoding UTF8

. (Join-Path $PSScriptRoot 'windows_benchmark_desktop.ps1')
$taskPowerShell = (Get-Process -Id $PID).Path
$taskCommand = '"' + $taskPowerShell + '" -NoLogo -NoProfile -NonInteractive -File "' + $taskRunner + '" -Configuration "' + $taskConfiguration + '"'
$taskDesktop = 'composemc-benchmark-' + $taskId
Write-Output "Starting isolated Gradle run on $taskDesktop. Log: $taskLog"
$taskCode = [ComposeBenchmarkDesktop]::Run($taskPowerShell, $taskCommand, $taskProject, $taskDesktop)
Get-Content -LiteralPath $taskLog -Tail 35
if ($taskCode -ne 0) { throw "Isolated Gradle run failed ($taskCode); see $taskLog" }
