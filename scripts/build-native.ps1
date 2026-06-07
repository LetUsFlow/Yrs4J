param(
    [string] $Targets = "windows-x64,windows-aarch64,linux-x64,linux-aarch64",
    [string] $YrsVersion,
    [switch] $SkipFetch
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$gradleProperties = Join-Path $repoRoot "gradle.properties"

function Get-GradleProperty([string] $Name) {
    $line = Get-Content $gradleProperties | Where-Object { $_ -match "^$Name=" } | Select-Object -First 1
    if (-not $line) {
        throw "Could not find '$Name' in $gradleProperties"
    }
    return ($line -split "=", 2)[1].Trim()
}

if (-not $YrsVersion) {
    $linuxVersion = Get-GradleProperty "nativeLinuxVersion"
    $windowsVersion = Get-GradleProperty "nativeWindowsVersion"
    if ($linuxVersion -ne $windowsVersion) {
        throw "nativeLinuxVersion ($linuxVersion) and nativeWindowsVersion ($windowsVersion) must match, or pass -YrsVersion explicitly."
    }
    $YrsVersion = $linuxVersion
}

$sourceDir = Join-Path $repoRoot "build\native-src\y-crdt"
$linuxTargetDir = Join-Path $repoRoot "build\native-target\linux"

function Invoke-LoggedCommand([string] $FilePath, [string[]] $Arguments, [string] $WorkingDirectory = $repoRoot) {
    Write-Host ">> $FilePath $($Arguments -join ' ')"
    Push-Location $WorkingDirectory
    try {
        & $FilePath @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Command failed with exit code $LASTEXITCODE`: $FilePath $($Arguments -join ' ')"
        }
    } finally {
        Pop-Location
    }
}

function Convert-ToWslPath([string] $Path) {
    $resolved = [System.IO.Path]::GetFullPath($Path)
    if ($resolved -notmatch "^([A-Za-z]):\\(.*)$") {
        throw "Cannot convert non-drive Windows path '$Path' to a WSL mount path."
    }

    $drive = $matches[1].ToLowerInvariant()
    $rest = $matches[2] -replace "\\", "/"
    return "/mnt/$drive/$rest"
}

function Initialize-Source {
    if ($SkipFetch -and -not (Test-Path $sourceDir)) {
        throw "-SkipFetch was passed, but $sourceDir does not exist."
    }

    if (-not (Test-Path $sourceDir)) {
        New-Item -ItemType Directory -Force (Split-Path $sourceDir) | Out-Null
        Invoke-LoggedCommand "git" @("clone", "--depth", "1", "--branch", "v$YrsVersion", "https://github.com/y-crdt/y-crdt.git", $sourceDir)
    } elseif (-not $SkipFetch) {
        Invoke-LoggedCommand "git" @("-C", $sourceDir, "fetch", "--tags", "--depth", "1", "origin", "v$YrsVersion")
        Invoke-LoggedCommand "git" @("-C", $sourceDir, "checkout", "--force", "v$YrsVersion")
    }
}

function Build-WindowsNative([string] $RustTarget, [string] $ResourcePrefix) {
    Invoke-LoggedCommand "rustup" @("target", "add", $RustTarget)
    Invoke-LoggedCommand "cargo" @("build", "--manifest-path", (Join-Path $sourceDir "yffi\Cargo.toml"), "--release", "--target", $RustTarget)

    $builtDll = Join-Path $sourceDir "target\$RustTarget\release\yrs.dll"
    if (-not (Test-Path $builtDll)) {
        throw "Expected Windows native library was not produced: $builtDll"
    }

    $destination = Join-Path $repoRoot "yrs4j-native-windows\src\main\resources\$ResourcePrefix\libyrs.dll"
    New-Item -ItemType Directory -Force (Split-Path $destination) | Out-Null
    Copy-Item -Force $builtDll $destination
    Write-Host "Copied $destination"
}

function Build-LinuxNative([string] $RustTarget, [string] $ResourcePrefix) {
    $wslSourceDir = Convert-ToWslPath $sourceDir
    $wslTargetDir = Convert-ToWslPath $linuxTargetDir

    $linkerExports = ""
    if ($RustTarget -eq "aarch64-unknown-linux-gnu") {
        $linkerExports = "export CC_aarch64_unknown_linux_gnu=aarch64-linux-gnu-gcc; export CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER=aarch64-linux-gnu-gcc;"
    }

    $command = @"
set -euo pipefail
rustup target add $RustTarget
mkdir -p '$wslTargetDir'
$linkerExports
export CARGO_TARGET_DIR='$wslTargetDir'
cargo build --manifest-path '$wslSourceDir/yffi/Cargo.toml' --release --target $RustTarget
"@

    Invoke-LoggedCommand "wsl" @("bash", "-lc", $command)

    $builtSo = Join-Path $linuxTargetDir "$RustTarget\release\libyrs.so"
    if (-not (Test-Path $builtSo)) {
        throw "Expected Linux native library was not produced: $builtSo"
    }

    $destination = Join-Path $repoRoot "yrs4j-native-linux\src\main\resources\$ResourcePrefix\libyrs.so"
    New-Item -ItemType Directory -Force (Split-Path $destination) | Out-Null
    Copy-Item -Force $builtSo $destination
    Write-Host "Copied $destination"
}

$selectedTargets = $Targets.Split(",") | ForEach-Object { $_.Trim().ToLowerInvariant() } | Where-Object { $_ }
Initialize-Source

foreach ($target in $selectedTargets) {
    switch ($target) {
        "windows-x64" { Build-WindowsNative "x86_64-pc-windows-msvc" "win32-x86-64" }
        "windows-aarch64" { Build-WindowsNative "aarch64-pc-windows-msvc" "win32-aarch64" }
        "linux-x64" { Build-LinuxNative "x86_64-unknown-linux-gnu" "linux-x86-64" }
        "linux-aarch64" { Build-LinuxNative "aarch64-unknown-linux-gnu" "linux-aarch64" }
        default { throw "Unknown target '$target'. Use one or more of: windows-x64, windows-aarch64, linux-x64, linux-aarch64." }
    }
}
