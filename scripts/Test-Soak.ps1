param(
    [ValidateRange(1,1440)][int]$Minutes = 60,
    [string]$Project = (Split-Path -Parent $PSScriptRoot),
    [string]$Repository = (Join-Path $PSScriptRoot '../.m2'),
    [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $Project).Path
$JavaHome = (Resolve-Path -LiteralPath $JavaHome).Path
New-Item -ItemType Directory -Path (Join-Path $root 'target/soak') -Force | Out-Null
# Record JVM health without collecting other processes, environment variables or launch arguments.
& (Join-Path $JavaHome 'bin/jfr.exe') configure --input (Join-Path $JavaHome 'lib/jfr/profile.jfc') --output (Join-Path $root 'target/soak/profile.jfc') 'jdk.SystemProcess#enabled=false' 'jdk.InitialEnvironmentVariable#enabled=false' 'jdk.InitialSystemProperty#enabled=false' 'jdk.JVMInformation#enabled=false'
if ($LASTEXITCODE -ne 0) { throw 'Flight Recorder profile creation failed.' }
# Preserve the test harness's module access and agent arguments; cap only this isolated JVM.
$jvm = '-Xmx512m -Xlog:jfr+startup=off @{jenkins.addOpens} @{jenkins.insaneHook} @{jenkins.javaAgent} -XX:StartFlightRecording=filename=target/soak/soak.jfr,settings=target/soak/profile.jfc,dumponexit=true,maxsize=64m'
& (Join-Path (Split-Path -Parent $PSScriptRoot) 'build.ps1') -Project $root -Repository $Repository -JavaHome $JavaHome -Goals @(
    'test', '-Dtest=PluginSoakTest', '-Dpolicy.soak=true',
    "-Dpolicy.soak.seconds=$($Minutes * 60)", '-Dpolicy.soak.warmupSeconds=60',
    "-Djenkins.test.timeout=$($Minutes * 60 + 300)",
    "-DforkedProcessTimeoutInSeconds=$($Minutes * 60 + 360)", "-DargLine=$jvm"
)
if (!(Test-Path -LiteralPath (Join-Path $root 'target/soak/PASS'))) { throw 'The soak did not pass.' }
