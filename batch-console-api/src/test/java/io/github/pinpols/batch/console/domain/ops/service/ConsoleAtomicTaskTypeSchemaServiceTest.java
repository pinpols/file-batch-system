package io.github.pinpols.batch.console.domain.ops.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.domain.ops.dto.AtomicTaskTypeSchema;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 2-B 内置原子四类 schema 静态目录单测 —— 校验四类齐全、必填字段、安全闸不空。 */
class ConsoleAtomicTaskTypeSchemaServiceTest {

  private final ConsoleAtomicTaskTypeSchemaService service =
      new ConsoleAtomicTaskTypeSchemaService();

  @Test
  void exposesExactlyFourBuiltinTypes() {
    assertThat(service.schema())
        .extracting(AtomicTaskTypeSchema::taskType)
        .containsExactly("sql", "stored_proc", "shell", "http");
  }

  @Test
  void shellDisabledByDefaultOthersEnabled() {
    assertThat(byType("shell").enabledByDefault()).isFalse();
    assertThat(byType("sql").enabledByDefault()).isTrue();
    assertThat(byType("stored_proc").enabledByDefault()).isTrue();
    assertThat(byType("http").enabledByDefault()).isTrue();
  }

  @Test
  void eachTypeHasRequiredParamAndSecurityGates() {
    for (AtomicTaskTypeSchema s : service.schema()) {
      assertThat(s.parameters())
          .as("type %s 必有必填参数", s.taskType())
          .anyMatch(AtomicTaskTypeSchema.ParamSpec::required);
      assertThat(s.securityGates()).as("type %s 必有安全闸", s.taskType()).isNotEmpty();
    }
  }

  @Test
  void requiredParamsMatchExecutorContract() {
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
    List<AtomicTaskTypeSchema.ParamSpec> required =
        byType(taskType).parameters().stream()
            .filter(AtomicTaskTypeSchema.ParamSpec::required)
            .toList();
    assertThat(required).hasSize(1);
    return required.get(0).name();
  }
}
