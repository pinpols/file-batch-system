package io.github.pinpols.batch.common.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 守护 Mapper XML 的状态写入边界：目标状态由 Java 领域方法提供，SQL 只执行参数化更新。 */
@DisplayName("Mapper 状态写入约定:禁止 UPDATE SET 固定状态字面量")
class MapperStatusWriteConventionTest {

  private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();
  private static final List<String> SCAN_MODULES = List.of(
      "batch-trigger",
      "batch-orchestrator",
      "batch-worker/core",
      "batch-worker/import",
      "batch-worker/export",
      "batch-worker/process",
      "batch-worker/dispatch",
      "batch-worker/atomic",
      "batch-console-api");
  private static final Pattern STATEMENT_BLOCK =
      Pattern.compile("(?is)<(insert|update|select)\\b[^>]*id=\"([^\"]+)\"[^>]*>(.*?)</\\1>");
  private static final Pattern FIXED_STATUS_ASSIGNMENT =
      Pattern.compile("(?i)\\b(?:[a-z][a-z0-9_]*_status|status)\\s*=\\s*'[A-Z][A-Z0-9_]*'");
  private static final Pattern SET_KEYWORD = Pattern.compile("(?i)\\bset\\b");
  private static final Pattern WHERE_KEYWORD = Pattern.compile("(?i)\\bwhere\\b");

  @Test
  @DisplayName("全仓 Mapper 更新语句通过参数接收目标状态,不在 SET 中写死状态码")
  void shouldParameterizeTargetStatus_whenMapperUpdatesState() throws IOException {
    List<String> violations = new ArrayList<>();
    int scannedFiles = 0;
    for (String module : SCAN_MODULES) {
      Path mapperRoot = REPO_ROOT.resolve(module).resolve("src/main/resources/mapper");
      if (!Files.isDirectory(mapperRoot)) {
        continue;
      }
      try (Stream<Path> files = Files.walk(mapperRoot)) {
        for (Path file :
            files.filter(path -> path.toString().endsWith("Mapper.xml")).toList()) {
          scannedFiles++;
          collectViolations(file, violations);
        }
      }
    }

    assertThat(scannedFiles).as("Mapper XML 扫描范围必须有效").isGreaterThan(50);
    assertThat(violations)
        .as("状态更新必须由 Java 语义方法传入目标状态；特殊业务筛选可以保留在 WHERE/SELECT，" + "但不得在 UPDATE SET 中新增固定状态字面量")
        .isEmpty();
  }

  @Test
  @DisplayName("守卫能够识别大小写不同且带表别名的固定状态赋值")
  void shouldDetectFixedStatusAssignment_whenSqlUsesLiteral() {
    String mapper = """
        <update id="badTransition">
          UPDATE batch.example
             SET run_status = 'SUCCESS', updated_at = current_timestamp
           WHERE id = #{id}
        </update>
        """;

    assertThat(fixedAssignments(mapper)).containsExactly("badTransition:run_status = 'SUCCESS'");
  }

  private static void collectViolations(Path file, List<String> violations) throws IOException {
    String xml = Files.readString(file, StandardCharsets.UTF_8);
    String relative = REPO_ROOT.relativize(file).toString().replace('\\', '/');
    for (String violation : fixedAssignments(xml)) {
      violations.add(relative + "#" + violation);
    }
  }

  private static List<String> fixedAssignments(String xml) {
    List<String> violations = new ArrayList<>();
    Matcher statementMatcher = STATEMENT_BLOCK.matcher(withoutComments(xml));
    while (statementMatcher.find()) {
      String statementId = statementMatcher.group(2);
      String updateSql = statementMatcher.group(3);
      Matcher setMatcher = SET_KEYWORD.matcher(updateSql);
      if (!setMatcher.find()) {
        continue;
      }
      int setEnd = setMatcher.end();
      Matcher whereMatcher = WHERE_KEYWORD.matcher(updateSql);
      int whereIndex = whereMatcher.find(setEnd) ? whereMatcher.start() : -1;
      String assignments =
          whereIndex < 0 ? updateSql.substring(setEnd) : updateSql.substring(setEnd, whereIndex);
      Matcher assignmentMatcher = FIXED_STATUS_ASSIGNMENT.matcher(assignments);
      while (assignmentMatcher.find()) {
        violations.add(statementId + ":" + assignmentMatcher.group());
      }
    }
    return violations;
  }

  private static String withoutComments(String xml) {
    return xml.replaceAll("(?s)<!--.*?-->", "");
  }
}
