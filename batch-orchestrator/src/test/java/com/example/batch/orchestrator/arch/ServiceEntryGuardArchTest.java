package com.example.batch.orchestrator.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 治理护栏:核心 service 入口 (Launch/Trigger/Gate) 必须显式守护 null 入参 — 走 {@code Guard.require} / {@code
 * Guard.requireFound} / {@code Objects.requireNonNull} / {@code if (x == null) ...} 等任一形式。
 *
 * <p>背景:这些入口处于 DB→Outbox→Kafka→CLAIM→EXECUTE→REPORT 主链路最上游,空入参直透到下层 mapper 会变成 NPE 风暴。本测试静态扫描 main
 * src,缺守护 → fail。
 */
class ServiceEntryGuardArchTest {

  /** 必守护的 service 实现类相对路径(从模块 src/main 起算)。 */
  private static final List<Path> GUARDED_SERVICES =
      List.of(
          Paths.get(
              "src/main/java/com/example/batch/orchestrator/service/DefaultLaunchValidationService.java"),
          Paths.get(
              "src/main/java/com/example/batch/orchestrator/service/BatchDayGateService.java"));

  @Test
  void coreServiceEntriesMustGuardAgainstNullInputs() throws IOException {
    List<String> violations = new ArrayList<>();
    for (Path rel : GUARDED_SERVICES) {
      if (!Files.exists(rel)) {
        violations.add(rel + " — file missing (refactor without updating guard test?)");
        continue;
      }
      String src = Files.readString(rel);
      boolean hasGuard =
          src.contains("Guard.require")
              || src.contains("Guard.requireFound")
              || src.contains("Objects.requireNonNull")
              || src.contains("== null")
              || src.contains("!= null");
      if (!hasGuard) {
        violations.add(rel.getFileName() + " — 缺 null 守护(Guard.* / requireNonNull / == null 检查任一)");
      }
    }
    assertThat(violations).as("core service entries must defend against null inputs").isEmpty();
  }

  @Test
  void allServiceImplsShouldNotContainBareThrowNew() throws IOException {
    Path mainDir = Paths.get("src/main/java");
    if (!Files.exists(mainDir)) {
      return;
    }
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(mainDir)) {
      files
          .filter(p -> p.toString().endsWith("Service.java") || p.toString().endsWith("Impl.java"))
          .forEach(
              p -> {
                String src;
                try {
                  src = Files.readString(p);
                } catch (IOException ex) {
                  violations.add(p + " — read failed");
                  return;
                }
                if (src.contains("throw new NullPointerException")) {
                  violations.add(p.getFileName() + " — 显式 throw new NullPointerException");
                }
              });
    }
    assertThat(violations)
        .as("service impl 不应显式抛 NPE — 应改用 BizException 或 Guard.require")
        .isEmpty();
  }
}
