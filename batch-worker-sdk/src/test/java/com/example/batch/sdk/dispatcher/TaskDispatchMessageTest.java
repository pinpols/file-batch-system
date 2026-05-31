package com.example.batch.sdk.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskDispatchMessageTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void deserializesFromJson() throws Exception {
    String json =
        "{\"taskId\":42,\"tenantId\":\"tx\",\"jobCode\":\"job-1\",\"taskType\":\"tt\","
            + "\"taskInstanceId\":\"ti-9\",\"parameters\":{\"k\":\"v\"},"
            + "\"runtimeAttributes\":{\"traceId\":\"abc\"}}";
    TaskDispatchMessage msg = mapper.readValue(json, TaskDispatchMessage.class);
    assertThat(msg.taskId()).isEqualTo(42L);
    assertThat(msg.tenantId()).isEqualTo("tx");
    assertThat(msg.parameters()).containsEntry("k", "v");
    msg.validate(); // 不抛
  }

  @Test
  void ignoresUnknownFields() throws Exception {
    // 平台升级新字段时 SDK 不 break
    String json =
        "{\"taskId\":1,\"tenantId\":\"tx\",\"jobCode\":\"j\",\"taskType\":\"t\","
            + "\"newPlatformField\":\"future\",\"anotherNewField\":42}";
    TaskDispatchMessage msg = mapper.readValue(json, TaskDispatchMessage.class);
    assertThat(msg.taskId()).isEqualTo(1L);
  }

  @Test
  void validateRejectsMissingFields() {
    assertThatThrownBy(
            () ->
                new TaskDispatchMessage(null, "tx", "j", "t", "ti", Map.of(), Map.of()).validate())
        .hasMessageContaining("taskId");
    assertThatThrownBy(
            () -> new TaskDispatchMessage(1L, "", "j", "t", "ti", Map.of(), Map.of()).validate())
        .hasMessageContaining("tenantId");
    assertThatThrownBy(
            () -> new TaskDispatchMessage(1L, "tx", null, "t", "ti", Map.of(), Map.of()).validate())
        .hasMessageContaining("jobCode");
    assertThatThrownBy(
            () -> new TaskDispatchMessage(1L, "tx", "j", "  ", "ti", Map.of(), Map.of()).validate())
        .hasMessageContaining("taskType");
  }
}
