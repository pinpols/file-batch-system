package io.github.pinpols.batch.console.application.workflow;

/** Workflow 校验所需的最小 Job 引用查询端口。 */
public interface WorkflowJobReferencePort {

  /** 判断租户下指定编码是否为 WORKFLOW 类型的 Job。 */
  boolean isWorkflowType(String tenantId, String jobCode);
}
