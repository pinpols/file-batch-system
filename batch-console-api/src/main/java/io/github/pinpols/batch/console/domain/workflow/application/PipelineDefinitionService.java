package io.github.pinpols.batch.console.domain.workflow.application;

import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.PipelineDefinitionSaveRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.ConsolePipelineDefinitionListItemResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.PipelineDefinitionDetailResponse;

/** 流水线定义应用服务：管理流水线定义的 CRUD 及启停操作。 */
public interface PipelineDefinitionService {

  PageResponse<ConsolePipelineDefinitionListItemResponse> list(
      String tenantId,
      String jobCode,
      String pipelineType,
      Boolean enabled,
      int pageNo,
      int pageSize);

  PipelineDefinitionDetailResponse detail(Long id, String tenantId);

  PipelineDefinitionDetailResponse create(PipelineDefinitionSaveRequest request);

  PipelineDefinitionDetailResponse update(Long id, PipelineDefinitionSaveRequest request);

  void toggle(Long id, String tenantId, Boolean enabled);
}
