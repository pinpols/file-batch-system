package com.example.batch.sdk.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Body for {@code POST /internal/tasks/{taskId}/report}.
 *
 * <p>字段集对齐 {@code com.example.batch.orchestrator.controller.request.TaskExecutionReportDto}(平台侧
 * Lombok {@code @Data} class)。SDK 用 record + {@link JsonInclude#NON_NULL} 让 Jackson 序列化时跳过 null,
 * 等价平台侧默认行为。
 *
 * <p>字段语义参考:
 *
 * <ul>
 *   <li>{@code success}:执行结果布尔。
 *   <li>{@code message} / {@code resultSummary} / {@code errorCode}:人读 / 摘要 / 错误码,失败路径填。
 *   <li>{@code highWaterMarkOut}:增量水位(仅成功路径)。
 *   <li>{@code outputs}:节点产出 Map,success 路径写到 {@code workflow_node_run.output}(ADR-009 Stage 1.2)。
 *   <li>{@code partitionInvocationId}:ADR-014;mismatch → orchestrator reject report with CONFLICT。
 *   <li>{@code failureClass}:ADR-012 失败分类(仅 failure 路径有意义)。
 *   <li>{@code verifierFailures}:ADR-030 §C/F 产物级失败(仅 success 路径)。每元素 {@code {code, message,
 *       evidence}}。
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportRequest(
    Long taskId,
    String tenantId,
    String workerId,
    String traceId,
    boolean success,
    String code,
    String message,
    String resultSummary,
    String errorCode,
    String highWaterMarkOut,
    Map<String, Object> outputs,
    String partitionInvocationId,
    String failureClass,
    List<Map<String, Object>> verifierFailures) {}
