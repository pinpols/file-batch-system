package io.github.pinpols.batch.securityscan;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("安全扫描步骤命令构建:各步骤展示名与容器命令拼装,含 API 规格挂载路径")
class SecurityScanOrchestratorTest {

  @Test
  @DisplayName("动态扫描失败汇总隐藏认证头，扫描进程仍接收原值")
  void shouldHideDastCredentialInFailureSummary(@TempDir Path tempDir) throws IOException {
    Path argsFile = tempDir.resolve("docker-args.txt");
    Path fakeDocker = tempDir.resolve("docker-fake");
    Files.writeString(
        fakeDocker, "#!/bin/sh\nprintf '%s\\n' \"$@\" > '" + argsFile + "'\nexit 1\n");
    Files.setPosixFilePermissions(fakeDocker, PosixFilePermissions.fromString("rwx------"));
    String credential = "test-only-session-value";
    SecurityScanOptions options = SecurityScanOptions.parse(new String[] {
      "--mode=dast",
      "--root=" + tempDir,
      "--report-dir=" + tempDir,
      "--docker=" + fakeDocker,
      "--zap-auth-header-name=Cookie",
      "--zap-auth-header-value=" + credential
    });

    ScanReport report = new SecurityScanOrchestrator(new ProcessCommandExecutor()).run(options);

    assertThat(report.hasFailures()).isTrue();
    assertThat(Files.readString(argsFile)).contains(credential);
    assertThat(report.results().getFirst().commandLine()).doesNotContain(credential);
    ExternalCommand command = ScanStep.DAST.buildCommands(options).getFirst();
    assertThat(ScanResult.skipped(command, "dry-run").commandLine()).doesNotContain(credential);
  }

  @Test
  @DisplayName("SAST 汇总区分高危发现、中低危发现和扫描错误")
  void shouldSummarizeSemgrepReport(@TempDir Path tempDir) throws IOException {
    Path fixture = tempDir.resolve("fixture.json");
    Path fakeSemgrep = tempDir.resolve("semgrep-fake");
    Files.writeString(fakeSemgrep, "#!/bin/sh\ncp '" + fixture + "' \"$6\"\n");
    Files.setPosixFilePermissions(fakeSemgrep, PosixFilePermissions.fromString("rwx------"));
    SecurityScanOptions options = SecurityScanOptions.parse(new String[] {
      "--mode=sast", "--root=" + tempDir, "--report-dir=" + tempDir, "--semgrep=" + fakeSemgrep
    });
    SecurityScanOrchestrator orchestrator =
        new SecurityScanOrchestrator(new ProcessCommandExecutor());

    Files.writeString(fixture, "{\"results\":[],\"errors\":[]}");
    assertThat(orchestrator.run(options).hasFailures()).isFalse();
    Files.writeString(
        fixture, "{\"results\":[{\"extra\":{\"severity\":\"WARNING\"}}],\"errors\":[]}");
    assertThat(orchestrator.run(options).hasFailures()).isFalse();
    Files.writeString(
        fixture, "{\"results\":[{\"extra\":{\"severity\":\"ERROR\"}}],\"errors\":[]}");
    assertThat(orchestrator.run(options).hasFailures()).isTrue();
    Files.writeString(fixture, "{\"results\":[],\"errors\":[{\"message\":\"parse failed\"}]}");
    assertThat(orchestrator.run(options).hasFailures()).isTrue();
    Files.writeString(fixture, "not json");
    assertThat(orchestrator.run(options).hasFailures()).isTrue();
  }

  @Test
  @DisplayName("扫描进程失败时不把旧报告当作本次结果")
  void shouldNotEvaluateStaleReportAfterSemgrepFailure(@TempDir Path tempDir) throws IOException {
    Files.writeString(tempDir.resolve("semgrep.json"), "{\"results\":[],\"errors\":[]}");
    Path fakeSemgrep = tempDir.resolve("semgrep-fake");
    Files.writeString(fakeSemgrep, "#!/bin/sh\nexit 2\n");
    Files.setPosixFilePermissions(fakeSemgrep, PosixFilePermissions.fromString("rwx------"));
    SecurityScanOptions options = SecurityScanOptions.parse(new String[] {
      "--mode=sast",
      "--root=" + tempDir,
      "--report-dir=" + tempDir,
      "--semgrep=" + fakeSemgrep,
      "--continue-on-error"
    });

    ScanReport report = new SecurityScanOrchestrator(new ProcessCommandExecutor()).run(options);

    assertThat(report.hasFailures()).isTrue();
    assertThat(report.results()).hasSize(1);
    assertThat(report.results().getFirst().label()).isEqualTo("semgrep");
  }

  @Test
  @DisplayName("密钥扫描步骤:全历史与当前工作树分别执行并保存独立报告")
  void shouldBuildGitleaksCommand_whenSecretStep() {
    SecurityScanOptions options = new SecurityScanOptions(
        false,
        ScanMode.SECRET,
        Path.of(".").toAbsolutePath().normalize(),
        "http://localhost:8080",
        "batch-console-api:local",
        "ghcr.io/zaproxy/zaproxy:stable",
        "target/zap-report.html",
        "Authorization",
        null,
        "baseline",
        null,
        false,
        Path.of("target/security-scan"),
        "mvn",
        "gitleaks",
        "semgrep",
        "trivy",
        "docker",
        true,
        false);

    ScanStep step = ScanStep.SECRET;
    assertThat(step.displayName()).isEqualTo("secret");
    var commands = step.buildCommands(options);
    assertThat(commands).hasSize(2);
    assertThat(commands.get(0).commandLine().getFirst()).isEqualTo("gitleaks");
    assertThat(commands.get(0).commandLine()).contains("git");
    assertThat(commands.get(0).commandLine()).anyMatch(arg -> arg.endsWith("/gitleaks.json"));
    assertThat(commands.get(1).commandLine().getFirst()).isEqualTo("gitleaks");
    assertThat(commands.get(1).commandLine()).contains("dir");
    assertThat(commands.get(1).commandLine().get(2)).isEqualTo(".");
    assertThat(commands.get(1).commandLine())
        .anyMatch(arg -> arg.endsWith("/gitleaks-worktree.json"));
  }

  @Test
  @DisplayName("历史扫描失败时仍运行工作树扫描并汇总失败")
  void shouldScanWorktreeAfterHistoryFailure(@TempDir Path tempDir) throws IOException {
    Path fakeGitleaks = tempDir.resolve("gitleaks-fake");
    Files.writeString(fakeGitleaks, "#!/bin/sh\n[ \"$1\" = git ] && exit 1\nexit 0\n");
    Files.setPosixFilePermissions(fakeGitleaks, PosixFilePermissions.fromString("rwx------"));
    SecurityScanOptions options = SecurityScanOptions.parse(new String[] {
      "--mode=secret", "--root=" + tempDir, "--report-dir=" + tempDir, "--gitleaks=" + fakeGitleaks
    });

    ScanReport report = new SecurityScanOrchestrator(new ProcessCommandExecutor()).run(options);

    assertThat(report.results()).hasSize(2);
    assertThat(report.results().get(1).label()).isEqualTo("gitleaks-worktree");
    assertThat(report.hasFailures()).isTrue();
  }

  @Test
  @DisplayName("文件系统扫描分别校验漏洞密钥与配置，并使用仓库各自的精确豁免")
  void shouldBuildBlockingFilesystemCommands() {
    SecurityScanOptions options = SecurityScanOptions.parse(new String[] {"--mode=filesystem"});
    var commands = ScanStep.FILESYSTEM.buildCommands(options);

    assertThat(commands).hasSize(2);
    assertThat(commands.get(0).label()).isEqualTo("trivy-fs");
    assertThat(commands.get(0).commandLine()).contains("vuln,secret", "--exit-code");
    assertThat(commands.get(0).commandLine()).anyMatch(arg -> arg.endsWith("/.trivyignore"));
    assertThat(commands.get(1).label()).isEqualTo("trivy-config");
    assertThat(commands.get(1).commandLine()).anyMatch(arg -> arg.endsWith("/.trivyignore.yaml"));
    assertThat(commands.get(1).commandLine()).contains("--exit-code");
  }

  @Test
  @DisplayName("DAST API 扫描步骤:命令含 api 扫描脚本与 openapi 格式,并把规格目录只读挂载进容器")
  void shouldMountOpenApiSpec_whenDastApiScanStep() {
    Path root = Path.of(".").toAbsolutePath().normalize();
    SecurityScanOptions options = new SecurityScanOptions(
        false,
        ScanMode.DAST,
        root,
        "http://localhost:18080",
        "batch-console-api:local",
        "ghcr.io/zaproxy/zaproxy:stable",
        "target/zap-report.html",
        "Cookie",
        "batch_console_token=test",
        "api",
        "docs/api/console-api.openapi.yaml",
        true,
        Path.of("target/security-scan"),
        "mvn",
        "gitleaks",
        "semgrep",
        "trivy",
        "docker",
        true,
        false);

    var command = ScanStep.DAST.buildCommands(options).getFirst().commandLine();

    assertThat(command)
        .contains(
            "zap-api-scan.py",
            "-f",
            "openapi",
            root + ":/zap/src:ro",
            "/zap/src/docs/api/console-api.openapi.yaml");
  }
}
