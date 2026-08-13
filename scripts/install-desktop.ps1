# Build and install the NotiSync desktop commands for the current Windows user.
$ErrorActionPreference = 'Stop'

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'install-desktop: this script must be run on Windows'
}

function Resolve-InstallPath {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [string] $Description
    )

    $expanded = [Environment]::ExpandEnvironmentVariables($Path)
    if (-not [IO.Path]::IsPathRooted($expanded)) {
        throw "install-desktop: $Description must be an absolute path"
    }

    $resolved = [IO.Path]::GetFullPath($expanded)
    $root = [IO.Path]::GetPathRoot($resolved)
    if ([string]::Equals($resolved.TrimEnd('\', '/'), $root.TrimEnd('\', '/'), [StringComparison]::OrdinalIgnoreCase)) {
        throw "install-desktop: $Description must not be a filesystem root"
    }
    return $resolved.TrimEnd('\', '/')
}

function Resolve-JavaHome {
    $configuredJavaHome = $env:JAVA_HOME
    if (-not [string]::IsNullOrWhiteSpace($configuredJavaHome)) {
        $candidate = [Environment]::ExpandEnvironmentVariables($configuredJavaHome.Trim().Trim('"'))
        if (-not [IO.Path]::IsPathRooted($candidate)) {
            throw 'install-desktop: JAVA_HOME must be an absolute path'
        }
        $javaExecutable = Join-Path ([IO.Path]::GetFullPath($candidate)) 'bin\java.exe'
        if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
            throw "install-desktop: JAVA_HOME does not contain bin\java.exe: $candidate"
        }
    } else {
        $javaCommand = Get-Command java.exe -CommandType Application -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($null -eq $javaCommand) {
            throw 'install-desktop: JDK 21 or newer is required; set JAVA_HOME or add java.exe to PATH'
        }
        $javaExecutable = $javaCommand.Source
    }

    # Ask Java for its real home so PATH shims and symbolic links do not get recorded as a JDK root.
    $startInfo = New-Object Diagnostics.ProcessStartInfo
    $startInfo.FileName = $javaExecutable
    $startInfo.Arguments = '-XshowSettings:properties -version'
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $startInfo
    [void] $process.Start()
    $settings = $process.StandardError.ReadToEnd() + "`n" + $process.StandardOutput.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "install-desktop: could not inspect Java at $javaExecutable"
    }

    $homeMatch = [regex]::Match($settings, '(?m)^\s*java\.home\s*=\s*(.+?)\s*$')
    $versionMatch = [regex]::Match($settings, '(?m)^\s*java\.specification\.version\s*=\s*(.+?)\s*$')
    if (-not $homeMatch.Success -or -not $versionMatch.Success) {
        throw "install-desktop: could not determine the Java home and version for $javaExecutable"
    }

    $versionText = $versionMatch.Groups[1].Value.Trim()
    $majorText = if ($versionText.StartsWith('1.')) {
        ($versionText -split '\.')[1]
    } else {
        ($versionText -split '\.')[0]
    }
    $majorVersion = 0
    if (-not [int]::TryParse($majorText, [ref] $majorVersion) -or $majorVersion -lt 21) {
        throw "install-desktop: JDK 21 or newer is required, but Java $versionText was found at $javaExecutable"
    }

    $resolvedJavaHome = [IO.Path]::GetFullPath($homeMatch.Groups[1].Value.Trim()).TrimEnd('\', '/')
    if (-not (Test-Path -LiteralPath (Join-Path $resolvedJavaHome 'bin\java.exe') -PathType Leaf)) {
        throw "install-desktop: Java reported an invalid home directory: $resolvedJavaHome"
    }
    return $resolvedJavaHome
}

function Invoke-CheckedCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string] $FilePath,

        [Parameter(ValueFromRemainingArguments = $true)]
        [string[]] $Arguments
    )

    # Windows PowerShell 5.1 can promote native stderr to a terminating error when the
    # caller uses Stop. Judge native commands by their exit code and keep their output visible.
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $LASTEXITCODE = $null
        & $FilePath @Arguments
        $commandSucceeded = $?
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    # Windows PowerShell 5.1 can leave LASTEXITCODE unset after a successful batch file.
    if ($null -eq $exitCode) {
        $exitCode = if ($commandSucceeded) { 0 } else { 1 }
    }
    if ($exitCode -ne 0) {
        throw "install-desktop: command failed with exit code ${exitCode}: $FilePath"
    }
}

function Test-PathWithin {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [string] $Directory
    )

    $prefix = $Directory.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    return $Path.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)
}

function Remove-GeneratedPath {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    if (Test-Path -LiteralPath $Path) {
        Remove-Item -LiteralPath $Path -Recurse -Force
    }
}

$scriptDirectory = $PSScriptRoot
$projectDirectory = [IO.Path]::GetFullPath((Join-Path $scriptDirectory '..'))
$gradleWrapper = Join-Path $projectDirectory 'gradlew.bat'
$distributionDirectory = Join-Path $projectDirectory 'notisyncd\build\install\notisyncd'
$launchers = @('notisyncd', 'notisync', 'notisync-gpg')
$rememberedJavaHome = Resolve-JavaHome

$localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
if ([string]::IsNullOrWhiteSpace($localAppData) -or -not [IO.Path]::IsPathRooted($localAppData)) {
    throw 'install-desktop: Windows did not provide an absolute local application-data directory'
}

$defaultInstallDirectory = Join-Path $localAppData 'Programs\NotiSync'
$defaultBinDirectory = Join-Path $localAppData 'Microsoft\WindowsApps'
$requestedInstallDirectory = if ([string]::IsNullOrWhiteSpace($env:NOTISYNC_INSTALL_DIR)) {
    $defaultInstallDirectory
} else {
    $env:NOTISYNC_INSTALL_DIR
}
$requestedBinDirectory = if ([string]::IsNullOrWhiteSpace($env:NOTISYNC_BIN_DIR)) {
    $defaultBinDirectory
} else {
    $env:NOTISYNC_BIN_DIR
}

$installDirectory = Resolve-InstallPath $requestedInstallDirectory 'installation directory'
$binDirectory = Resolve-InstallPath $requestedBinDirectory 'command directory'
if (Test-PathWithin $binDirectory $installDirectory) {
    throw 'install-desktop: the command directory must not be inside the installation directory'
}

Write-Host 'Building the NotiSync desktop distribution...'
Invoke-CheckedCommand $gradleWrapper '-p' $projectDirectory ':notisyncd:installDist' '--console=plain' @args

foreach ($launcher in $launchers) {
    $builtLauncher = Join-Path $distributionDirectory "bin\${launcher}.bat"
    if (-not (Test-Path -LiteralPath $builtLauncher -PathType Leaf)) {
        throw "install-desktop: build did not produce bin\${launcher}.bat"
    }
}

$installParent = Split-Path -Parent $installDirectory
New-Item -ItemType Directory -Path $installParent -Force | Out-Null
New-Item -ItemType Directory -Path $binDirectory -Force | Out-Null

$shimPaths = @{}
foreach ($launcher in $launchers) {
    $shimPath = Join-Path $binDirectory "${launcher}.cmd"
    if ((Test-Path -LiteralPath $shimPath) -and -not (Test-Path -LiteralPath $shimPath -PathType Leaf)) {
        throw "install-desktop: refusing to replace non-file path $shimPath"
    }
    $shimPaths[$launcher] = $shimPath
}

$operationId = [Guid]::NewGuid().ToString('N')
$stageDirectory = Join-Path $installParent ".notisync-install.$operationId"
$backupDirectory = Join-Path $installParent ".notisync-backup.$operationId"
$shimBackupDirectory = Join-Path $binDirectory ".notisync-shim-backup.$operationId"
$installedNewDistribution = $false
$hadPreviousInstallation = $false
$daemonWasRunning = $false
$daemonWasStopped = $false
$installedShimPaths = New-Object System.Collections.Generic.List[string]
$backedUpShimPaths = @{}

try {
    New-Item -ItemType Directory -Path $stageDirectory | Out-Null
    Get-ChildItem -LiteralPath $distributionDirectory -Force |
        Copy-Item -Destination $stageDirectory -Recurse -Force

    $builtDaemon = Join-Path $distributionDirectory 'bin\notisyncd.bat'
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $LASTEXITCODE = $null
        & $builtDaemon status *> $null
        $statusSucceeded = $?
        $statusExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($null -eq $statusExitCode) {
        $statusExitCode = if ($statusSucceeded) { 0 } else { 1 }
    }
    $daemonWasRunning = $statusExitCode -eq 0
    if ($daemonWasRunning) {
        Write-Host 'Stopping the running NotiSync daemon...'
        Invoke-CheckedCommand $builtDaemon 'stop'
        $daemonWasStopped = $true
    } else {
        Write-Host 'NotiSync daemon is not running.'
    }

    if (Test-Path -LiteralPath $installDirectory) {
        Move-Item -LiteralPath $installDirectory -Destination $backupDirectory
        $hadPreviousInstallation = $true
    }

    Write-Host "Installing NotiSync to $installDirectory..."
    Move-Item -LiteralPath $stageDirectory -Destination $installDirectory
    $installedNewDistribution = $true

    New-Item -ItemType Directory -Path $shimBackupDirectory | Out-Null
    foreach ($launcher in $launchers) {
        $shimPath = $shimPaths[$launcher]
        if (Test-Path -LiteralPath $shimPath) {
            $backupShimPath = Join-Path $shimBackupDirectory "${launcher}.cmd"
            Move-Item -LiteralPath $shimPath -Destination $backupShimPath
            $backedUpShimPaths[$shimPath] = $backupShimPath
        }

        $target = Join-Path $installDirectory "bin\${launcher}.bat"
        # A doubled percent sign survives cmd.exe's batch-file expansion as a literal percent.
        $escapedTarget = $target.Replace('%', '%%')
        $escapedJavaHome = $rememberedJavaHome.Replace('%', '%%')
        $shim = @"
@echo off
setlocal DisableDelayedExpansion
if not defined JAVA_HOME goto notisync_java_home
if exist "%JAVA_HOME%\bin\java.exe" goto notisync_run
:notisync_java_home
set "JAVA_HOME=$escapedJavaHome"
:notisync_run
call "$escapedTarget" %*
exit /b %ERRORLEVEL%
"@
        [IO.File]::WriteAllText($shimPath, $shim, [Text.Encoding]::Default)
        $installedShimPaths.Add($shimPath)
    }

    if ($daemonWasRunning) {
        Write-Host 'Starting the updated NotiSync daemon...'
        Invoke-CheckedCommand (Join-Path $installDirectory 'bin\notisyncd.bat') 'start'
        $daemonWasStopped = $false
    }
} catch {
    $failure = $_

    foreach ($shimPath in $installedShimPaths) {
        try {
            if (Test-Path -LiteralPath $shimPath -PathType Leaf) {
                Remove-Item -LiteralPath $shimPath -Force
            }
        } catch {
            Write-Warning "install-desktop: could not remove new command shim ${shimPath}: $($_.Exception.Message)"
        }
    }
    foreach ($entry in $backedUpShimPaths.GetEnumerator()) {
        try {
            if (Test-Path -LiteralPath $entry.Value -PathType Leaf) {
                Move-Item -LiteralPath $entry.Value -Destination $entry.Key -Force
            }
        } catch {
            Write-Warning "install-desktop: preserved the previous command shim at $($entry.Value) because it could not be restored: $($_.Exception.Message)"
        }
    }

    try {
        if ($installedNewDistribution -and (Test-Path -LiteralPath $installDirectory)) {
            Remove-GeneratedPath $installDirectory
        }
        if ($hadPreviousInstallation -and (Test-Path -LiteralPath $backupDirectory)) {
            Move-Item -LiteralPath $backupDirectory -Destination $installDirectory
        }
    } catch {
        Write-Warning "install-desktop: automatic installation rollback was incomplete; the previous installation remains at ${backupDirectory}: $($_.Exception.Message)"
    }

    if ($daemonWasRunning -and $daemonWasStopped) {
        $restoredDaemon = Join-Path $installDirectory 'bin\notisyncd.bat'
        if (-not (Test-Path -LiteralPath $restoredDaemon -PathType Leaf)) {
            $restoredDaemon = Join-Path $backupDirectory 'bin\notisyncd.bat'
        }
        if (-not (Test-Path -LiteralPath $restoredDaemon -PathType Leaf)) {
            $restoredDaemon = Join-Path $distributionDirectory 'bin\notisyncd.bat'
        }
        try {
            Invoke-CheckedCommand $restoredDaemon 'start'
        } catch {
            Write-Warning "install-desktop: could not restart the previous daemon: $($_.Exception.Message)"
        }
    }

    throw $failure
} finally {
    try {
        Remove-GeneratedPath $stageDirectory
    } catch {
        Write-Warning "install-desktop: could not remove staging directory ${stageDirectory}: $($_.Exception.Message)"
    }
}

foreach ($obsoletePath in @($backupDirectory, $shimBackupDirectory)) {
    try {
        Remove-GeneratedPath $obsoletePath
    } catch {
        Write-Warning "install-desktop: installation succeeded, but obsolete backup cleanup failed for ${obsoletePath}: $($_.Exception.Message)"
    }
}

Write-Host "Installed NotiSync in $installDirectory"
Write-Host "Installed commands: $($launchers -join ' ')"

$pathEntries = $env:Path -split ';' | ForEach-Object { $_.TrimEnd('\', '/') }
if ($pathEntries -notcontains $binDirectory.TrimEnd('\', '/')) {
    Write-Host "Add $binDirectory to PATH to run the commands."
}
