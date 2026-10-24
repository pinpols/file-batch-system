package io.github.pinpols.batch.console.application.workflow;

/** 解析指定 Job 所拥有且已启用的受管单作业 Workflow。 */
public interface ManagedJobWorkflowReferencePort {

  boolean isEnabledSingleJobWorkflow(String tenantId, Long workflowDefinitionId, Long jobId);
}
