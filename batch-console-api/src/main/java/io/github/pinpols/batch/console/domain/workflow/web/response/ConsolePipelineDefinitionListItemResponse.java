package io.github.pinpols.batch.console.domain.workflow.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;

/**
 * 流水线定义列表项响应（{@code PipelineDefinitionMapper.selectByQuery} 投影）。
 *
 * <p><b>wire 红线</b>：mapper 显式列出 snake_case 列且不带别名，历史响应键为 snake_case（{@code job_code} / {@code
 * pipeline_type} 等）。每个多词字段显式 {@code @JsonProperty} 回 snake_case。列全为标量（无 jsonb）。 详情/创建/更新走 {@code
 * PipelineDefinitionDetailResponse}，此 record 仅覆盖 list 端点。
 *
 * <p>MyBatis {@code resultType="map"} 省略 null 列 → {@code NON_NULL} 保键集对等。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsolePipelineDefinitionListItemResponse(
    Long id,
    @JsonProperty("tenant_id") String tenantId,
    @JsonProperty("job_code") String jobCode,
    @JsonProperty("pipeline_name") String pipelineName,
    @JsonProperty("pipeline_type") String pipelineType,
    @JsonProperty("biz_type") String bizType,
    @JsonProperty("worker_group") String workerGroup,
    Integer version,
    Boolean enabled,
    String description,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("updated_at") Instant updatedAt) {

  public static ConsolePipelineDefinitionListItemResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsolePipelineDefinitionListItemResponse(
        WorkflowResponseFieldReader.longValue(row, "id"),
        WorkflowResponseFieldReader.stringValue(row, "tenant_id"),
        WorkflowResponseFieldReader.stringValue(row, "job_code"),
        WorkflowResponseFieldReader.stringValue(row, "pipeline_name"),
        WorkflowResponseFieldReader.stringValue(row, "pipeline_type"),
        WorkflowResponseFieldReader.stringValue(row, "biz_type"),
        WorkflowResponseFieldReader.stringValue(row, "worker_group"),
        WorkflowResponseFieldReader.integerValue(row, "version"),
        WorkflowResponseFieldReader.booleanValue(row, "enabled"),
        WorkflowResponseFieldReader.stringValue(row, "description"),
        WorkflowResponseFieldReader.instantValue(row, "created_at"),
        WorkflowResponseFieldReader.instantValue(row, "updated_at"));
  }
}
