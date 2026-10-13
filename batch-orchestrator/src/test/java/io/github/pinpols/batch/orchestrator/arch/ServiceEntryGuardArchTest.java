package io.github.pinpols.batch.orchestrator.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 治理护栏:核心 service 入口 (Launch/Trigger/Gate) 必须显式守护 null 入参 — 走 {@code Guard.require} / {@code
 * Guard.requireFound} / {@code Objects.requireNonNull} / {@code if (x == null) ...} 等任一形式。
 *
 * <p>背景:这些入口处于 DB→Outbox→Kafka→CLAIM→EXECUTE→REPORT 主链路最上游,空入参直透到下层 mapper 会变成 NPE 风暴。本测试静态扫描 main
 * src,缺守护 → fail。
 */
@DisplayName("核心服务入口空值守护与实现类异常抛出约束的静态扫描")
class ServiceEntryGuardArchTest {

  /** 必守护的 service 实现类相对路径(从模块 src/main 起算)。 */
  private static final List<Path> GUARDED_SERVICES = List.of(
      Paths.get(
          "src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultLaunchValidationService.java"),
      Paths.get(
          "src/main/java/io/github/pinpols/batch/orchestrator/service/BatchDayGateService.java"));

  @Test
  @DisplayName("核心服务入口必须对空入参显式守护,任一实现缺少守护即判定违规")
  void shouldGuardAgainstNullInputs_whenScanningCoreServiceEntries() throws IOException {
    List<String> violations = new ArrayList<>();
    Path launchValidation = GUARDED_SERVICES.get(0);
    Path batchDayGate = GUARDED_SERVICES.get(1);
    requireSource(launchValidation, violations);
    requireSource(batchDayGate, violations);

    if (Files.exists(launchValidation)) {
      String src = Files.readString(launchValidation);
      if (!src.contains("validate(request);") || !src.contains("Guard.require(request != null")) {
        violations.add(launchValidation.getFileName()
            + " — load 必须调用 validate(request)，且 validate 必须守护 request 空值");
      }
    }
    if (Files.exists(batchDayGate)) {
      String src = Files.readString(batchDayGate);
      if (!src.contains("request == null || loaded == null")) {
        violations.add(batchDayGate.getFileName()
            + " — evaluateAndApply 必须对 request/loaded 空值采取显式 fail-safe 策略");
      }
    }
    assertThat(violations)
        .as("core service entries must defend against null inputs")
        .isEmpty();
  }

  private static void requireSource(Path rel, List<String> violations) {
    if (!Files.exists(rel)) {
      violations.add(rel + " — file missing (refactor without updating guard test?)");
    }
  }

  @Test
  @DisplayName("服务实现类不得显式抛出空指针异常,扫描到即判定违规")
  void shouldNotContainBareNullPointerException_whenScanningAllServiceImpls() throws IOException {
    Path mainDir = Paths.get("src/main/java");
    if (!Files.exists(mainDir)) {
      return;
    }
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(mainDir)) {
      files
          .filter(p -> p.toString().endsWith("Service.java") || p.toString().endsWith("Impl.java"))
          .forEach(p -> {
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
