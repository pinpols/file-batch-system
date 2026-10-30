package io.github.pinpols.batch.trigger.domain;

import io.github.pinpols.batch.trigger.support.TriggerDescriptor;

/**
 * 触发器注册管理服务接口。注册、注销、暂停和恢复统一以 WorkflowCode 为调度目标；调度操作不得改变 Job 执行启用状态。
 * {@code registerAll} 在应用启动时幂等执行，租户级批量暂停/恢复不改变持久化 Workflow 调度配置。
 */
public interface TriggerRegistrationService {

  void registerAll();

  void registerByWorkflowCode(String tenantId, String workflowCode);

  /** 发布等待数量和等待时长汇总，不带租户/作业标签。 */
  void reconcileByWorkflowCode(String tenantId, String workflowCode);

  /** 度量已接受但过期的请求，以及近期请求到实例的启动延迟。 */
  void registerRecoveryJob(TriggerDescriptor descriptor);

  void unregisterByWorkflowCode(String tenantId, String workflowCode);

  void removeQuartzJob(String tenantId, String quartzJobIdentity);

  void pauseByWorkflowCode(String tenantId, String workflowCode);

  void resumeByWorkflowCode(String tenantId, String workflowCode);

  void pauseByTenant(String tenantId);

  void resumeByTenant(String tenantId);

  void pauseAll();

  void resumeAll();
}
