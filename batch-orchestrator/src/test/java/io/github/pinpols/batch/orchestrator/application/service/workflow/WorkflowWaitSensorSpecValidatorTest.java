package io.github.pinpols.batch.orchestrator.application.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WAIT 传感器配置校验器:格式、传感类型与超时策略边界")
class WorkflowWaitSensorSpecValidatorTest {

  private final WorkflowWaitSensorSpecValidator validator = new WorkflowWaitSensorSpecValidator();

  @Test
  @DisplayName("非 WAIT 节点不执行传感器专属校验")
  void shouldIgnoreNonWaitNode() {
    assertThat(validator.validate(node("TASK", "not-json"))).isEmpty();
  }

  @Test
  @DisplayName("无效 JSON 返回 V16,空参数或缺失类型返回 V16-a")
  void shouldReportInvalidOrMissingSensorType() {
    assertThat(codes(node("WAIT", "{"))).contains("V16");
    assertThat(codes(node("WAIT", " "))).contains("V16-a");
    assertThat(codes(node("WAIT", "{}"))).contains("V16-a");
  }

  @Test
  @DisplayName("未知传感类型立即返回 V16-b")
  void shouldRejectUnknownSensorType() {
    assertThat(codes(node("WAIT", "{\"sensor_type\":\"UNKNOWN\"}"))).containsExactly("V16-b");
  }

  @Test
  @DisplayName("各传感类型按自己的必填字段校验")
  void shouldValidateTypeSpecificFields() {
    assertThat(codes(wait("FILE_ARRIVAL", "{}"))).contains("V16-c");
    assertThat(codes(wait("HTTP_POLL", "{\"url\":\"https://example.test\"}"))).contains("V16-c");
    assertThat(codes(wait("KAFKA_OFFSET", "{\"topic\":\"events\"}"))).contains("V16-c");
    assertThat(codes(wait("DB_ROW_EXISTS", "{\"schema\":\"biz\"}"))).contains("V16-c");

    assertThat(codes(wait("FILE_ARRIVAL", "{\"pattern\":\"daily-*\",\"maxAgeSeconds\":60}")))
        .isEmpty();
    assertThat(codes(wait("HTTP_POLL", "{\"url\":\"https://example.test\",\"matchExpr\":\"ok\"}")))
        .isEmpty();
    assertThat(
            codes(wait("KAFKA_OFFSET", "{\"topic\":\"events\",\"partition\":0,\"minOffset\":1}")))
        .isEmpty();
    assertThat(codes(wait("DB_ROW_EXISTS", "{\"schema\":\"biz\",\"sql\":\"select 1\"}")))
        .isEmpty();
  }

  @Test
  @DisplayName("传感器对象、超时窗口与超时动作缺失或无效时返回对应错误")
  void shouldReportMalformedSensorSpecAndTimeoutSettings() {
    assertThat(codes(node("WAIT", """
        {"sensor_type":"FILE_ARRIVAL","sensor_spec":[],"timeout_seconds":0,
         "poll_interval_seconds":"bad","on_timeout":""}
        """))).contains("V16-c", "V16-d", "V16-e");
    assertThat(codes(node("WAIT", """
        {"sensor_type":"FILE_ARRIVAL","sensor_spec":{"pattern":"x","maxAgeSeconds":1},
         "timeout_seconds":5,"poll_interval_seconds":5,"on_timeout":"INVALID"}
        """))).contains("V16-d", "V16-e");
    assertThat(codes(node("WAIT", """
        {"sensor_type":"FILE_ARRIVAL","sensor_spec":{"pattern":"x","maxAgeSeconds":1},
         "timeout_seconds":"10","poll_interval_seconds":"2","on_timeout":"FAIL"}
        """))).isEmpty();
  }

  private List<String> codes(WorkflowNodeEntity node) {
    return validator.validate(node).stream()
        .map(WorkflowValidationResult.ValidationIssue::code)
        .toList();
  }

  private static WorkflowNodeEntity wait(String sensorType, String spec) {
    return node("WAIT", """
        {"sensor_type":"%s","sensor_spec":%s,"timeout_seconds":30,
         "poll_interval_seconds":5,"on_timeout":"SKIP_DOWNSTREAM"}
        """.formatted(sensorType, spec));
  }

  private static WorkflowNodeEntity node(String type, String params) {
    WorkflowNodeEntity node = new WorkflowNodeEntity();
    node.setNodeType(type);
    node.setNodeCode("WAIT_TEST");
    node.setNodeParams(params);
    return node;
  }
}
