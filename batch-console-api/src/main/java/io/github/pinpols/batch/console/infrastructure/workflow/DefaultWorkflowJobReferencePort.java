package io.github.pinpols.batch.console.infrastructure.workflow;

import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.console.application.workflow.WorkflowJobReferencePort;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 基于 Job 定义 Mapper 的 Workflow 引用查询适配器。 */
@Component
@RequiredArgsConstructor
public class DefaultWorkflowJobReferencePort implements WorkflowJobReferencePort {

  private final JobDefinitionMapper jobDefinitionMapper;

  @Override
  public boolean isWorkflowType(String tenantId, String jobCode) {
    JobDefinitionEntity job = jobDefinitionMapper.selectByUniqueKey(tenantId, jobCode);
    return job != null && JobType.WORKFLOW.code().equalsIgnoreCase(job.getJobType());
  }
}
