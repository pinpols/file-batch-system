package io.github.pinpols.batch.sdk.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import java.time.Month;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TaskDispatchMessage 派单消息 — 反序列化、必填校验与协议版本兼容")
class TaskDispatchMessageTest {

  private final ObjectMapper mapper = new ObjectMapper();

  // 对齐真实 KafkaTaskConsumer:LocalDate 在线上以 int-array([2026,6,1])下发,需 JavaTimeModule。
  private final ObjectMapper timeAwareMapper =
      new ObjectMapper().registerModule(new JavaTimeModule());

  @Test
  @DisplayName("完整 JSON 载荷可反序列化出各业务字段并顺利通过校验")
  void shouldDeserialize_whenJsonPayloadComplete() throws Exception {
    String json = "{\"taskId\":42,\"tenantId\":\"tx\",\"jobCode\":\"job-1\",\"taskType\":\"tt\","
        + "\"taskInstanceId\":\"ti-9\",\"parameters\":{\"k\":\"v\"},"
        + "\"runtimeAttributes\":{\"traceId\":\"abc\"}}";
    TaskDispatchMessage msg = mapper.readValue(json, TaskDispatchMessage.class);
    assertThat(msg.taskId()).isEqualTo(42L);
    assertThat(msg.tenantId()).isEqualTo("tx");
    assertThat(msg.parameters()).containsEntry("k", "v");
    msg.validate(); // 不抛
  }

  @Test
  @DisplayName("平台新增未知字段时反序列化不失败,老客户端平滑兼容")
  void shouldIgnoreUnknownFields_whenPlatformAddsNewOnes() throws Exception {
    // 平台升级新字段时 SDK 不 break
    String json = "{\"taskId\":1,\"tenantId\":\"tx\",\"jobCode\":\"j\",\"taskType\":\"t\","
        + "\"newPlatformField\":\"future\",\"anotherNewField\":42}";
    TaskDispatchMessage msg = mapper.readValue(json, TaskDispatchMessage.class);
    assertThat(msg.taskId()).isEqualTo(1L);
  }

  @Test
  @DisplayName("调度上下文按契约解析出业务日期、触发类型与尝试次数")
  void shouldDeserializeSchedulingContext_whenPresent() throws Exception {
    // 平台以 int-array 下发 LocalDate(WRITE_DATES_AS_TIMESTAMPS 默认开);triggerCode/workflowRunId 平台置 null
    String json = "{\"taskId\":42,\"tenantId\":\"tx\",\"jobCode\":\"job-1\",\"taskType\":\"tt\","
        + "\"schedulingContext\":{\"bizDate\":[2026,6,1],\"prevBizDate\":[2026,5,29],"
        + "\"nextBizDate\":[2026,6,2],\"isHoliday\":false,\"attemptNo\":2,"
        + "\"triggerType\":\"SCHEDULED\",\"triggerCode\":null,\"workflowRunId\":null}}";
    TaskDispatchMessage msg = timeAwareMapper.readValue(json, TaskDispatchMessage.class);

    assertThat(msg.schedulingContext()).isNotNull();
    assertThat(msg.schedulingContext().bizDate()).isEqualTo(LocalDate.of(2026, Month.JUNE, 1));
    assertThat(msg.schedulingContext().prevBizDate()).isEqualTo(LocalDate.of(2026, Month.MAY, 29));
    assertThat(msg.schedulingContext().nextBizDate()).isEqualTo(LocalDate.of(2026, Month.JUNE, 2));
    assertThat(msg.schedulingContext().isHoliday()).isFalse();
    assertThat(msg.schedulingContext().attemptNo()).isEqualTo(2);
    assertThat(msg.schedulingContext().triggerType()).isEqualTo("SCHEDULED");
    assertThat(msg.schedulingContext().triggerCode()).isNull();
    assertThat(msg.schedulingContext().workflowRunId()).isNull();
  }

  @Test
  @DisplayName("老平台不下发调度上下文时该字段为空且不报错")
  void shouldReturnNullSchedulingContext_whenAbsent() throws Exception {
    // 老平台不下发 schedulingContext → 字段为 null,SDK 不 break
    String json = "{\"taskId\":1,\"tenantId\":\"tx\",\"jobCode\":\"j\",\"taskType\":\"t\"}";
    TaskDispatchMessage msg = mapper.readValue(json, TaskDispatchMessage.class);
    assertThat(msg.schedulingContext()).isNull();
  }

  @Test
  @DisplayName("缺少协议版本字段时按 v1 处理并判定为受支持")
  void shouldResolveToV1_whenSchemaVersionMissing() throws Exception {
    String json = "{\"taskId\":1,\"tenantId\":\"tx\",\"jobCode\":\"j\",\"taskType\":\"t\"}";
    TaskDispatchMessage msg = mapper.readValue(json, TaskDispatchMessage.class);
    assertThat(msg.schemaVersion()).isNull(); // 缺字段反序列化为 null
    assertThat(msg.resolvedMajor()).isEqualTo("v1"); // 但 resolvedMajor fallback v1
    assertThat(msg.isSchemaSupported()).isTrue();
  }

  @Test
  @DisplayName("v1 与 v2 主版本及其预发布变体均判定为受支持")
  void shouldSupportSchema_whenMajorVersionIsV1OrV2() throws Exception {
    for (String v : new String[] {"v1", "v2", "v1-rc", "v2-beta", "v2.1"}) {
      String json = "{\"schemaVersion\":\""
          + v
          + "\",\"taskId\":1,\"tenantId\":\"tx\",\"jobCode\":\"j\",\"taskType\":\"t\"}";
      TaskDispatchMessage msg = mapper.readValue(json, TaskDispatchMessage.class);
      assertThat(msg.isSchemaSupported())
          .as("schemaVersion=%s should be supported", v)
          .isTrue();
    }
  }

  @Test
  @DisplayName("未知主版本判定为不受支持,避免按旧协议误处理")
  void shouldRejectSchema_whenMajorVersionUnknown() throws Exception {
    for (String v : new String[] {"v3", "v3-rc", "v99", "vNext", "1", "draft"}) {
      String json = "{\"schemaVersion\":\""
          + v
          + "\",\"taskId\":1,\"tenantId\":\"tx\",\"jobCode\":\"j\",\"taskType\":\"t\"}";
      TaskDispatchMessage msg = mapper.readValue(json, TaskDispatchMessage.class);
      assertThat(msg.isSchemaSupported())
          .as("schemaVersion=%s should be rejected", v)
          .isFalse();
    }
  }

  @Test
  @DisplayName("首字符非字母数字的畸形版本号一律拒绝,不回退默认版本")
  void shouldRejectSchema_whenVersionMalformed() {
    // P1:首字符非字母数字的畸形 schemaVersion(前导空格 / 标点 / BOM)不能回退 v1 accept。
    // 否则 " v3" 会被本 SDK 按 v1 假设吃掉,违反 fixture 18 sdkMustNot "process v3 under v1"。
    // 用 record 构造直接锁 resolvedMajor(避免 Jackson 对畸形值的 trim 干扰)。
    for (String v : new String[] {" v3", "\tv3", "!v3", "\uFEFFv3", " v1", " v2", "-v1", ".v2"}) {
      TaskDispatchMessage msg =
          new TaskDispatchMessage(v, 1L, "tx", "j", "t", "ti", Map.of(), Map.of());
      assertThat(msg.isSchemaSupported())
          .as("malformed schemaVersion=%s must be rejected (no silent v1 fallback)", v)
          .isFalse();
      assertThat(msg.resolvedMajor())
          .as("malformed schemaVersion=%s must not resolve to a supported major", v)
          .isNotIn("v1", "v2");
    }
  }

  @Test
  @DisplayName("仅真正缺省时才回退 v1,空白版本号按 v1 接受")
  void shouldFallbackToV1_onlyWhenVersionTrulyMissing() {
    // 对照:真正缺省(null / 全空白)才 fallback v1 accept(fixture 16 缺省=v1)。
    assertThat(new TaskDispatchMessage(null, 1L, "tx", "j", "t", "ti", Map.of(), Map.of())
            .resolvedMajor())
        .isEqualTo("v1");
    assertThat(new TaskDispatchMessage("   ", 1L, "tx", "j", "t", "ti", Map.of(), Map.of())
            .isSchemaSupported())
        .isTrue();
  }

  @Test
  @DisplayName("解析主版本时去掉后缀,只保留版本主干")
  void shouldStripSuffix_whenResolvingMajorVersion() {
    TaskDispatchMessage msg =
        new TaskDispatchMessage("v2-rc", 1L, "tx", "j", "t", "ti", Map.of(), Map.of());
    assertThat(msg.resolvedMajor()).isEqualTo("v2");
  }

  @Test
  @DisplayName("兼容构造函数补默认版本号并判定为受支持")
  void shouldDefaultSchemaVersion_whenUsingCompatConstructor() {
    TaskDispatchMessage msg = new TaskDispatchMessage(1L, "tx", "j", "t", "ti", Map.of(), Map.of());
    // 7 参兼容构造 → schemaVersion 填默认值,supported = true
    assertThat(msg.schemaVersion()).isEqualTo("v1");
    assertThat(msg.isSchemaSupported()).isTrue();
  }

  @Test
  @DisplayName("任务标识、租户、作业编码与任务类型缺失时校验报错")
  void shouldReject_whenRequiredFieldMissing() {
    assertThatThrownBy(() ->
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
