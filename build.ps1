param(
    [string[]]$Goals = @('verify'),
    [string]$Project = $PSScriptRoot,
    [string]$Repository = (Join-Path $PSScriptRoot '.m2'),
    [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
if (!$JavaHome -or !(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\javac.exe'))) {
    throw 'Set JAVA_HOME or pass -JavaHome pointing to JDK 21 or 25.'
}
$mavenPath = Join-Path $PSScriptRoot '..\.tools\apache-maven-3.10.0\bin\mvn.cmd'
if (!(Test-Path -LiteralPath $mavenPath)) {
    $mavenPath = (Get-Command mvn.cmd -ErrorAction Stop).Source
}
$previousJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JavaHome
    Push-Location -LiteralPath $Project
    & $mavenPath '-B' '-ntp' '-s' "$PSScriptRoot\settings.xml" '-gs' "$PSScriptRoot\settings.xml" "-Dmaven.repo.local=$Repository" @Goals
    if ($LASTEXITCODE -ne 0) { throw "Maven failed with exit code $LASTEXITCODE." }
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJava
}

