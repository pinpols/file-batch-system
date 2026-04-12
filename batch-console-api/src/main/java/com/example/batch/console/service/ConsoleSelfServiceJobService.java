package com.example.batch.console.service;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.config.ConsoleOrchestratorClientProperties;
import com.example.batch.console.support.ConsoleTenantGuard;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** 租户自助重跑/补偿：将请求转为审批工单提交到编排器，等待管理员审批后自动执行。 */
@Service
@RequiredArgsConstructor
public class ConsoleSelfServiceJobService {

  private final RestClient.Builder restClientBuilder;
  private final ConsoleOrchestratorClientProperties orchestratorClientProperties;
  private final ConsoleTenantGuard tenantGuard;
  private final Environment environment;

  public String requestRerun(RerunParam param, String operator, String idempotencyKey) {
    String tenantId = tenantGuard.resolveTenant(param.tenantId());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("tenantId", tenantId);
    payload.put("jobCode", param.jobCode());
    payload.put("bizDate", param.bizDate());
    payload.put("targetInstanceNo", param.targetInstanceNo());
    payload.put("reason", param.reason());
    return submitApproval(
        tenantId,
        "RERUN",
        "JOB_INSTANCE",
        param.jobCode(),
        JsonUtils.toJson(payload),
        operator,
        idempotencyKey);
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
    return submitApproval(
        tenantId,
        "COMPENSATION",
        "JOB_INSTANCE",
        param.jobCode(),
        JsonUtils.toJson(payload),
        operator,
        idempotencyKey);
  }

  @SuppressWarnings("unchecked")
  private String submitApproval(
      String tenantId,
      String actionType,
      String targetType,
      String targetId,
      String payloadJson,
      String operator,
      String idempotencyKey) {
    String baseUrl = resolveUrl(orchestratorClientProperties.getBaseUrl());
    RestClient client = restClientBuilder.baseUrl(baseUrl).build();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenantId", tenantId);
    body.put("approvalType", "SELF_SERVICE");
    body.put("actionType", actionType);
    body.put("targetType", targetType);
    body.put("targetId", targetId);
    body.put("payloadJson", payloadJson);
    body.put("requesterId", operator);
    Map<String, Object> response =
        client
            .post()
            .uri("/internal/approvals/submit")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
            .body(body)
            .retrieve()
            .body(Map.class);
    if (response == null || !response.containsKey("approvalNo")) {
      throw new BizException(ResultCode.SYSTEM_ERROR, "Failed to submit approval");
    }
    return (String) response.get("approvalNo");
  }

  private String resolveUrl(String url) {
    return environment.resolveRequiredPlaceholders(url);
  }

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
