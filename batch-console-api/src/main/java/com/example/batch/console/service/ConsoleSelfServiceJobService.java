package com.example.batch.console.service;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.infrastructure.ops.OrchestratorInternalRestClient;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** 租户自助重跑/补偿：将请求转为审批工单提交到编排器，等待管理员审批后自动执行。 */
@Service
@RequiredArgsConstructor
public class ConsoleSelfServiceJobService {

  // R7-A1-P1：原来自建 RestClient 漏 X-Internal-Secret，生产关 bypass-mode 时
  // /internal/approvals 直接 401；改走标准入口 OrchestratorInternalRestClient.
  private final OrchestratorInternalRestClient orchestratorInternalRestClient;
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

  @SuppressWarnings("unchecked")
  private String submitApproval(SubmitApprovalParam param) {
    RestClient client = orchestratorInternalRestClient.build();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenantId", param.tenantId());
    body.put("approvalType", "SELF_SERVICE");
    body.put("actionType", param.actionType());
    body.put("targetType", param.targetType());
    body.put("targetId", param.targetId());
    body.put("payloadJson", param.payloadJson());
    body.put("requesterId", param.operator());
    Map<String, Object> response =
        client
            .post()
            .uri("/internal/approvals")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, param.idempotencyKey())
            .body(body)
            .retrieve()
            .body(Map.class);
    if (response == null || !response.containsKey("approvalNo")) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.approval.submit_failed");
    }
    return (String) response.get("approvalNo");
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
