package com.example.batch.console.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 守护测试:全 reactor 已统一走 MyBatis(ADR-001 决策落地,SDJ 迁移盘 Phase 0-4 完成)。
 *
 * <p>禁止任何业务模块 {@code main} 路径下的 Java 文件引入 Spring Data JDBC / Spring Data Relational
 * 注解或类型({@code @Query}、{@code Repository}、{@code CrudRepository}、{@code @Column}、{@code
 * EnableJdbcRepositories} 等)。新增查询请走 MyBatis mapper。
 *
 * <p>扫描范围:9 个业务模块(batch-common / batch-trigger / batch-orchestrator / batch-worker-* /
 * batch-console-api)的 {@code src/main/java}。
 *
 * <p>豁免:test 路径不扫(允许 IT 中用 Spring Data JDBC 做断言查询)。
 *
 * <p>放在 console-api 模块是因为它最早完成 SDJ→MyBatis 迁移(Phase 0-4)、最有完整 mapper 模板可参考;实际守护范围覆盖全 reactor。 pom 层
 * {@code spring-boot-starter-data-jdbc} 依赖由 {@code scripts/ci/check-dependency-boundaries.py}
 * 把关,与本测试互补。
 */
class NoSpringDataJdbcQueryGuardTest {

  private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

  private static final List<String> SCAN_MODULES =
      List.of(
          "batch-common",
          "batch-trigger",
          "batch-orchestrator",
          "batch-worker-core",
          "batch-worker-import",
          "batch-worker-export",
          "batch-worker-process",
          "batch-worker-dispatch",
          "batch-console-api");

  private static final List<String> FORBIDDEN_TOKENS =
      List.of(
          "org.springframework.data.jdbc.repository.query.Query",
          "org.springframework.data.jdbc.repository.config.EnableJdbcRepositories",
          "org.springframework.data.repository.Repository",
          "org.springframework.data.repository.CrudRepository",
          "org.springframework.data.relational.core.mapping.Column");

  @Test
  void noSpringDataJdbcQueryInMain() throws IOException {
    List<String> violations = new ArrayList<>();
    for (String module : SCAN_MODULES) {
      Path mainJava = REPO_ROOT.resolve(module).resolve("src/main/java");
      if (!Files.isDirectory(mainJava)) {
        continue;
      }
      try (Stream<Path> stream = Files.walk(mainJava)) {
        stream.filter(p -> p.toString().endsWith(".java")).forEach(p -> scanFile(p, violations));
      }
    }
    assertThat(violations)
        .as(
            "全 reactor 已统一走 MyBatis(ADR-001);新增查询请走 mapper,不要再引入 Spring Data JDBC @Query /"
                + " Repository / @Column / EnableJdbcRepositories。详见 commit 7870f06e + Phase 0-4"
                + " 迁移历史。")
        .isEmpty();
  }

  private static void scanFile(Path file, List<String> sink) {
    String content;
    try {
      content = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException ignored) {
      return;
    }
    for (String token : FORBIDDEN_TOKENS) {
      if (content.contains(token)) {
        sink.add(file + " -> " + token);
      }
    }
  }
}
