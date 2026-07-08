package io.github.pinpols.batch.console.domain.job.service;

import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.ops.infrastructure.OrchestratorApprovalClient;
import io.github.pinpols.batch.console.domain.ops.infrastructure.OrchestratorApprovalClient.ApprovalSubmitCommand;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 租户自助重跑/补偿：将请求转为审批工单提交到编排器，等待管理员审批后自动执行。 */
@Service
@RequiredArgsConstructor
public class ConsoleSelfServiceJobService {

  // R7-A1-P1：原来自建 RestClient 漏 X-Internal-Secret，生产关 bypass-mode 时 /internal/approvals 直接 401；
  // 现统一走共享 OrchestratorApprovalClient（内部走带 secret 的 OrchestratorInternalRestClient），
  // 顺带补齐此前缺失的 requestId/traceId header 透传与 requesterId 文本清洗。
  private final OrchestratorApprovalClient approvalClient;
  private final ConsoleTenantGuard tenantGuard;

  public String requestRerun(RerunParam param, String operator, String idempotencyKey) {
    String tenantId = tenantGuard.resolveTenant(param.tenantId());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("tenantId", tenantId);
    payload.put("jobCode", param.jobCode());
    payload.put("bizDate", param.bizDate());
    payload.put("targetInstanceNo", param.targetInstanceNo());
    payload.put("reason", param.reason());
    SubmitApprovalParam approvalParam =
        SubmitApprovalParam.builder()
            .tenantId(tenantId)
            .actionType("RERUN")
            .targetType("JOB_INSTANCE")
            .targetId(param.jobCode())
            .payloadJson(JsonUtils.toJson(payload))
            .operator(operator)
            .idempotencyKey(idempotencyKey)
            .build();
    return submitApproval(approvalParam);
  }

  public String requestCompensation(
      CompensationParam param, String operator, String idempotencyKey) {
    String tenantId = tenantGuard.resolveTenant(param.tenantId());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("tenantId", tenantId);
    payload.put("jobCode", param.jobCode());
    payload.put("bizDate", param.bizDate());
    payload.put("compensationType", param.compensationType());
    payload.put("targetInstanceNo", param.targetInstanceNo());
    payload.put("reason", param.reason());
    SubmitApprovalParam approvalParam =
        SubmitApprovalParam.builder()
            .tenantId(tenantId)
            .actionType("COMPENSATION")
            .targetType("JOB_INSTANCE")
            .targetId(param.jobCode())
            .payloadJson(JsonUtils.toJson(payload))
            .operator(operator)
            .idempotencyKey(idempotencyKey)
            .build();
    return submitApproval(approvalParam);
  }

  private String submitApproval(SubmitApprovalParam param) {
    // 保留本调用方对外可见的错误 key error.approval.submit_failed（前端/i18n 已有映射）。
    return approvalClient.submitApproval(
        ApprovalSubmitCommand.builder()
            .tenantId(param.tenantId())
            .approvalType("SELF_SERVICE")
            .actionType(param.actionType())
            .targetType(param.targetType())
            .targetId(param.targetId())
            .payloadJson(param.payloadJson())
            .requesterId(param.operator())
            .idempotencyKey(param.idempotencyKey())
            .emptyResponseMessageKey("error.approval.submit_failed")
            .build());
  }

  @Builder
  private record SubmitApprovalParam(
      String tenantId,
      String actionType,
      String targetType,
      String targetId,
      String payloadJson,
      String operator,
      String idempotencyKey) {}

  public record RerunParam(
      String tenantId, String jobCode, String bizDate, String targetInstanceNo, String reason) {}

  public record CompensationParam(
      String tenantId,
      String jobCode,
      String bizDate,
      String compensationType,
      String targetInstanceNo,
      String reason) {}
}
