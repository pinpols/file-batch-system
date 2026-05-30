param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
$ErrorActionPreference = "Stop"
& "$PSScriptRoot\observability\up.ps1" @Args
exit $LASTEXITCODE
