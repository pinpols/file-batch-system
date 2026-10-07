package io.github.pinpols.batch.console.application.ops;

import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleSchedulerCommandResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleTriggerActionResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleTriggerStatusResponse;
import java.util.List;

/** 触发器代理服务：转发控制台对调度器与触发器管理接口的操作。 */
public interface ConsoleTriggerProxyService {

  ConsoleSchedulerCommandResponse schedulerStatus();

  ConsoleSchedulerCommandResponse schedulerPauseAll();

  ConsoleSchedulerCommandResponse schedulerResumeAll();

  List<ConsoleTriggerStatusResponse> triggerList();

  ConsoleTriggerActionResponse triggerAction(String tenantId, String jobCode, String action);

  ConsoleTriggerActionResponse pauseByTenant(String tenantId);

  ConsoleTriggerActionResponse resumeByTenant(String tenantId);
}
