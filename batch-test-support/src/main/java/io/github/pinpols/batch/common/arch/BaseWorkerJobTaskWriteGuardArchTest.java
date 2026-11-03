package io.github.pinpols.batch.common.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Worker 模块 {@code batch.job_task} 写入所有权守护(ARCH-20261103-03)。
 *
 * <p>{@code job_task} 归 Orchestrator 所有,任务状态只经 claim/report 收敛;Worker 只能写登记过的执行辅助列。本守护扫描
 * {@code src/main/resources/mapper/**.xml} 全部语句块(含 CTE 和 {@code <select>} 包装的写入):
 *
 * <ul>
 *   <li>{@code insert into} / {@code delete from batch.job_task} 一律违规;
 *   <li>{@code update batch.job_task} 的 SET 列必须属于 {@link #allowedJobTaskColumns()} 或 {@code updated_at}。
 * </ul>
 *
 * <p>白名单与 docs/architecture/runtime-module-communication.md「Worker 可写的 job_task 列」一一对应,新增列须同步登记并说明
 * RUNNING/invocation 谓词。只扫描 Mapper XML,不覆盖 Java 内联 SQL;行为正确性仍由各语句的专项测试保证。
 */
public abstract class BaseWorkerJobTaskWriteGuardArchTest {

  private static final Pattern STATEMENT_BLOCK = Pattern.compile(
      "<(insert|update|delete|select)\\b([^>]*)>(.*?)</\\1>",
      Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private static final Pattern STATEMENT_ID =
      Pattern.compile("id\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

  private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

  /** MyBatis 动态标签只影响拼接,不改变 SET 列;去掉标签保留内容后再解析。 */
  private static final Pattern XML_TAG = Pattern.compile("</?\\w+\\b[^>]*>");

  private static final Pattern JOB_TASK_INSERT_OR_DELETE = Pattern.compile(
      "\\b(insert\\s+into|delete\\s+from)\\s+batch\\.job_task\\b", Pattern.CASE_INSENSITIVE);

  private static final Pattern JOB_TASK_UPDATE = Pattern.compile(
      "\\bupdate\\s+batch\\.job_task\\b(?:\\s+(?:as\\s+)?\\w+)?\\s+set\\b",
      Pattern.CASE_INSENSITIVE);

  private static final Pattern SET_CLAUSE_END =
      Pattern.compile("\\b(from|where|returning)\\b", Pattern.CASE_INSENSITIVE);

  private static final Pattern ASSIGNED_COLUMN =
      Pattern.compile("^\\s*(?:\\w+\\.)?(\\w+)\\s*=", Pattern.CASE_INSENSITIVE);

  /** 本模块允许写入的 {@code job_task} 列;默认不允许任何列。 */
  protected Set<String> allowedJobTaskColumns() {
    return Set.of();
  }

  @Test
  void workerMapperMayOnlyWriteRegisteredJobTaskColumns() throws IOException {
    Path mapperDir = Paths.get("src/main/resources/mapper");
    if (!Files.exists(mapperDir)) {
      return;
    }
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(mapperDir)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".xml")).toList()) {
        violations.addAll(jobTaskWriteViolations(
            file.getFileName().toString(), Files.readString(file), allowedJobTaskColumns()));
      }
    }
    assertThat(violations)
        .as("Worker 写 batch.job_task 越过登记列白名单;任务状态只能经 Orchestrator report 收敛,确需新列时同步更新"
            + " allowedJobTaskColumns() 与 runtime-module-communication.md")
        .isEmpty();
  }

  /** 供守护自测复用:返回给定 Mapper XML 内容中的越界写入,格式为 {@code 文件#语句: 原因}。 */
  public static List<String> jobTaskWriteViolations(
      String fileName, String xml, Set<String> allowedColumns) {
    List<String> violations = new ArrayList<>();
    Matcher block = STATEMENT_BLOCK.matcher(XML_COMMENT.matcher(xml).replaceAll(" "));
    while (block.find()) {
      String statementId = statementId(block.group(2));
      String sql = XML_TAG.matcher(block.group(3)).replaceAll(" ");
      String location = fileName.replace(".xml", "") + "#" + statementId;
      Matcher insertOrDelete = JOB_TASK_INSERT_OR_DELETE.matcher(sql);
      while (insertOrDelete.find()) {
        violations.add(location + ": " + insertOrDelete.group(1).toLowerCase(Locale.ROOT));
      }
      Matcher update = JOB_TASK_UPDATE.matcher(sql);
      while (update.find()) {
        for (String column : assignedColumns(sql.substring(update.end()))) {
          boolean allowed = "updated_at".equals(column) || allowedColumns.contains(column);
          if (!allowed) {
            violations.add(location + ": set " + column);
          }
        }
      }
    }
    return violations;
  }

  private static String statementId(String attributes) {
    Matcher id = STATEMENT_ID.matcher(attributes);
    return id.find() ? id.group(1) : "?";
  }

  /** 解析 SET 子句的被赋值列;按括号深度切分,避免 coalesce(a, b) 等表达式里的逗号误切。 */
  private static List<String> assignedColumns(String afterSet) {
    String setClause = setClause(afterSet);
    List<String> columns = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int i = 0; i <= setClause.length(); i++) {
      char c = i < setClause.length() ? setClause.charAt(i) : ',';
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (c == ',' && depth == 0) {
        Matcher column = ASSIGNED_COLUMN.matcher(setClause.substring(start, i));
        if (column.find()) {
          columns.add(column.group(1).toLowerCase(Locale.ROOT));
        }
        start = i + 1;
      }
    }
    return columns;
  }

  /** SET 子句止于同层的 FROM/WHERE/RETURNING,或包住本语句的 CTE 右括号。 */
  private static String setClause(String afterSet) {
    int depth = 0;
    for (int i = 0; i < afterSet.length(); i++) {
      char c = afterSet.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        if (depth == 0) {
          return afterSet.substring(0, i);
        }
        depth--;
      } else if (depth == 0
          && Character.isLetter(c)
          && (i == 0 || !isWordChar(afterSet.charAt(i - 1)))) {
        Matcher end = SET_CLAUSE_END.matcher(afterSet).region(i, afterSet.length());
        if (end.lookingAt()) {
          return afterSet.substring(0, i);
        }
      }
    }
    return afterSet;
  }

  private static boolean isWordChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }
}
