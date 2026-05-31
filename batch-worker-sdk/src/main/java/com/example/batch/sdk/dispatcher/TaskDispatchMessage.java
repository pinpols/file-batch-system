package com.example.batch.sdk.dispatcher;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Kafka 派单消息的 SDK 侧投影 — 字段子集来自主项目 {@code TaskDispatchMessage}(orchestrator 发出)。
 *
 * <p>设计原则:**只保留 SDK 框架真正需要的字段**,其余 jackson 用 {@link JsonIgnoreProperties}{@code
 * (ignoreUnknown=true)} 包容,让 SDK 不会因平台新加字段而 break。
 *
 * <p>{@code schemaVersion} 协议(SDK 路线图 Phase 0):
 *
 * <ul>
 *   <li>缺失 → 当 {@code "v1"} 处理(向后兼容老 orchestrator 不带版本字段的消息)
 *   <li>{@code "v1"} / {@code "v1-..."} → SDK 当前 major,正常处理
 *   <li>其他 major(如 {@code "v2"} / {@code "v3-rc"}) → {@link #validate()} 抛 {@link
 *       UnsupportedSchemaVersionException},dispatcher skip + WARN(避免老 SDK 误解新格式)
 * </ul>
 *
 * @param schemaVersion 协议版本字符串(SDK Phase 0 起);null 兼容旧 orchestrator
 * @param taskId orchestrator 主键
 * @param tenantId 租户 ID
 * @param jobCode 作业编码
 * @param taskType 任务类型(SDK 用它路由到对应 {@link com.example.batch.sdk.task.SdkTaskHandler})
 * @param taskInstanceId 本次执行的 instance ID
 * @param parameters 业务参数(jackson 反序列化)
 * @param runtimeAttributes 框架属性(traceId / bizDate / pipelineInstanceId 等)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskDispatchMessage(
    @JsonProperty("taskId") Long taskId,
    @JsonProperty("tenantId") String tenantId,
    @JsonProperty("jobCode") String jobCode,
    @JsonProperty("taskType") String taskType,
    @JsonProperty("taskInstanceId") String taskInstanceId,
    @JsonProperty("parameters") Map<String, Object> parameters,
    @JsonProperty("runtimeAttributes") Map<String, Object> runtimeAttributes,
    @JsonProperty("schemaVersion") String schemaVersion) {

  /** 兼容老测试 / 老 orchestrator 不带 schemaVersion 的构造 — 内部委托至带 schemaVersion 的 canonical constructor。 */
  public TaskDispatchMessage(
      Long taskId,
      String tenantId,
      String jobCode,
      String taskType,
      String taskInstanceId,
      Map<String, Object> parameters,
      Map<String, Object> runtimeAttributes) {
    this(taskId, tenantId, jobCode, taskType, taskInstanceId, parameters, runtimeAttributes, null);
  }

  /** SDK 当前支持的 schema major(去掉 -rc/-beta 后)。 */
  public static final String SUPPORTED_MAJOR = "v1";

  /** 校验消息必备字段;不合规抛 {@link IllegalArgumentException},dispatcher 跳过该消息 + 上报 WARN。 */
  public void validate() {
    if (taskId == null) throw new IllegalArgumentException("taskId required");
    if (tenantId == null || tenantId.isBlank())
      throw new IllegalArgumentException("tenantId required");
    if (jobCode == null || jobCode.isBlank())
      throw new IllegalArgumentException("jobCode required");
    if (taskType == null || taskType.isBlank())
      throw new IllegalArgumentException("taskType required");
    String major = majorOf(schemaVersion);
    if (!SUPPORTED_MAJOR.equals(major)) {
      throw new UnsupportedSchemaVersionException(schemaVersion, SUPPORTED_MAJOR);
    }
  }

  /** 解析 major:{@code "v2-rc"} → {@code "v2"};null/blank → {@link #SUPPORTED_MAJOR}(向后兼容)。 */
  static String majorOf(String version) {
    if (version == null || version.isBlank()) return SUPPORTED_MAJOR;
    int dash = version.indexOf('-');
    return dash > 0 ? version.substring(0, dash) : version;
  }

  /** 不识别的 schema major → dispatcher 跳过 + WARN,不传染整个 consumer。 */
  public static final class UnsupportedSchemaVersionException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public UnsupportedSchemaVersionException(String actual, String supportedMajor) {
      super(
          "unsupported schemaVersion="
              + actual
              + " (SDK supports major="
              + supportedMajor
              + "); upgrade SDK to consume newer message");
    }
  }
}
