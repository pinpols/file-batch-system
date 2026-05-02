package com.example.batch.console.application.monitor;

import com.example.batch.console.web.request.ops.AlertActionRequest;
import com.example.batch.console.web.response.ops.ConsoleAlertActionResponse;

/** 控制台告警治理应用服务：确认、静默、关闭告警。 */
public interface ConsoleAlertApplicationService {

  /** 确认告警。 */
  ConsoleAlertActionResponse ack(Long alertId, AlertActionRequest request, String idempotencyKey);

  /** 静默告警。 */
  ConsoleAlertActionResponse silence(
      Long alertId, AlertActionRequest request, String idempotencyKey);

  /** 关闭告警。 */
  ConsoleAlertActionResponse close(Long alertId, AlertActionRequest request, String idempotencyKey);
}
