package io.github.pinpols.batch.sdk.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskDispatchMessageTest {

  private final ObjectMapper mapper = new ObjectMapper();

  // 对齐真实 KafkaTaskConsumer:LocalDate 在线上以 int-array([2026,6,1])下发,需 JavaTimeModule。
  private final ObjectMapper timeAwareMapper =
      new ObjectMapper().registerModule(new JavaTimeModule());

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
  void deserializesSchedulingContext() throws Exception {
    // 平台以 int-array 下发 LocalDate(WRITE_DATES_AS_TIMESTAMPS 默认开);triggerCode/workflowRunId 平台置 null
    String json =
        "{\"taskId\":42,\"tenantId\":\"tx\",\"jobCode\":\"job-1\",\"taskType\":\"tt\","
            + "\"schedulingContext\":{\"bizDate\":[2026,6,1],\"prevBizDate\":[2026,5,29],"
            + "\"nextBizDate\":[2026,6,2],\"isHoliday\":false,\"attemptNo\":2,"
            + "\"triggerType\":\"SCHEDULED\",\"triggerCode\":null,\"workflowRunId\":null}}";
    TaskDispatchMessage msg = timeAwareMapper.readValue(json, TaskDispatchMessage.class);

    assertThat(msg.schedulingContext()).isNotNull();
    assertThat(msg.schedulingContext().bizDate()).isEqualTo(LocalDate.of(2026, 6, 1));
    assertThat(msg.schedulingContext().prevBizDate()).isEqualTo(LocalDate.of(2026, 5, 29));
    assertThat(msg.schedulingContext().nextBizDate()).isEqualTo(LocalDate.of(2026, 6, 2));
    assertThat(msg.schedulingContext().isHoliday()).isFalse();
    assertThat(msg.schedulingContext().attemptNo()).isEqualTo(2);
    assertThat(msg.schedulingContext().triggerType()).isEqualTo("SCHEDULED");
    assertThat(msg.schedulingContext().triggerCode()).isNull();
    assertThat(msg.schedulingContext().workflowRunId()).isNull();
  }

  @Test
  void schedulingContextNullWhenAbsent() throws Exception {
    // 老平台不下发 schedulingContext → 字段为 null,SDK 不 break
    String json = "{\"taskId\":1,\"tenantId\":\"tx\",\"jobCode\":\"j\",\"taskType\":\"t\"}";
    TaskDispatchMessage msg = mapper.readValue(json, TaskDispatchMessage.class);
    assertThat(msg.schedulingContext()).isNull();
  }

  @Test
  void schemaVersionDefaultsToV1WhenMissing() throws Exception {
    String json = "{\"taskId\":1,\"tenantId\":\"tx\",\"jobCode\":\"j\",\"taskType\":\"t\"}";
    TaskDispatchMessage msg = mapper.readValue(json, TaskDispatchMessage.class);
    assertThat(msg.schemaVersion()).isNull(); // 缺字段反序列化为 null
    assertThat(msg.resolvedMajor()).isEqualTo("v1"); // 但 resolvedMajor fallback v1
    assertThat(msg.isSchemaSupported()).isTrue();
  }

  @Test
  void schemaVersionSupportedWhenV1OrV2() throws Exception {
    for (String v : new String[] {"v1", "v2", "v1-rc", "v2-beta", "v2.1"}) {
      String json =
          "{\"schemaVersion\":\""
              + v
              + "\",\"taskId\":1,\"tenantId\":\"tx\",\"jobCode\":\"j\",\"taskType\":\"t\"}";
      TaskDispatchMessage msg = mapper.readValue(json, TaskDispatchMessage.class);
      assertThat(msg.isSchemaSupported()).as("schemaVersion=%s should be supported", v).isTrue();
    }
  }

  @Test
  void schemaVersionRejectedWhenUnknownMajor() throws Exception {
    for (String v : new String[] {"v3", "v3-rc", "v99", "vNext", "1", "draft"}) {
      String json =
          "{\"schemaVersion\":\""
              + v
              + "\",\"taskId\":1,\"tenantId\":\"tx\",\"jobCode\":\"j\",\"taskType\":\"t\"}";
      TaskDispatchMessage msg = mapper.readValue(json, TaskDispatchMessage.class);
      assertThat(msg.isSchemaSupported()).as("schemaVersion=%s should be rejected", v).isFalse();
    }
  }

  @Test
  void resolvedMajorStripsSuffix() {
    TaskDispatchMessage msg =
        new TaskDispatchMessage("v2-rc", 1L, "tx", "j", "t", "ti", Map.of(), Map.of());
    assertThat(msg.resolvedMajor()).isEqualTo("v2");
  }

  @Test
  void compatConstructorDefaultsSchemaVersion() {
    TaskDispatchMessage msg = new TaskDispatchMessage(1L, "tx", "j", "t", "ti", Map.of(), Map.of());
    // 7 参兼容构造 → schemaVersion 填默认值,supported = true
    assertThat(msg.schemaVersion()).isEqualTo("v1");
    assertThat(msg.isSchemaSupported()).isTrue();
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
