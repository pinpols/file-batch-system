param()
$ErrorActionPreference = "Stop"
Import-Module "$PSScriptRoot\LocalDev.psm1" -Force -DisableNameChecking

$root = Get-RepoRoot
Set-Location $root

$runtimeJarDir = Join-Path $root "build\runtime-jars"
Ensure-Directory $runtimeJarDir
$mvn = Get-MavenCommand
Enable-MavenNativeAccessForNewJdk

if ((Get-EnvValue "CLEAN" "0") -eq "1") {
  $cleanGoal = @("clean")
  Write-Host "==> Maven 打包应用模块（clean package -DskipTests，CLEAN=1）..."
} else {
  $cleanGoal = @()
  Write-Host "==> Maven 打包应用模块（增量 package -DskipTests；强制清理用 `$env:CLEAN='1'）..."
}

$mvnArgs = @(
  "-q",
  "-DskipTests",
  "-Dcyclonedx.skip=true",
  "-Dlicense.skip=true",
  "-Dmaven.javadoc.skip=true",
  "-Dflatten.skip=true",
  "-pl",
  "batch-trigger,batch-orchestrator,batch-worker-import,batch-worker-export,batch-worker-process,batch-worker-dispatch,batch-worker-spi,batch-console-api",
  "-am"
) + $cleanGoal + @("package", "-T", "2C")
& $mvn @mvnArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "==> 复制可执行 jar 到 build/runtime-jars/..."
$modules = @("batch-orchestrator", "batch-trigger", "batch-console-api", "batch-worker-import", "batch-worker-export", "batch-worker-process", "batch-worker-dispatch", "batch-worker-spi")
$names = @("orchestrator", "trigger", "console", "worker-import", "worker-export", "worker-process", "worker-dispatch", "worker-spi")

for ($i = 0; $i -lt $modules.Count; $i++) {
  $module = $modules[$i]
  $name = $names[$i]
  $target = Join-Path $root "$module\target"
  $jar = Get-ChildItem $target -Filter "$module-*-exec.jar" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch "sources|javadoc" } |
    Select-Object -First 1
  if (-not $jar) {
    $jar = Get-ChildItem $target -Filter "$module-*.jar" -ErrorAction SilentlyContinue |
      Where-Object { $_.Name -notmatch "sources|javadoc|\.original$|-exec\.jar$" } |
      Select-Object -First 1
  }
  if (-not $jar) { throw "未找到可执行 jar: $module/target/$module-*.jar" }
  if ($jar.Length -lt 4096) { throw "$($jar.FullName) 仅 $($jar.Length) 字节，疑似损坏。请执行 `$env:CLEAN='1'; .\scripts\ps1\local\build-apps.ps1" }
  Copy-Item $jar.FullName (Join-Path $runtimeJarDir "$name.jar") -Force
  Write-Host "  $name.jar <- $($jar.Name)"
}

Write-Host "==> 构建完成（jar 已输出到 build/runtime-jars/）"
