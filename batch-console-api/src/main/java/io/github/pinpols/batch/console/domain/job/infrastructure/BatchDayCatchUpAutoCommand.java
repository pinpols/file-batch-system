package io.github.pinpols.batch.console.domain.job.infrastructure;

import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.console.shared.command.ConsoleLaunchCommand;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Builder;

/** AUTO 首次提交、接续和回查共用的冻结请求载荷。 */
@Builder
record BatchDayCatchUpAutoCommand(
    String tenantId,
    String jobCode,
    Long workflowId,
    String bizDate,
    String calendarCode,
    String reason,
    String sourceKey) {
  ConsoleLaunchCommand toLaunchCommand() {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("operationType", "BATCH_DAY_CATCH_UP");
    params.put("approvalMode", "AUTO");
    params.put("batchDayCatchUp", true);
    params.put("batchDayBizDate", bizDate);
    params.put("batchDayCalendarCode", calendarCode);
    params.put("jobCode", jobCode);
    params.put("reason", reason);
    params.put("catchUpPolicy", "AUTO");
    return new ConsoleLaunchCommand(
        tenantId, jobCode, workflowId, bizDate, TriggerType.CATCH_UP, params, sourceKey);
  }
}
