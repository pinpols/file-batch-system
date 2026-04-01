package com.example.batch.console.application;

import com.example.batch.console.web.request.JobDefinitionCreateRequest;
import com.example.batch.console.web.request.JobDefinitionUpdateRequest;
import com.example.batch.console.web.response.ConsoleJobDefinitionResponse;

/**
 * 作业定义应用服务：管理作业定义的 CRUD 操作。
 */
public interface ConsoleJobDefinitionApplicationService {

    ConsoleJobDefinitionResponse detail(Long id, String tenantId);

    ConsoleJobDefinitionResponse create(JobDefinitionCreateRequest request);

    ConsoleJobDefinitionResponse update(Long id, JobDefinitionUpdateRequest request);

    void toggle(Long id, String tenantId, Boolean enabled);

    void delete(Long id, String tenantId);

    ConsoleJobDefinitionResponse copy(Long id, String tenantId, String newJobCode);
}
