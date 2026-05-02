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
 * 守护测试:console-api 已统一走 MyBatis(MyBatis 迁移盘 Phase 0-3 完成)。
 *
 * <p>禁止 console-api {@code main} 路径下任何 Java 文件引入 Spring Data JDBC / Spring Data Relational
 * 注解或类型（{@code @Query}、{@code Repository}、{@code CrudRepository}、{@code @Column}、{@code
 * EnableJdbcRepositories} 等）。新增查询请走 MyBatis mapper(参见 {@code mapper/Console*Mapper.java} + {@code
 * resources/mapper/Console*Mapper.xml})。
 *
 * <p>豁免:仅 test 路径不扫(允许 IT 中用 Spring Data JDBC 做断言查询)。
 */
class NoSpringDataJdbcQueryGuardTest {

  private static final Path MAIN_JAVA =
      Path.of("..").toAbsolutePath().normalize().resolve("batch-console-api/src/main/java");

  private static final List<String> FORBIDDEN_TOKENS =
      List.of(
          "org.springframework.data.jdbc.repository.query.Query",
          "org.springframework.data.jdbc.repository.config.EnableJdbcRepositories",
          "org.springframework.data.repository.Repository",
          "org.springframework.data.repository.CrudRepository",
          "org.springframework.data.relational.core.mapping.Column");

  @Test
  void noSpringDataJdbcQueryInMain() throws IOException {
    if (!Files.isDirectory(MAIN_JAVA)) {
      return;
    }
    List<String> violations = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(MAIN_JAVA)) {
      stream.filter(p -> p.toString().endsWith(".java")).forEach(p -> scanFile(p, violations));
    }
    assertThat(violations)
        .as(
            "console-api 已统一走 MyBatis;新增查询请走 mapper,不要再引入 Spring Data JDBC @Query / Repository。"
                + " 详见 commit 历史 'refactor(console): * 迁 MyBatis (Phase 0-3)'")
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
