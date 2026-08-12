package io.github.pinpols.batch.console.application.ops;

import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.console.shared.command.ApprovalSubmitContext;
import io.github.pinpols.batch.console.shared.command.CompensationPayload;
import io.github.pinpols.batch.console.shared.command.ConsoleLaunchCommand;
import java.time.LocalDate;
import java.util.Map;

/** Job 操作所需的跨上下文端口。 */
public interface ConsoleJobOperationsPort {

  String JOB_TYPE_COMPENSATION = "COMPENSATION";

  String resolveTenant(String requestTenantId);

  void publishRefresh(String tenantId);

  String delegateLaunch(ConsoleLaunchCommand command);

  String submitCompensation(CompensationPayload payload, String idempotencyKey);

  String triggerRecovery(String tenantId, String uriTemplate, Long targetId, String idempotencyKey);

  String submitApproval(ApprovalSubmitContext context);

  void requireApprovedApproval(String tenantId, String approvalNo);

  boolean hasText(String text);

  TriggerType resolveTriggerType(String triggerTypeValue, TriggerType defaultTriggerType);

  LocalDate parseBizDate(String bizDate);

  LocalDate parseOptionalBizDate(String bizDate);

  Map<String, Object> parsePayload(String payloadJson);
}
