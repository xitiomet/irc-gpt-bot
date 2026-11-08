param(
    [string] $Version = '1.0',
    [string] $ImageName = 'irc-gpt-bot',
    # 'auto' detects the CRT from $env:VCToolsRedistDir (set in a Visual Studio developer prompt)
    [string] $VCRuntimeDirectory = 'auto',
    [string] $MakeNSIS = 'makensis.exe'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$target = Join-Path $root 'target'
$executable = Join-Path $target "$ImageName.exe"
if (!(Test-Path -LiteralPath $executable -PathType Leaf)) {
    throw "Missing $executable. Build the Windows native image before packaging."
}
if ($VCRuntimeDirectory -eq 'auto' -or !$VCRuntimeDirectory) {
    $VCRuntimeDirectory = $null
    if ($env:VCToolsRedistDir) {
        $runtime = @(Get-ChildItem (Join-Path $env:VCToolsRedistDir 'x64\Microsoft.VC*.CRT') -Directory)
        if ($runtime.Count -eq 1) {
            $VCRuntimeDirectory = $runtime[0].FullName
        }
    }
}
if (!$VCRuntimeDirectory -or !(Test-Path (Join-Path $VCRuntimeDirectory 'vcruntime140.dll'))) {
    throw 'Run from a Visual Studio x64 developer prompt, or pass -Dwindows.vcRuntimeDir=<dir> pointing to the x64 Visual C++ CRT directory (containing vcruntime140.dll).'
}
$compiler = (Get-Command $MakeNSIS -ErrorAction SilentlyContinue).Source
if (!$compiler) {
    # NSIS's installer does not add itself to PATH, so check its registered and default install locations
    $candidates = @()
    foreach ($key in 'HKLM:\SOFTWARE\WOW6432Node\NSIS', 'HKLM:\SOFTWARE\NSIS') {
        $installDir = (Get-ItemProperty -LiteralPath $key -ErrorAction SilentlyContinue).'(default)'
        if ($installDir) {
            $candidates += Join-Path $installDir 'makensis.exe'
        }
    }
    $candidates += Join-Path ${env:ProgramFiles(x86)} 'NSIS\makensis.exe'
    $candidates += Join-Path $env:ProgramFiles 'NSIS\makensis.exe'
    $compiler = $candidates | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) } | Select-Object -First 1
}
if (!$compiler) {
    throw "Unable to find makensis.exe. Install NSIS (https://nsis.sourceforge.io/), add it to PATH, or pass -Dwindows.makeNSIS=<path to makensis.exe>."
}
# Use a fresh staging directory so stale DLLs from older builds cannot leak into the installer.
$stage = Join-Path $target ('windows-installer-' + [guid]::NewGuid().ToString('N'))
$payload = Join-Path $stage 'payload'
New-Item -ItemType Directory -Path $payload | Out-Null
try {
    Copy-Item -LiteralPath $executable -Destination $payload
    $libraries = @(Get-ChildItem -LiteralPath $target -Filter '*.dll' -File)
    $libraries += @(Get-ChildItem -LiteralPath $VCRuntimeDirectory -Filter '*.dll' -File)
    foreach ($library in $libraries) {
        $destination = Join-Path $payload $library.Name
        if (Test-Path -LiteralPath $destination) {
            if ((Get-FileHash -LiteralPath $destination).Hash -ne (Get-FileHash -LiteralPath $library.FullName).Hash) {
                throw "Conflicting DLLs named $($library.Name): $($library.FullName)"
            }
        } else {
            Copy-Item -LiteralPath $library.FullName -Destination $destination
        }
    }
    # AWT font configuration for GUI mode (staged by the copy-jdk-fontconfig build step)
    $lib = Join-Path $target 'lib'
    New-Item -ItemType Directory -Path (Join-Path $payload 'lib') | Out-Null
    if (Test-Path -LiteralPath $lib) {
        Copy-Item -Path (Join-Path $lib '*') -Destination (Join-Path $payload 'lib') -Recurse
    }
    $output = Join-Path $target "IrcGPTBotSetup-$Version.exe"
    & $compiler "/DVERSION=$Version" "/DIMAGE_NAME=$ImageName" "/DPAYLOAD_DIR=$payload" "/DOUTPUT_FILE=$output" (Join-Path $PSScriptRoot 'irc-gpt-bot.nsi')
    if ($LASTEXITCODE -ne 0) {
        throw "NSIS failed with exit code $LASTEXITCODE."
    }
    Write-Host "Generated: $output"
} finally {
    Remove-Item -LiteralPath $stage -Recurse -Force
}
