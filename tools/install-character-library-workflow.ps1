$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$template = Join-Path $PSScriptRoot "workflow-templates\update-character-library.yml"
$workflowDir = Join-Path $repoRoot ".github\workflows"
$destination = Join-Path $workflowDir "update-character-library.yml"

if (-not (Test-Path -LiteralPath $template)) {
    throw "Workflow template not found: $template"
}

New-Item -ItemType Directory -Force -Path $workflowDir | Out-Null
Copy-Item -LiteralPath $template -Destination $destination -Force
Write-Host "Installed: $destination"
Write-Host "Commit and push this file to enable the scheduled character/UB data updater."
