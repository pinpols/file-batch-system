package com.example.batch.sdk.dispatcher;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.Set;

/**
 * Kafka 派单消息的 SDK 侧投影 — 字段子集来自主项目 {@code TaskDispatchMessage}(orchestrator 发出)。
 *
 * <p>设计原则:**只保留 SDK 框架真正需要的字段**,其余 jackson 用 {@link JsonIgnoreProperties}{@code
 * (ignoreUnknown=true)} 包容,让 SDK 不会因平台新加字段而 break。
 *
 * <p>Phase 0 ({@code docs/plans/sdk-roadmap-2026-h2.md} §2)起携带 {@code schemaVersion} — 平台派单时填
 * {@code "v1"} / {@code "v2"} 等;SDK 解析时通过 {@link #SUPPORTED_MAJOR_VERSIONS} 检查主版本,未知 major 直接
 * reject(防错乱)。缺字段时 fallback {@code "v1"}(兼容老平台)。
 *
 * @param schemaVersion 协议主版本号(如 {@code "v1"} / {@code "v2"} / {@code "v2-rc"});缺字段当 {@code "v1"} 解析
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
    @JsonProperty("schemaVersion") String schemaVersion,
    @JsonProperty("taskId") Long taskId,
    @JsonProperty("tenantId") String tenantId,
    @JsonProperty("jobCode") String jobCode,
    @JsonProperty("taskType") String taskType,
    @JsonProperty("taskInstanceId") String taskInstanceId,
    @JsonProperty("parameters") Map<String, Object> parameters,
    @JsonProperty("runtimeAttributes") Map<String, Object> runtimeAttributes) {

  /**
   * 当前 SDK 兼容的 major 版本集合(决策 #4:字符串 {@code "v1"} / {@code "v2"})。 收到不在集合的版本 → dispatcher reject +
   * 上报 WARN,不调 handler,等运维升级 SDK。
   */
  public static final Set<String> SUPPORTED_MAJOR_VERSIONS = Set.of("v1", "v2");

  /** 缺字段时 fallback 主版本(老 orchestrator 没填 schemaVersion 时按 v1 解析)。 */
  public static final String DEFAULT_SCHEMA_VERSION = "v1";

  /**
   * 7 参兼容构造器 —— 历史构造方式(无 schemaVersion)继续可用,schemaVersion 走 {@link #DEFAULT_SCHEMA_VERSION}。新代码推荐用
   * canonical 8 参构造,显式带 schemaVersion。
   */
  public TaskDispatchMessage(
      Long taskId,
      String tenantId,
      String jobCode,
      String taskType,
      String taskInstanceId,
      Map<String, Object> parameters,
      Map<String, Object> runtimeAttributes) {
    this(
        DEFAULT_SCHEMA_VERSION,
        taskId,
        tenantId,
        jobCode,
        taskType,
        taskInstanceId,
        parameters,
        runtimeAttributes);
  }

  /** 校验消息必备字段;不合规抛 {@link IllegalArgumentException},dispatcher 跳过该消息 + 上报 WARN。 */
  public void validate() {
    if (taskId == null) throw new IllegalArgumentException("taskId required");
    if (tenantId == null || tenantId.isBlank())
      throw new IllegalArgumentException("tenantId required");
    if (jobCode == null || jobCode.isBlank())
      throw new IllegalArgumentException("jobCode required");
    if (taskType == null || taskType.isBlank())
      throw new IllegalArgumentException("taskType required");
  }

  /**
   * 解析 schemaVersion 的主版本前缀(如 {@code "v2-rc"} → {@code "v2"};{@code "v2.1"} → {@code
   * "v2"};null/blank → {@link #DEFAULT_SCHEMA_VERSION})。 用于 {@link #isSchemaSupported()} 判定。
   */
  public String resolvedMajor() {
    if (schemaVersion == null || schemaVersion.isBlank()) {
      return DEFAULT_SCHEMA_VERSION;
    }
    // 截断到首个非字母数字字符前(支持 "v2-rc" / "v2.1" / "v2_x" 等后缀格式)
    int end = 0;
    while (end < schemaVersion.length() && Character.isLetterOrDigit(schemaVersion.charAt(end))) {
      end++;
    }
    return end > 0 ? schemaVersion.substring(0, end) : DEFAULT_SCHEMA_VERSION;
  }

  /** 当前 SDK 是否能处理此消息的 schema(false → dispatcher 拒收 + log WARN)。 */
  public boolean isSchemaSupported() {
    return SUPPORTED_MAJOR_VERSIONS.contains(resolvedMajor());
  }
}
