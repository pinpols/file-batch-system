package io.github.pinpols.batch.console.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * QF-2 守护测试:全仓扫 {@code new *Query(...)} 调用,字段 ≥ 5 的 record 在调用处出现 ≥ 2 个 {@code null} 占位时
 * fail,提示走静态工厂方法(详见 CLAUDE.md §Query Record 工厂方法规约)。
 *
 * <p>白名单:
 *
 * <ul>
 *   <li>Query record 文件本身(record 内部 of-factory 实现允许 raw new)
 *   <li>测试 fixture / Excel SheetSpec / Registry 等声明式上下文(目前无命中,预留)
 * </ul>
 */
class QueryRecordConstructionConventionTest {

  private static final Path REPO_ROOT = Path.of(System.getProperty("user.dir")).getParent();
  private static final Pattern INLINE_NEW_QUERY =
      Pattern.compile("new\\s+([A-Z][A-Za-z0-9_]*Query)\\s*\\(([^;{}]*?)\\)", Pattern.DOTALL);

  // 白名单:文件名后缀(实际路径 endsWith 判断)
  private static final List<String> WHITELIST_SUFFIX =
      List.of(
          // record 内部工厂自身的 raw new 允许(允许工厂 implementation)
          "/domain/job/query/JobTaskQuery.java",
          "/domain/job/query/JobPartitionQuery.java",
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
          // follow-up:复杂 record(8/11 字段)+ E2E support 临时豁免,留 QF 后续 sprint 处理
          "/test/java/io/github/pinpols/batch/console/integration/JobInstanceQueryIntegrationTest.java",
          "/test/java/io/github/pinpols/batch/console/integration/ConsoleAiAuditServiceIntegrationTest.java",
          "/test/java/io/github/pinpols/batch/e2e/support/E2eOutboxPublishSupport.java",
          "/test/java/io/github/pinpols/batch/e2e/WorkerProcessRestartRecoveryE2eIT.java");

  @Test
  void noInlineQueryConstructorWithMultipleNullPlaceholders() throws IOException {
    List<String> violations = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(REPO_ROOT)) {
      paths
          .filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".java"))
          .filter(p -> !p.toString().contains("/target/"))
          .filter(p -> !isWhitelisted(p))
          .forEach(p -> scanFile(p, violations));
    }

    assertThat(violations)
        .as(
            "Query record inline new 不允许 ≥ 2 个 null 占位 — 必须走静态工厂(CLAUDE.md §Query Record 工厂方法规约)\n"
                + "命中:\n  "
                + String.join("\n  ", violations))
        .isEmpty();
  }

  private static boolean isWhitelisted(Path file) {
    String s = file.toString().replace('\\', '/');
    return WHITELIST_SUFFIX.stream().anyMatch(s::endsWith);
  }

  private static void scanFile(Path file, List<String> violations) {
    String src;
    try {
      src = Files.readString(file);
    } catch (IOException e) {
      return;
    }
    Matcher m = INLINE_NEW_QUERY.matcher(src);
    while (m.find()) {
      String args = m.group(2);
      // 计 null 出现次数(以单词边界,排除 "isNull" 等)
      int nullCount = countNulls(args);
      if (nullCount >= 2) {
        // 取行号
        int lineno = src.substring(0, m.start()).split("\n", -1).length;
        violations.add(
            file.getFileName()
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

  private static int countNulls(String args) {
    int count = 0;
    Matcher m = Pattern.compile("(?<![A-Za-z0-9_])null(?![A-Za-z0-9_])").matcher(args);
    while (m.find()) {
      count++;
    }
    return count;
  }
}
