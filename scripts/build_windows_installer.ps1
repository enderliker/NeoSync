# Copyright (c) NeoSync contributors
# SPDX-License-Identifier: LGPL-2.1-only
param([Parameter(Mandatory=$true)][string]$InstallerJar, [string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$taskRepo = Split-Path -Parent $PSScriptRoot
$taskJar = (Resolve-Path -LiteralPath $InstallerJar).Path
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $taskRepo 'build/windows-installer' }
$taskOutput = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $taskOutput | Out-Null
$taskHash = (Get-FileHash -LiteralPath $taskJar -Algorithm SHA256).Hash.ToLowerInvariant()
$taskName = [IO.Path]::GetFileNameWithoutExtension($taskJar)
if ($taskName -notmatch '^NeoSync-[0-9A-Za-z.+-]+-neoforge-[0-9.]+-installer$') { throw 'Select a NeoSync installer JAR.' }
$taskCompiler = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
if (-not (Test-Path -LiteralPath $taskCompiler)) { throw '.NET Framework compiler was not found.' }
$taskScratch = Join-Path $taskOutput ('.build-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskScratch | Out-Null
try {
    $taskInfo = Join-Path $taskScratch 'BuildInfo.cs'
    [IO.File]::WriteAllText($taskInfo, ('internal static class BuildInfo { internal const string Sha256 = "' + $taskHash + '"; }'))
    Add-Type -AssemblyName System.Drawing
    $taskPng = [IO.File]::ReadAllBytes((Join-Path $taskRepo 'docs/assets/neosync-icon.png'))
    $taskIcon = Join-Path $taskScratch 'neosync.ico'
    $taskStream = [IO.File]::Create($taskIcon)
    $taskWriter = [IO.BinaryWriter]::new($taskStream)
    try {
        $taskWriter.Write([uint16]0); $taskWriter.Write([uint16]1); $taskWriter.Write([uint16]1)
        $taskWriter.Write([byte]128); $taskWriter.Write([byte]128); $taskWriter.Write([byte]0); $taskWriter.Write([byte]0)
        $taskWriter.Write([uint16]1); $taskWriter.Write([uint16]32); $taskWriter.Write([uint32]$taskPng.Length); $taskWriter.Write([uint32]22)
        $taskWriter.Write($taskPng)
    } finally { $taskWriter.Dispose() }
    $taskExe = Join-Path $taskOutput ($taskName + '.exe')
    & $taskCompiler /nologo /target:winexe /platform:anycpu /optimize+ ('/out:' + $taskExe) ('/win32icon:' + $taskIcon) ('/win32manifest:' + (Join-Path $taskRepo 'installer/windows/installer.manifest')) /reference:System.Windows.Forms.dll ('/resource:' + $taskJar + ',NeoSync.Installer') (Join-Path $taskRepo 'installer/windows/Launcher.cs') $taskInfo
    if ($LASTEXITCODE -ne 0) { throw 'Windows installer compilation failed.' }
    [IO.File]::WriteAllText(($taskExe + '.jar.sha256'), ($taskHash + "`n"))
    Write-Output $taskExe
} finally {
    $taskResolved = [IO.Path]::GetFullPath($taskScratch)
    if (-not $taskResolved.StartsWith($taskOutput + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Scratch directory escaped the build output.' }
    Remove-Item -LiteralPath $taskResolved -Recurse -Force
}
