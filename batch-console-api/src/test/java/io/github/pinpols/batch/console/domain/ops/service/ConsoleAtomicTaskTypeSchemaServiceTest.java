package io.github.pinpols.batch.console.domain.ops.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.domain.ops.dto.AtomicTaskTypeSchema;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 2-B 内置原子四类 schema 静态目录单测 —— 校验四类齐全、必填字段、安全闸不空。 */
@DisplayName("内置原子任务类型目录:四类齐全, 必填参数与安全闸不空且与执行器契约一致")
class ConsoleAtomicTaskTypeSchemaServiceTest {

  private final ConsoleAtomicTaskTypeSchemaService service =
      new ConsoleAtomicTaskTypeSchemaService();

  @Test
  @DisplayName("目录枚举:内置类型恰好四类, 顺序与集合完全一致")
  void shouldExposeExactlyFourBuiltinTypes_whenCatalogLoaded() {
    assertThat(service.schema())
        .extracting(AtomicTaskTypeSchema::taskType)
        .containsExactly("sql", "stored_proc", "shell", "http");
  }

  @Test
  @DisplayName("默认开关:shell 默认关闭, 其余三类默认启用")
  void shouldKeepShellDisabled_whenOtherTypesEnabledByDefault() {
    assertThat(byType("shell").enabledByDefault()).isFalse();
    assertThat(byType("sql").enabledByDefault()).isTrue();
    assertThat(byType("stored_proc").enabledByDefault()).isTrue();
    assertThat(byType("http").enabledByDefault()).isTrue();
  }

  @Test
  @DisplayName("目录完整性:每一类都至少一个必填参数, 且安全闸非空")
  void shouldHaveRequiredParamAndSecurityGates_whenIteratingAllTypes() {
    for (AtomicTaskTypeSchema s : service.schema()) {
      assertThat(s.parameters())
          .as("type %s 必有必填参数", s.taskType())
          .anyMatch(AtomicTaskTypeSchema.ParamSpec::required);
      assertThat(s.securityGates()).as("type %s 必有安全闸", s.taskType()).isNotEmpty();
    }
  }

  @Test
  @DisplayName("必填对齐:四类的必填参数名与执行器契约逐一相等")
  void shouldMatchExecutorContract_whenReadingRequiredParams() {
    assertThat(requiredParam("sql")).isEqualTo("sql");
    assertThat(requiredParam("stored_proc")).isEqualTo("procedureName");
    assertThat(requiredParam("shell")).isEqualTo("command");
    assertThat(requiredParam("http")).isEqualTo("url");
  }

  private AtomicTaskTypeSchema byType(String taskType) {
    return service.schema().stream()
        .filter(s -> s.taskType().equals(taskType))
        .findFirst()
        .orElseThrow();
  }

  private String requiredParam(String taskType) {
    List<AtomicTaskTypeSchema.ParamSpec> required = byType(taskType).parameters().stream()
        .filter(AtomicTaskTypeSchema.ParamSpec::required)
        .toList();
    assertThat(required).hasSize(1);
    return required.get(0).name();
  }
}
