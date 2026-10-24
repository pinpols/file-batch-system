package io.github.pinpols.batch.trigger.domain;

/**
 * 触发器注册管理服务接口。注册、注销、暂停和恢复以 WorkflowCode 为权威目标，JobCode 方法仅兼容显式受管单作业 Workflow；
 * 调度操作不得改变 Job 执行启用状态。{@code registerAll} 在应用启动时幂等执行，租户级批量暂停/恢复不改变持久化 Workflow 调度配置。
 */
public interface TriggerRegistrationService {

  void registerAll();

  void registerByJobCode(String tenantId, String jobCode);

  void registerByWorkflowCode(String tenantId, String workflowCode);

  void unregisterByJobCode(String tenantId, String jobCode);

  void unregisterByWorkflowCode(String tenantId, String workflowCode);

  void removeQuartzJob(String tenantId, String quartzJobIdentity);

  void pauseByJobCode(String tenantId, String jobCode);

  void pauseByWorkflowCode(String tenantId, String workflowCode);

  void resumeByJobCode(String tenantId, String jobCode);

  void resumeByWorkflowCode(String tenantId, String workflowCode);

  void pauseByTenant(String tenantId);

  void resumeByTenant(String tenantId);

  void pauseAll();

  void resumeAll();
}
