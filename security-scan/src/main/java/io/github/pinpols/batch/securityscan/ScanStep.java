package io.github.pinpols.batch.securityscan;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public enum ScanStep {
  SECRET("secret"),
  DEPS("deps"),
  SAST("sast"),
  FILESYSTEM("filesystem"),
  IMAGE("image"),
  DAST("dast");

  private final String displayName;

  ScanStep(String displayName) {
    this.displayName = displayName;
  }

  public String displayName() {
    return displayName;
  }

  public List<ExternalCommand> buildCommands(SecurityScanOptions options) {
    Path root = options.root();
    return switch (this) {
      case SECRET -> secretScanCommands(options, root);
      case DEPS -> dependencyCheckCommands(options, root);
      case SAST -> semgrepCommands(options, root);
      case FILESYSTEM -> filesystemCommands(options, root);
      case IMAGE -> imageCommands(options, root);
      case DAST -> dastCommands(options, root);
    };
  }

  private List<ExternalCommand> dependencyCheckCommands(SecurityScanOptions options, Path root) {
    // R7 OSS 扫描 followup：纯 Java backend 不需要 JS / Node / Ruby analyzer。
    // 关掉它们避免 RetireJS 拉 raw.githubusercontent.com（VPN/代理 DNS 劫持时整个扫描 abort）
    // 以及 NodeJS / Ruby 等 noise，同时显著降低扫描耗时。
    List<String> arguments = list(
        options.mvnCommand(),
        "-P",
        "compliance",
        "org.owasp:dependency-check-maven:check",
        "-DfailBuildOnCVSS=7",
        "-DoutputDirectory=" + options.reportDir().resolve("dependency-check"),
        // scripts/ci/dependency-check-suppressions.xml 已审计 CVE 白名单(OTel semconv / Log4j1 桥 / Tomcat
        // etc),需显式传入
        "-DsuppressionFile=" + root.resolve("scripts/ci/dependency-check-suppressions.xml"),
        // dependency-check 12.x 把 -Dformat 的 CSV 写法当单一模板名解析（HTML,JSON.vsl
        // 文件找不到），必须改用 -Dformats 重复传 或 ALL。这里用 ALL 一次生成全格式报告。
        "-Dformats=ALL",
        "-DretireJsAnalyzerEnabled=false",
        "-DnodeAnalyzerEnabled=false",
        "-DnodeAuditAnalyzerEnabled=false",
        "-DyarnAuditAnalyzerEnabled=false",
        "-DpnpmAuditAnalyzerEnabled=false",
        "-DrubygemsAnalyzerEnabled=false",
        "-DbundleAuditAnalyzerEnabled=false",
        "-DassemblyAnalyzerEnabled=false");
    ExternalCommand command = new ExternalCommand(this, "dependency-check", arguments, root);
    return List.of(command);
  }

  private List<ExternalCommand> semgrepCommands(SecurityScanOptions options, Path root) {
    List<String> arguments = list(
        options.semgrepCommand(),
        "scan",
        "--config",
        "auto",
        "--json",
        "--output",
        options.reportDir().resolve("semgrep.json").toString(),
        root.toString());
    ExternalCommand command = new ExternalCommand(this, "semgrep", arguments, root);
    try (InputStream source = ScanStep.class.getResourceAsStream("/semgrep_report.py")) {
      if (source == null) {
        throw new IllegalStateException("Missing Semgrep report checker");
      }
      String checker = new String(source.readAllBytes(), StandardCharsets.UTF_8);
      ExternalCommand reportCheck = new ExternalCommand(
          this,
          "semgrep-report",
          list(
              "python3",
              "-c",
              checker,
              options.reportDir().resolve("semgrep.json").toString()),
          root);
      return List.of(command, reportCheck);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot load Semgrep report checker", e);
    }
  }

  private List<ExternalCommand> filesystemCommands(SecurityScanOptions options, Path root) {
    List<String> filesystemArguments = list(
        options.trivyCommand(),
        "fs",
        root.toString(),
        "--scanners",
        "vuln,secret",
        "--ignorefile",
        root.resolve(".trivyignore").toString(),
        "--severity",
        "CRITICAL,HIGH",
        "--exit-code",
        "1",
        "--format",
        "json",
        "--output",
        options.reportDir().resolve("trivy-fs.json").toString());
    List<String> configArguments = list(
        options.trivyCommand(),
        "config",
        root.toString(),
        "--ignorefile",
        root.resolve(".trivyignore.yaml").toString(),
        "--severity",
        "CRITICAL,HIGH",
        "--exit-code",
        "1",
        "--format",
        "json",
        "--output",
        options.reportDir().resolve("trivy-config.json").toString());
    return List.of(
        new ExternalCommand(this, "trivy-fs", filesystemArguments, root),
        new ExternalCommand(this, "trivy-config", configArguments, root));
  }

  private List<ExternalCommand> imageCommands(SecurityScanOptions options, Path root) {
    List<String> dockerArguments = list(
        options.dockerCommand(),
        "build",
        "-f",
        "deploy/docker/Dockerfile.app",
        "--build-arg",
        "MODULE=batch-console-api",
        "-t",
        options.imageName(),
        root.toString());
    ExternalCommand dockerCommand =
        new ExternalCommand(this, "docker-build", dockerArguments, root);

    List<String> trivyArguments = list(
        options.trivyCommand(),
        "image",
        options.imageName(),
        "--format",
        "json",
        "--output",
        options.reportDir().resolve("trivy-image.json").toString());
    ExternalCommand trivyCommand = new ExternalCommand(this, "trivy-image", trivyArguments, root);
    return List.of(dockerCommand, trivyCommand);
  }

  private List<ExternalCommand> dastCommands(SecurityScanOptions options, Path root) {
    List<String> arguments = dastCommand(options);
    ExternalCommand command = new ExternalCommand(this, "zap-baseline", arguments, root);
    return List.of(command);
  }

  private List<ExternalCommand> secretScanCommands(SecurityScanOptions options, Path root) {
    // Git 历史扫描不会读取未提交文件；目录扫描补齐当前工作树，两份报告分别保留。
    ExternalCommand history = new ExternalCommand(
        this, "gitleaks-history", gitleaksCommand(options, root, "git", "gitleaks.json"), root);
    ExternalCommand worktree = new ExternalCommand(
        this,
        "gitleaks-worktree",
        gitleaksCommand(options, root, "dir", "gitleaks-worktree.json"),
        root);
    return List.of(history, worktree);
  }

  private static List<String> gitleaksCommand(
      SecurityScanOptions options, Path root, String mode, String reportName) {
    // dir 使用工作目录相对路径，以便精确路径豁免和报告指纹与 Git 模式一致。
    String scanTarget = "dir".equals(mode) ? "." : root.toString();
    List<String> arguments = new ArrayList<>(list(options.gitleaksCommand(), mode, scanTarget));
    Path configPath = root.resolve(".gitleaks.toml");
    if (Files.exists(configPath)) {
      arguments.add("--config");
      arguments.add(configPath.toString());
    }
    arguments.addAll(list(
        "--redact",
        "--no-banner",
        "--report-format",
        "json",
        "--report-path",
        options.reportDir().resolve(reportName).toString()));
    return arguments;
  }

  private static List<String> dastCommand(SecurityScanOptions options) {
    List<String> command = new ArrayList<>(list(
        options.dockerCommand(),
        "run",
        "--rm",
        "-t",
        "-v",
        options.reportDir().toString() + ":/zap/wrk"));
    String scan = options.zapScan().toLowerCase(Locale.ROOT);
    boolean apiScan = "api".equals(scan);
    boolean apiScanUsesLocalSpec = apiScan && isLocalSpec(options.zapApiSpec());
    if (apiScanUsesLocalSpec) {
      command.add("-v");
      command.add(options.root().toString() + ":/zap/src:ro");
    }
    command.add(options.zapImage());
    command.add(
        switch (scan) {
          case "api" -> "zap-api-scan.py";
          case "full" -> "zap-full-scan.py";
          default -> "zap-baseline.py";
        });
    command.add("-t");
    command.add(apiScan ? zapApiTarget(options) : options.targetUrl());
    if (apiScan) {
      command.add("-f");
      command.add("openapi");
    }
    command.add("-r");
    Path reportPath = Path.of(options.zapReport());
    String reportFileName = reportPath.getFileName().toString();
    command.add(reportFileName);
    if (options.zapAuthHeaderValue() != null && !options.zapAuthHeaderValue().isBlank()) {
      command.add("-z");
      command.add("-config replacer.full_list(0).description=batch-console-auth"
          + " -config replacer.full_list(0).enabled=true"
          + " -config replacer.full_list(0).matchtype=REQ_HEADER"
          + " -config replacer.full_list(0).matchstr=" + options.zapAuthHeaderName()
          + " -config replacer.full_list(0).replacement=" + options.zapAuthHeaderValue());
    }
    return command;
  }

  private static String zapApiTarget(SecurityScanOptions options) {
    if (!isLocalSpec(options.zapApiSpec())) {
      return options.zapApiSpec();
    }
    Path root = options.root().toAbsolutePath().normalize();
    Path spec = Path.of(options.zapApiSpec()).toAbsolutePath().normalize();
    if (!spec.startsWith(root)) {
      throw new IllegalArgumentException("Local --zap-api-spec must be under --root: " + spec);
    }
    Path relativeSpec = root.relativize(spec);
    String relativeSpecPath = relativeSpec.toString();
    String containerSpecPath = relativeSpecPath.replace('\\', '/');
    return "/zap/src/" + containerSpecPath;
  }

  private static boolean isLocalSpec(String value) {
    return value != null
        && !value.startsWith("http://")
        && !value.startsWith("https://")
        && !value.startsWith("/zap/");
  }

  private static List<String> list(String first, String... rest) {
    List<String> command = new ArrayList<>(rest.length + 1);
    command.add(first);
    for (String value : rest) {
      command.add(value);
    }
    return command;
  }
}
