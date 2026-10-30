package io.github.pinpols.batch.console.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * QF-2 守护测试:全仓扫 {@code new *Query(...)} 调用,字段 ≥ 5 的 record 在调用处出现 ≥ 2 个 {@code null} 占位时
 * fail,提示走静态工厂方法(详见 docs/agent-baseline.md §Query Record 工厂方法规约)。
 *
 * <p>白名单:
 *
 * <ul>
 *   <li>Query record 文件本身(record 内部 of-factory 实现允许 raw new)
 *   <li>测试 fixture / Excel SheetSpec / Registry 等声明式上下文(目前无命中,预留)
 * </ul>
 */
@DisplayName("查询记录构造规约守护: 字段较多的查询记录禁止在调用处内联构造并传多个空占位参数")
class QueryRecordConstructionConventionTest {

  private static final Path REPO_ROOT = Path.of(System.getProperty("user.dir")).getParent();
  private static final Pattern INLINE_NEW_QUERY =
      Pattern.compile("new\\s+([A-Z][A-Za-z0-9_]*Query)\\s*\\(([^;{}]*?)\\)", Pattern.DOTALL);

  // 白名单:文件名后缀(实际路径 endsWith 判断)
  private static final List<String> WHITELIST_SUFFIX = List.of(
      // record 内部工厂自身的 raw new 允许(允许工厂 implementation)
      "/domain/job/query/JobTaskQuery.java",
      "/domain/job/query/JobPartitionQuery.java",
      "/domain/job/query/JobStepInstanceQuery.java",
      "/domain/job/query/JobExecutionLogQuery.java",
      "/domain/notification/query/AlertEventQuery.java",
      "/domain/query/JobTaskQuery.java",
      "/domain/query/JobExecutionLogQuery.java",
      "/domain/query/OutboxEventQuery.java",
      "/domain/ops/query/OutboxEventQuery.java",
      "/domain/job/query/JobDefinitionQuery.java",
      "/domain/workflow/query/WorkflowDefinitionQuery.java",
      "/domain/workflow/query/WorkflowNodeQuery.java",
      "/domain/workflow/query/WorkflowEdgeQuery.java",
      "/domain/workflow/query/WorkflowRunQuery.java",
      "/domain/workflow/query/WorkflowNodeRunQuery.java",
      "/domain/workflow/query/WorkflowTopologyQuery.java",
      "/domain/governance/query/DeadLetterTaskQuery.java",
      "/domain/ops/query/RetryScheduleQuery.java",
      "/domain/file/query/FileErrorRecordQuery.java",
      // follow-up:复杂 record(8/11 字段)+ E2E support 暂行豁免,纳入后续 sprint 处理
      "/test/java/io/github/pinpols/batch/console/integration/JobInstanceQueryIntegrationTest.java",
      "/test/java/io/github/pinpols/batch/console/integration/ConsoleAiAuditServiceIntegrationTest.java",
      "/test/java/io/github/pinpols/batch/e2e/support/E2eOutboxPublishSupport.java",
      "/test/java/io/github/pinpols/batch/e2e/WorkerProcessRestartRecoveryE2eIT.java");

  @Test
  @DisplayName("全仓扫描调用处内联构造, 出现两个及以上空占位参数即判为违例")
  void shouldRejectInlineQueryConstruction_whenMultipleNullPlaceholders()
      throws IOException, InterruptedException {
    List<String> violations = new ArrayList<>();
    List<Path> sources = versionableJavaSources(REPO_ROOT);
    assertThat(sources).as("Git 源码枚举必须覆盖实际仓库，不能以空清单通过").hasSizeGreaterThan(100);
    for (Path source : sources) {
      if (!isWhitelisted(source)) {
        scanFile(source, violations);
      }
    }

    assertThat(violations)
        .as(
            "Query record inline new 不允许 ≥ 2 个 null 占位 — 必须走静态工厂(docs/agent-baseline.md §Query Record 工厂方法规约)\n"
                + "命中:\n  "
                + String.join("\n  ", violations))
        .isEmpty();
  }

  private static boolean isWhitelisted(Path file) {
    String s = file.toString().replace('\\', '/');
    return WHITELIST_SUFFIX.stream().anyMatch(s::endsWith);
  }

  private static void scanFile(Path file, List<String> violations) throws IOException {
    String src = Files.readString(file);
    Matcher m = INLINE_NEW_QUERY.matcher(src);
    while (m.find()) {
      String queryType = m.group(1);
      if (isOwnRecordFactory(file, src, queryType)) {
        continue;
      }
      String args = m.group(2);
      // 计 null 出现次数(以单词边界,排除 "isNull" 等)
      int nullCount = countNulls(args);
      if (nullCount >= 2) {
        // 取行号
        int lineno = src.substring(0, m.start()).split("\n", -1).length;
        violations.add(file.getFileName()
            + ":"
            + lineno
            + " — new "
            + m.group(1)
            + "(... "
            + nullCount
            + " null 占位 ...)");
      }
    }
  }

  private static List<Path> versionableJavaSources(Path root)
      throws IOException, InterruptedException {
    ProcessBuilder command = new ProcessBuilder(
        "git", "ls-files", "--cached", "--others", "--exclude-standard", "-z", "--", "*.java");
    Process git = command.directory(root.toFile()).redirectErrorStream(true).start();
    byte[] rawOutput = git.getInputStream().readAllBytes();
    String output = new String(rawOutput, StandardCharsets.UTF_8);
    int exitCode = git.waitFor();
    if (exitCode != 0) {
      throw new IOException("Git Java 源码枚举失败: " + output);
    }
    List<Path> sources = new ArrayList<>();
    for (String name : output.split("\0")) {
      if (!name.isEmpty()) {
        Path source = root.resolve(name);
        if (Files.isRegularFile(source)) {
          sources.add(source);
        }
      }
    }
    return sources.stream().distinct().toList();
  }

  private static boolean isOwnRecordFactory(Path file, String source, String queryType) {
    if (!file.getFileName().toString().equals(queryType + ".java")) {
      return false;
    }
    String declarations =
        source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//[^\\r\\n]*", "");
    String quotedType = Pattern.quote(queryType);
    Pattern declaration =
        Pattern.compile("(?m)^\\s*(?:public\\s+)?record\\s+" + quotedType + "\\s*\\(");
    return declaration.matcher(declarations).find();
  }

  @Test
  @DisplayName("只允许查询记录自己的工厂构造，外部调用及同文件构造另一查询仍拦截")
  void shouldKeepFactoryExceptionBoundToItsOwnRecord(@TempDir Path root) throws IOException {
    Path ownFactory = root.resolve("ExampleQuery.java");
    String construction = "new " + "ExampleQuery(null, null, null, null, null)";
    String factory = "record ExampleQuery(String a, String b, String c, String d, String e) {"
        + " static ExampleQuery all() { return " + construction + "; } }";
    Files.writeString(ownFactory, factory);
    List<String> ownViolations = new ArrayList<>();
    scanFile(ownFactory, ownViolations);
    assertThat(ownViolations).isEmpty();

    Path externalCaller = root.resolve("Caller.java");
    Files.writeString(externalCaller, factory);
    List<String> externalViolations = new ArrayList<>();
    scanFile(externalCaller, externalViolations);
    assertThat(externalViolations).hasSize(1);

    String anotherQuery = factory.replace("new ExampleQuery", "new AnotherQuery");
    Files.writeString(ownFactory, anotherQuery);
    List<String> otherRecordViolations = new ArrayList<>();
    scanFile(ownFactory, otherRecordViolations);
    assertThat(otherRecordViolations).hasSize(1);
  }

  @Test
  @DisplayName("Git 清单保留未忽略新源码和已跟踪忽略源码，排除快照及已删除源")
  void shouldRespectGitSourcesWithoutLosingTrackedIgnoredFiles(@TempDir Path root)
      throws IOException, InterruptedException {
    runGitFixture(root, "init", "--quiet");
    Path ignoreFile = root.resolve(".gitignore");
    Files.writeString(ignoreFile, "snapshots/\n");
    Path current = root.resolve("Current.java");
    Files.writeString(current, "class Current {}\n");
    Path snapshots = root.resolve("snapshots");
    Files.createDirectories(snapshots);
    Path tracked = snapshots.resolve("Tracked.java");
    Files.writeString(tracked, "class Tracked {}\n");
    runGitFixture(root, "add", "-f", "snapshots/Tracked.java");
    Path ignored = snapshots.resolve("Copy.java");
    Files.writeString(ignored, "class Copy {}\n");
    Path deleted = root.resolve("Deleted.java");
    Files.writeString(deleted, "class Deleted {}\n");
    runGitFixture(root, "add", "Deleted.java");
    Files.delete(deleted);

    List<Path> sources = versionableJavaSources(root);

    assertThat(sources).containsExactlyInAnyOrder(current, tracked);
    assertThat(sources).doesNotContain(ignored, deleted);
  }

  private static void runGitFixture(Path root, String... arguments)
      throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add("git");
    List<String> gitArguments = List.of(arguments);
    command.addAll(gitArguments);
    ProcessBuilder builder = new ProcessBuilder(command);
    Process git = builder.directory(root.toFile()).redirectErrorStream(true).start();
    byte[] rawOutput = git.getInputStream().readAllBytes();
    String output = new String(rawOutput, StandardCharsets.UTF_8);
    int exitCode = git.waitFor();
    if (exitCode != 0) {
      throw new IOException("临时 Git 夹具初始化失败: " + output);
    }
  }

  private static int countNulls(String args) {
    int count = 0;
    Matcher m = Pattern.compile("(?<![A-Za-z0-9_])null(?![A-Za-z0-9_])").matcher(args);
    while (m.find()) {
      count++;
    }
    return count;
  }
}
