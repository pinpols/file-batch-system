param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
$ErrorActionPreference = "Stop"
& "$PSScriptRoot\observability\down.ps1" @Args
exit $LASTEXITCODE
