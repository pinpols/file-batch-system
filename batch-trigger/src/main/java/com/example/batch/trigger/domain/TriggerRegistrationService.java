package com.example.batch.trigger.domain;

import java.util.List;

/**
 * 触发器注册管理服务接口，定义调度器中触发器的完整生命周期操作契约。 实现类须保证 {@code registerAll} 在应用启动时幂等执行；单个任务的注册/注销/暂停/恢复
 * 支持动态热更新，不影响其他已注册触发器的运行状态。 租户级别的批量暂停/恢复用于租户隔离场景，{@code schedulerStatus} 反映底层调度引擎的整体健康状态。
 */
public interface TriggerRegistrationService {

  void registerAll();

  void registerByJobCode(String tenantId, String jobCode);

  void unregisterByJobCode(String tenantId, String jobCode);

  void pauseByJobCode(String tenantId, String jobCode);

  void resumeByJobCode(String tenantId, String jobCode);

  void pauseByTenant(String tenantId);

  void resumeByTenant(String tenantId);

  List<TriggerStatusInfo> listRegisteredTriggers();

  void pauseAll();

  void resumeAll();

  String schedulerStatus();
}
