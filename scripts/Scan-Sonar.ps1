param(
    [Parameter(Mandatory)][pscredential]$AdminCredential,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Repository = (Join-Path $PSScriptRoot '..\.m2'),
    [string]$Project = (Split-Path -Parent $PSScriptRoot),
    [string]$Server = 'http://127.0.0.1:19000'
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $Project).Path
$projectKey = 'node-property-enforcer'
$tokenName = 'node-property-analysis-' + [guid]::NewGuid().ToString('N')
$credentialText = $AdminCredential.UserName + ':' + $AdminCredential.GetNetworkCredential().Password
$headers = @{Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($credentialText))}
$previousToken = $env:SONAR_TOKEN
$scanSucceeded = $false
$evidence = Join-Path $root 'target\sonar-evidence'
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
function Save-ScanResult([bool]$Succeeded) {
    [ordered]@{ succeeded=$Succeeded; completedAtUtc=[DateTime]::UtcNow.ToString('O'); project=$projectKey } |
        ConvertTo-Json | Set-Content (Join-Path $evidence 'scanner-result.json')
}
Save-ScanResult $false
$report = Join-Path $root 'target\site\jacoco\jacoco.xml'
if (!(Test-Path -LiteralPath $report)) { throw 'Run verify with -Penable-jacoco before analysis.' }
$javascriptReport = Join-Path $root 'target\js-coverage\lcov.info'
if (!(Test-Path -LiteralPath $javascriptReport)) { throw 'Run npm run coverage before analysis.' }
$token = Invoke-RestMethod -Method Post -Uri "$Server/api/user_tokens/generate" -Headers $headers -Body @{
    name=$tokenName; type='PROJECT_ANALYSIS_TOKEN'; projectKey=$projectKey
}
try {
    $env:SONAR_TOKEN = $token.token
    & (Join-Path (Split-Path -Parent $PSScriptRoot) 'build.ps1') -Project $root -JavaHome $JavaHome -Repository $Repository -Goals @(
        'org.sonarsource.scanner.maven:sonar-maven-plugin:5.8.0.7211:sonar',
        "-Dsonar.host.url=$Server", '-Dsonar.scanner.skipJreProvisioning=true',
        '-Dsonar.scm.exclusions.disabled=true',
        '-Dsonar.qualitygate.timeout=300'
    )
    $measures = Invoke-RestMethod -Uri "$Server/api/measures/component?component=$projectKey&metricKeys=ncloc" -Headers $headers
    $lines = @($measures.component.measures | Where-Object metric -eq 'ncloc')[0].value
    if ([int]$lines -lt 1) { throw 'Analysis indexed no source code; the result is not a valid scan.' }
    $scanSucceeded = $true
} finally {
    $env:SONAR_TOKEN = $previousToken
    Invoke-RestMethod -Method Post -Uri "$Server/api/user_tokens/revoke" -Headers $headers -Body @{name=$tokenName} | Out-Null
    foreach ($entry in @(
        @('gate-result','qualitygates/project_status?projectKey='),
        @('issues','issues/search?ps=500&componentKeys='),
        @('hotspots','hotspots/search?ps=500&project='),
        @('measures','measures/component?metricKeys=ncloc,coverage,line_coverage,branch_coverage,bugs,vulnerabilities,code_smells,duplicated_lines_density&component=')
    )) {
        Invoke-RestMethod -Uri ($Server + '/api/' + $entry[1] + $projectKey) -Headers $headers |
            ConvertTo-Json -Depth 30 | Set-Content (Join-Path $evidence ($entry[0] + '.json'))
    }
    Save-ScanResult $scanSucceeded
}

