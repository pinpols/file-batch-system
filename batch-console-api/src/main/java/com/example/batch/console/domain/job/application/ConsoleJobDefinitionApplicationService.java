package com.example.batch.console.domain.job.application;

import com.example.batch.console.domain.job.web.request.JobDefinitionCopyRequest;
import com.example.batch.console.domain.job.web.request.JobDefinitionCreateRequest;
import com.example.batch.console.domain.job.web.request.JobDefinitionUpdateRequest;
import com.example.batch.console.domain.job.web.response.ConsoleJobDefinitionResponse;
import java.util.List;

/** 作业定义应用服务：管理作业定义的 CRUD 操作。 */
public interface ConsoleJobDefinitionApplicationService {

  ConsoleJobDefinitionResponse detail(Long id, String tenantId);

  ConsoleJobDefinitionResponse create(JobDefinitionCreateRequest request);

  ConsoleJobDefinitionResponse update(Long id, JobDefinitionUpdateRequest request);

  void toggle(Long id, String tenantId, Boolean enabled);

  /** 批量启停作业定义。 */
  int batchToggle(String tenantId, List<Long> ids, Boolean enabled);

  ConsoleJobDefinitionResponse copy(Long id, String tenantId, String newJobCode);

  /** 克隆作业定义并可选覆盖字段。 */
  ConsoleJobDefinitionResponse copyWithOverrides(Long id, JobDefinitionCopyRequest request);
}
