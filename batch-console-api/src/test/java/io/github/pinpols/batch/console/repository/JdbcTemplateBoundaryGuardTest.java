package io.github.pinpols.batch.console.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ADR-001 持久化边界守护：平台业务 CRUD / 状态推进 / 幂等写默认走 MyBatis mapper；Spring
 * {@code JdbcTemplate} 只允许用于动态 SQL 执行、锁提供者、worker 本地 outbox 初始化等明确例外。
 *
 * <p>新增生产 {@code JdbcTemplate} 使用点时必须先判断边界：如果是平台表读写，请改成 MyBatis mapper；如果是动态业务库
 * SQL、框架适配或本地存储初始化，先补充本测试的允许清单，并在代码侧保留参数绑定、SQL 校验和租户约束。
 */
class JdbcTemplateBoundaryGuardTest {

  private static final Set<String> ALLOWED_MAIN_JAVA_FILES = Set.of(
      "batch-common/src/main/java/io/github/pinpols/batch/common/config/ShedLockProviderFactory.java",
      "batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/dataquality/DataQualityCheckExecutor.java",
      "batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/dryrun/DefaultDryRunPlanService.java",
      "batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/dryrun/DryRunSqlProbe.java",
      "batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/sensor/DbRowExistsSensorPolicy.java",
      "batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/reportoutbox/WorkerReportOutboxConfiguration.java",
      "batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/reportoutbox/WorkerReportOutboxRepository.java",
      "batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/plugin/GenericJdbcMappedExportDataPlugin.java",
      "batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/plugin/SqlTemplateExportDataPlugin.java",
      "batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/JdbcMappedImportCompensator.java",
      "batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/plugin/GenericJdbcMappedImportLoadPlugin.java",
      "batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputePlugin.java");

  private static final List<String> SPRING_JDBC_IMPORTS = List.of(
      "import org.springframework.jdbc.core.JdbcTemplate;",
      "import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;");

  @Test
  void springJdbcTemplateUsageStaysOnExplicitAllowList() throws IOException {
    Path repoRoot = locateRepositoryRoot();
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(repoRoot)) {
      files
          .filter(Files::isRegularFile)
          .filter(path -> path.toString().endsWith(".java"))
          .filter(JdbcTemplateBoundaryGuardTest::isMainJava)
          .forEach(path -> scan(path, repoRoot, violations));
    }

    assertThat(violations).as("""
            Spring JdbcTemplate / NamedParameterJdbcTemplate 只能出现在明确允许的生产边界。
            平台业务 CRUD、状态推进、幂等写、控制面查询请走 MyBatis mapper + XML。
            动态 SQL / 外部业务库 / ShedLock / 本地 outbox 初始化等例外，先补 ALLOWED_MAIN_JAVA_FILES 并说明边界。
            越界文件:
            %s
            """, String.join("\n", new TreeSet<>(violations))).isEmpty();
  }

  private static void scan(Path file, Path repoRoot, List<String> violations) {
    String content = readFile(file);
    boolean usesSpringJdbc = SPRING_JDBC_IMPORTS.stream().anyMatch(content::contains);
    if (!usesSpringJdbc) {
      return;
    }
    String relative = repoRoot.relativize(file).toString().replace('\\', '/');
    if (!ALLOWED_MAIN_JAVA_FILES.contains(relative)) {
      violations.add(relative);
    }
  }

  private static boolean isMainJava(Path path) {
    String normalized = path.toString().replace('\\', '/');
    return normalized.contains("/src/main/java/") && !normalized.contains("/target/");
  }

  private static Path locateRepositoryRoot() {
    Path candidate = Path.of(System.getProperty("maven.multiModuleProjectDirectory", "."))
        .toAbsolutePath()
        .normalize();
    while (candidate != null) {
      if (Files.isRegularFile(candidate.resolve("pom.xml"))
          && Files.isDirectory(candidate.resolve("batch-common"))
          && Files.isDirectory(candidate.resolve("batch-worker"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("cannot locate repository root");
  }

  private static String readFile(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new IllegalStateException("read failed: " + file, ex);
    }
  }
}
