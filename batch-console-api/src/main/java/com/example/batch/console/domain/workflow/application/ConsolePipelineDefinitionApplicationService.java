package com.example.batch.console.domain.workflow.application;

import com.example.batch.common.model.PageResponse;
import com.example.batch.console.domain.workflow.web.request.PipelineDefinitionSaveRequest;
import com.example.batch.console.domain.workflow.web.response.PipelineDefinitionDetailResponse;
import java.util.Map;

/** 流水线定义应用服务：管理流水线定义的 CRUD 及启停操作。 */
public interface ConsolePipelineDefinitionApplicationService {

  PageResponse<Map<String, Object>> list(
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
