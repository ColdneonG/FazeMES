# Local tool selection for this computer; does not change system settings.
[CmdletBinding()]
param([ValidateSet('start','stop','init','face')][string]$Action='start',[switch]$Build)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME='C:\Users\mm\.jdks\ms-17.0.19'
$mavenBin='C:\Users\mm\.m2\wrapper\dists\apache-maven-3.9.16\0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0\bin'
$pwshBin='C:\Users\mm\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell'
$pythonBin=Join-Path $projectRoot '.runtime\tools-python\Scripts'
$env:PATH="$pythonBin;$env:JAVA_HOME\bin;$mavenBin;$pwshBin;C:\Program Files\nodejs;$env:PATH"
Set-Location -LiteralPath $projectRoot
switch ($Action) {
 'start' { & (Join-Path $PSScriptRoot 'start-local.ps1') -Build:$Build }
 'stop' { & (Join-Path $PSScriptRoot 'stop-local.ps1') }
 'init' { & (Join-Path $PSScriptRoot 'init-local.ps1') }
 'face' { & (Join-Path $PSScriptRoot 'init-face-auth.ps1') }
}
