param(
    [Parameter(Mandatory)][pscredential]$AdminCredential,
    [string]$Server = 'http://127.0.0.1:19000',
    [string]$ProjectKey = 'node-property-enforcer'
)
$ErrorActionPreference = 'Stop'
$credentialText = $AdminCredential.UserName + ':' + $AdminCredential.GetNetworkCredential().Password
$headers = @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($credentialText)) }
$gateName = 'Node Property Enforcer Strict'

function Get-Sonar([string]$Path) {
    Invoke-RestMethod -Uri "$Server/api/$Path" -Headers $headers
}
function Set-Sonar([string]$Path, [hashtable]$Body) {
    Invoke-RestMethod -Method Post -Uri "$Server/api/$Path" -Headers $headers -Body $Body
}

if ((Get-Sonar 'system/status').status -ne 'UP') { throw 'SonarQube is not ready.' }
$projects = Get-Sonar "projects/search?projects=$ProjectKey"
if ($projects.components.Count -eq 0) {
    Set-Sonar 'projects/create' @{project=$ProjectKey; name='Node Property Enforcer'; visibility='private'} | Out-Null
}
$gates = Get-Sonar 'qualitygates/list'
if (!($gates.qualitygates | Where-Object name -eq $gateName)) {
    Set-Sonar 'qualitygates/create' @{name=$gateName} | Out-Null
}
$current = Get-Sonar ('qualitygates/show?name=' + [uri]::EscapeDataString($gateName))
foreach ($condition in $current.conditions) {
    Set-Sonar 'qualitygates/delete_condition' @{id=$condition.id} | Out-Null
}
foreach ($metric in @('violations','new_violations','bugs','vulnerabilities','code_smells','duplicated_lines_density','new_duplicated_lines_density')) {
    Set-Sonar 'qualitygates/create_condition' @{gateName=$gateName;metric=$metric;op='GT';error='0'} | Out-Null
}
foreach ($metric in @('coverage','line_coverage','branch_coverage','new_coverage','new_line_coverage','new_branch_coverage','security_hotspots_reviewed','new_security_hotspots_reviewed')) {
    Set-Sonar 'qualitygates/create_condition' @{gateName=$gateName;metric=$metric;op='LT';error='100'} | Out-Null
}
Set-Sonar 'qualitygates/select' @{gateName=$gateName;projectKey=$ProjectKey} | Out-Null
Set-Sonar 'settings/set' @{component=$ProjectKey;key='sonar.qualitygate.ignoreSmallChanges';value='false'} | Out-Null
Set-Sonar 'settings/set' @{component=$ProjectKey;key='sonar.xml.file.suffixes';values='.xml,.jelly'} | Out-Null

foreach ($language in @('java','xml','web','secrets')) {
    $profiles = Get-Sonar "qualityprofiles/search?language=$language"
    $profile = $profiles.profiles | Where-Object name -eq $gateName
    if (!$profile) {
        $profile = (Set-Sonar 'qualityprofiles/create' @{language=$language;name=$gateName}).profile
    }
    Set-Sonar 'qualityprofiles/change_parent' @{language=$language;qualityProfile=$gateName;parentQualityProfile='Sonar way comprehensive'} | Out-Null
    Set-Sonar 'qualityprofiles/add_project' @{language=$language;qualityProfile=$gateName;project=$ProjectKey} | Out-Null
    if ($language -eq 'java') {
        foreach ($rule in @(@('java:S3776','Threshold=5'),@('java:S1541','Threshold=5'),@('java:S138','max=30'))) {
            Set-Sonar 'qualityprofiles/activate_rule' @{key=$profile.key;rule=$rule[0];params=$rule[1]} | Out-Null
        }
    }
}
$evidence = Join-Path $PSScriptRoot '..\target\sonar-evidence'
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
Get-Sonar ('qualitygates/show?name=' + [uri]::EscapeDataString($gateName)) | ConvertTo-Json -Depth 20 | Set-Content (Join-Path $evidence 'quality-gate.json')
Get-Sonar "qualityprofiles/search?project=$ProjectKey" | ConvertTo-Json -Depth 20 | Set-Content (Join-Path $evidence 'quality-profiles.json')
Write-Output "Configured dedicated project $ProjectKey with strict gate and comprehensive profiles."

