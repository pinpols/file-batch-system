package com.example.batch.console.application;

import com.example.batch.console.web.request.WorkflowDefinitionSaveRequest;
import com.example.batch.console.web.response.WorkflowDefinitionDetailResponse;

import java.util.List;

/**
 * 工作流定义应用服务：管理工作流定义的 CRUD 及 DAG 校验操作。
 */
public interface ConsoleWorkflowDefinitionApplicationService {

    WorkflowDefinitionDetailResponse getById(Long id, String tenantId);

    WorkflowDefinitionDetailResponse create(WorkflowDefinitionSaveRequest request);

    WorkflowDefinitionDetailResponse update(Long id, WorkflowDefinitionSaveRequest request);

    void toggleEnabled(Long id, String tenantId, Boolean enabled);

    void delete(Long id, String tenantId);

    DagValidationResult validate(Long id, String tenantId);

    record DagValidationResult(boolean valid, List<String> errors) {}
}
