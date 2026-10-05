<#
.SYNOPSIS
  One-line status of the external-relay process. Read-only: calls only GET /health.
  Reports whether the thing listening on the configured port is RunningAI-managed, some other
  process (e.g. the pre-existing ad hoc legacy watchface-relay instance, see
  tools/external-relay/README.md), or not running at all.
  Exit code 0 when healthy, 1 otherwise.
#>
[CmdletBinding()]
param([int]$Port)

. "$PSScriptRoot\..\RunningAI.Common.ps1"
. "$PSScriptRoot\RunningAI.ExternalRelay.Common.ps1"

if (-not $PSBoundParameters.ContainsKey('Port')) { $Port = Get-ExternalRelayConfiguredPort }

$healthy = Test-ExternalRelayHealth $Port
$tracked = Get-TrackedProcessId 'external-relay' (Get-ExternalRelayMarkers)
$detail = if ($tracked) { " (managed, PID $tracked)" } elseif ($healthy) { ' (not started by RunningAI scripts)' } else { '' }
Write-Host ("{0,-16} {1}" -f 'ExternalRelay', (($(if ($healthy) { 'UP' } else { 'DOWN' })) + " 127.0.0.1:$Port$detail"))
exit $(if ($healthy) { $ExitCode.Ok } else { $ExitCode.Other })
