# Convenience entry point using JAVA_HOME/PATH; does not change system settings.
#requires -Version 7.0
[CmdletBinding()]
param([ValidateSet('start','stop','init','face')][string]$Action='start',[switch]$Build)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$pythonBin=Join-Path $projectRoot '.runtime\tools-python\Scripts'
if (Test-Path -LiteralPath $pythonBin -PathType Container) { $env:PATH="$pythonBin;$env:PATH" }
Push-Location -LiteralPath $projectRoot
try {
 switch ($Action) {
  'start' { & (Join-Path $PSScriptRoot 'start-local.ps1') -Build:$Build }
  'stop' { & (Join-Path $PSScriptRoot 'stop-local.ps1') }
  'init' { & (Join-Path $PSScriptRoot 'init-local.ps1') }
  'face' { & (Join-Path $PSScriptRoot 'init-face-auth.ps1') }
 }
} finally { Pop-Location }
