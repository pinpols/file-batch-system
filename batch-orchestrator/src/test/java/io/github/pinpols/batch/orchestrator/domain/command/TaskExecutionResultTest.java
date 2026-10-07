package io.github.pinpols.batch.orchestrator.domain.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.utils.JsonUtils;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("任务结果建模：分离执行分支并保留历史输入及软告警语义")
class TaskExecutionResultTest {

  @Test
  @DisplayName("内部结果 accessor 不进入 JSON，固定字段与开放扩展仍按原协议序列化")
  void shouldRetainWireShape_whenInternalResultIsAdded() {
    Map<String, Object> verifier = Map.of("code", "COUNT", "extension", 7);
    TaskOutcomeCommand command = TaskOutcomeCommand.builder()
        .tenantId("t1")
        .taskId(17L)
        .workerId("wk1")
        .success(true)
        .resultSummary("summary")
        .errorCode("legacy")
        .errorMessage("message")
        .errorKey("error.key")
        .errorArgs("[]")
        .highWaterMarkOut("42")
        .outputs(Map.of("open", true))
        .partitionInvocationId("inv1")
        .failureClass("TECHNICAL")
        .verifierFailures(List.of(verifier))
        .build();
    Map<String, Object> legacyWire = Map.ofEntries(
        Map.entry("tenantId", "t1"),
        Map.entry("taskId", 17L),
        Map.entry("workerId", "wk1"),
        Map.entry("success", true),
        Map.entry("resultSummary", "summary"),
        Map.entry("errorCode", "legacy"),
        Map.entry("errorMessage", "message"),
        Map.entry("errorKey", "error.key"),
        Map.entry("errorArgs", "[]"),
        Map.entry("highWaterMarkOut", "42"),
        Map.entry("outputs", Map.of("open", true)),
        Map.entry("partitionInvocationId", "inv1"),
        Map.entry("failureClass", "TECHNICAL"),
        Map.entry("verifierFailures", List.of(verifier)));
    ObjectMapper mapper = JsonUtils.newDefaultMapper();

    JsonNode actual = mapper.valueToTree(command);
    JsonNode expected = mapper.valueToTree(legacyWire);
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  @DisplayName("成功回报携带校验失败时仍建模为成功，保留空元素、开放证据及旧字段")
  void shouldRetainSoftFailuresAndLegacyFields_whenExecutionSucceeds() {
    Map<String, Object> evidence = Map.of("rows", 7, "custom", List.of("a", "b"));
    Map<String, Object> wireFailure = Map.of(
        "code", 123, "message", false, "evidence", evidence, "extension", "retained-in-summary");
    TaskOutcomeCommand command = TaskOutcomeCommand.builder()
        .success(true)
        .errorCode("LEGACY_UNUSED")
        .failureClass("LEGACY_CLASS")
        .highWaterMarkOut("2026-10-08")
        .outputs(Map.of("custom", 42))
        .verifierFailures(Arrays.asList(null, wireFailure))
        .build();

    TaskExecutionResult.Success result = (TaskExecutionResult.Success) command.executionResult();

    assertThat(result.highWaterMarkOut()).isEqualTo("2026-10-08");
    assertThat(result.outputs()).containsEntry("custom", 42);
    assertThat(result.verifierFailures())
        .containsExactly(null, new VerifierFailure("123", "false", evidence));
    assertThat(command.errorCode()).isEqualTo("LEGACY_UNUSED");
    assertThat(command.failureClass()).isEqualTo("LEGACY_CLASS");
    assertThat(command.verifierFailures().get(1)).containsEntry("extension", "retained-in-summary");
  }

  @Test
  @DisplayName("失败回报只暴露失败字段，但不删除旧 command 的成功专属字段")
  void shouldSelectFailureFieldsWithoutRewritingInput_whenExecutionFails() {
    TaskOutcomeCommand command = TaskOutcomeCommand.builder()
        .success(false)
        .errorCode("DB_FAILED")
        .errorMessage("retry later")
        .errorKey("error.db")
        .errorArgs("[]")
        .failureClass(null)
        .highWaterMarkOut("ignored")
        .outputs(Map.of("legacy", true))
        .build();

    assertThat(command.executionResult())
        .isEqualTo(
            new TaskExecutionResult.Failure("DB_FAILED", "retry later", "error.db", "[]", null));
    assertThat(command.highWaterMarkOut()).isEqualTo("ignored");
    assertThat(command.outputs()).containsEntry("legacy", true);
  }

  @Test
  @DisplayName("缺失、空列表与缺失字段保持原语义，不引入更严格的校验")
  void shouldPreserveNullAndEmptySemantics_whenVerifierFieldsAreAbsent() {
    assertThat(VerifierFailure.fromWire(null)).isEmpty();
    assertThat(VerifierFailure.fromWire(List.of())).isEmpty();
    assertThat(VerifierFailure.fromWire(List.of(Map.of())))
        .containsExactly(new VerifierFailure(null, null, null));
    TaskOutcomeCommand command = TaskOutcomeCommand.builder().success(true).build();
    assertThat(command.verifierFailures()).isNull();
    assertThat(((TaskExecutionResult.Success) command.executionResult()).verifierFailures())
        .isEmpty();
  }
}
