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
    @JsonProperty("runtimeAttributes") Map<String, Object> runtimeAttributes) {

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
}
