package io.github.pinpols.batch.console.domain.workflow.query;

import io.github.pinpols.batch.common.model.PageRequest;
import java.util.Objects;

/** 已解析租户的列表条件；计数与数据页复用筛选，分页只由列表 SQL 使用。 */
public record PipelineDefinitionQuery(
    String tenantId, String jobCode, String pipelineType, Boolean enabled, PageRequest pageRequest) {

  public PipelineDefinitionQuery {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(pageRequest, "pageRequest");
  }
}
