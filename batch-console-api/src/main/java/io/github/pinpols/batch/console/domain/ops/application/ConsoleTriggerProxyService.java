package io.github.pinpols.batch.console.domain.ops.application;

import java.util.List;
import java.util.Map;

/** 触发器代理服务：转发控制台对调度器与触发器管理接口的操作。 */
public interface ConsoleTriggerProxyService {

  Map<String, String> schedulerStatus();

  Map<String, String> schedulerPauseAll();

  Map<String, String> schedulerResumeAll();

  List<Object> triggerList();

  Map<String, String> triggerAction(String tenantId, String jobCode, String action);

  Map<String, String> pauseByTenant(String tenantId);

  Map<String, String> resumeByTenant(String tenantId);
}
