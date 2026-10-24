package io.github.pinpols.batch.console.domain.job.service;

import lombok.Builder;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.job.entity.JobInstanceEntity;
import io.github.pinpols.batch.console.domain.job.mapper.JobInstanceMapper;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient.ApprovalSubmitCommand;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
  private final TenantIdResolver tenantGuard;
  private final JobInstanceMapper jobInstanceMapper;

  public String requestRerun(RerunParam param, String operator, String idempotencyKey) {
    String tenantId = tenantGuard.resolveTenant(param.tenantId());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("tenantId", tenantId);
    payload.put("jobCode", param.jobCode());
    payload.put("bizDate", param.bizDate());
    payload.put("targetInstanceNo", param.targetInstanceNo());
    payload.put("reason", param.reason());
    SubmitApprovalParam approvalParam = SubmitApprovalParam.builder()
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
    boolean jobCompensationTargetIsInvalid = !"JOB".equalsIgnoreCase(param.compensationType())
        || EmptyChecks.isNull(param.targetInstanceNo())
        || EmptyChecks.isBlank(param.targetInstanceNo());
    if (jobCompensationTargetIsInvalid) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument");
    }
    List<JobInstanceEntity> matchingInstances =
        jobInstanceMapper.selectByInstanceNos(tenantId, List.of(param.targetInstanceNo())).stream()
            .filter(instance -> Objects.equals(instance.getJobCode(), param.jobCode()))
            .filter(instance -> {
              LocalDate instanceBizDate = instance.getBizDate();
              LocalDate requestedBizDate = parseBizDate(param.bizDate());
              return Objects.equals(instanceBizDate, requestedBizDate);
            })
            .toList();
    if (matchingInstances.size() != 1
        || EmptyChecks.isNull(matchingInstances.getFirst().getId())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument");
    }
    JobInstanceEntity target = matchingInstances.getFirst();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("tenantId", tenantId);
    payload.put("jobCode", param.jobCode());
    payload.put("bizDate", param.bizDate());
    payload.put("compensationType", "JOB");
    payload.put("targetId", target.getId());
    payload.put("targetInstanceNo", param.targetInstanceNo());
    payload.put("reason", param.reason());
    SubmitApprovalParam approvalParam = SubmitApprovalParam.builder()
        .tenantId(tenantId)
        .actionType("COMPENSATION")
        .targetType("JOB_INSTANCE")
        .targetId(String.valueOf(target.getId()))
        .payloadJson(JsonUtils.toJson(payload))
        .operator(operator)
        .idempotencyKey(idempotencyKey)
        .build();
    return submitApproval(approvalParam);
  }

  private static LocalDate parseBizDate(String bizDate) {
    try {
      return LocalDate.parse(bizDate);
    } catch (RuntimeException exception) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument");
    }
  }

  private String submitApproval(SubmitApprovalParam param) {
    // 保留本调用方对外可见的错误 key error.approval.submit_failed（前端/i18n 已有映射）。
    ApprovalSubmitCommand approvalCommand = ApprovalSubmitCommand.builder()
        .tenantId(param.tenantId())
        .approvalType("SELF_SERVICE")
        .actionType(param.actionType())
        .targetType(param.targetType())
        .targetId(param.targetId())
        .payloadJson(param.payloadJson())
        .requesterId(param.operator())
        .idempotencyKey(param.idempotencyKey())
        .emptyResponseMessageKey("error.approval.submit_failed")
        .build();
    return approvalClient.submitApproval(approvalCommand);
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
