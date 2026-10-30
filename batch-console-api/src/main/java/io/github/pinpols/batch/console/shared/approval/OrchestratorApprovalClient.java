package io.github.pinpols.batch.console.shared.approval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.shared.client.OrchestratorInternalRestClient;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.Map;
import java.util.Objects;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * console 内唯一的 orchestrator 审批客户端：提交审批工单（{@code POST /internal/approvals}）+ 校验审批已通过态（{@code GET
 * /internal/approvals/{no}}）。取代此前散落在 {@code ConsoleJobOpsSupport} / {@code
 * DefaultConsoleFileApplicationService} / {@code DefaultConsoleFileDownloadApplicationService} /
 * {@code ConsoleSelfServiceJobService} 四处近乎逐行相同、且已漂移的复制代码。
 *
 * <p>统一约定（消灭漂移）：
 *
 * <ul>
 *   <li><b>请求追踪三件套</b>：提交都带 {@code Idempotency-Key / X-Request-Id / X-Trace-Id}（此前 SelfService 版只带
 *       Idempotency-Key）。
 *   <li><b>自由文本清洗</b>：{@code requesterId} / {@code approvalReason} 一律经 {@link
 *       ConsoleTextSanitizer#safeInput} 截断并过滤控制字符（此前 SelfService 版 requesterId 未清洗）。
 *   <li><b>审批二次校验带必选目标绑定参数</b>：{@link #requireApprovedApproval} 必须传 {@link ApprovalTargetBinding}
 *       —— 绑定生效时审批单的 {@code targetType/targetId} 必须与本次请求资源一致，否则 {@code FORBIDDEN}。 不绑定会导致同租越权（任一已
 *       APPROVED 的审批单解锁任意同类资源）。文件绑定目标；作业运维还须通过 {@link #requireApprovedExecution}
 *       绑定动作与完整执行载荷。
 *   <li><b>状态判定</b>：{@code APPROVED} 与 {@code EXECUTED} 均视为通过（已执行=幂等重放不被拒）。
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class OrchestratorApprovalClient {

  private static final String DEFAULT_EMPTY_RESPONSE_MESSAGE = "error.approval.empty_response";
  private static final String FILE_TARGET_MISMATCH_MESSAGE = "error.approval.target_file_mismatch";

  private final OrchestratorInternalRestClient orchestratorInternalRestClient;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;

  /**
   * 提交审批工单，返回 orchestrator 分配的 {@code approvalNo}。空响应/缺 approvalNo 时抛 {@code SYSTEM_ERROR}，错误 key
   * 由调用方 {@link ApprovalSubmitCommand#emptyResponseMessageKey()} 指定（保留各调用方现有对外 key），默认 {@code
   * error.approval.empty_response}。
   */
  public String submitApproval(ApprovalSubmitCommand command) {
    ConsoleRequestMetadata metadata = requestMetadataResolver.current();
    String requesterId = ConsoleTextSanitizer.safeInput(
        command.requesterId() != null ? command.requesterId() : metadata.operatorId(), 64);
    String tenantId = command.tenantId();
    String approvalType = command.approvalType();
    String actionType = command.actionType();
    String targetType = command.targetType();
    String targetId = command.targetId();
    String payloadJson = command.payloadJson();
    String traceId = metadata.traceId();
    String idempotencyKey = command.idempotencyKey();
    String approvalReason = ConsoleTextSanitizer.safeInput(command.approvalReason(), 512);
    ApprovalSubmitBody body = ApprovalSubmitBody.builder()
        .tenantId(tenantId)
        .approvalType(approvalType)
        .actionType(actionType)
        .targetType(targetType)
        .targetId(targetId)
        .payloadJson(payloadJson)
        .requesterId(requesterId)
        .sourceTraceId(traceId)
        .sourceIdempotencyKey(idempotencyKey)
        .approvalReason(approvalReason)
        .build();
    ApprovalSubmitResponse response = orchestratorInternalRestClient
        .client()
        .post()
        .uri("/internal/approvals")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, command.idempotencyKey())
        .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, metadata.requestId())
        .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, metadata.traceId())
        .body(body)
        .retrieve()
        .body(ApprovalSubmitResponse.class);
    if (response == null || !hasText(response.approvalNo())) {
      String messageKey = command.emptyResponseMessageKey() != null
          ? command.emptyResponseMessageKey()
          : DEFAULT_EMPTY_RESPONSE_MESSAGE;
      throw BizException.of(ResultCode.SYSTEM_ERROR, messageKey);
    }
    return response.approvalNo();
  }

  /** 查询已持久化的来源审批；未找到或调用失败均不代表可以重新提交。 */
  public ApprovalSourceRecord lookupBySourceKey(String tenantId, String sourceIdempotencyKey) {
    if (!hasText(tenantId) || !hasText(sourceIdempotencyKey)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument");
    }
    ApprovalSourceLookupResponse response = orchestratorInternalRestClient
        .client()
        .get()
        .uri("/internal/approvals/by-source-key?tenantId={tenantId}", tenantId)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, sourceIdempotencyKey)
        .retrieve()
        .body(ApprovalSourceLookupResponse.class);
    ApprovalSourceRecord record = EmptyChecks.isNull(response) ? null : response.record();
    if (EmptyChecks.isNull(record)
        || !tenantId.equals(record.tenantId())
        || !sourceIdempotencyKey.equals(record.sourceIdempotencyKey())
        || !hasText(record.approvalNo())) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.approval.empty_response");
    }
    return record;
  }

  /**
   * 按已冻结且与提交载荷一致的参数校验回查记录；未查到或不匹配均不能证明可重新提交。
   */
  public ApprovalSourceRecord lookupVerifiedSubmission(ApprovalSubmitCommand expected) {
    if (EmptyChecks.isNull(expected)
        || !hasText(expected.tenantId())
        || !hasText(expected.idempotencyKey())
        || !hasText(expected.approvalType())
        || !hasText(expected.actionType())
        || !hasText(expected.targetType())
        || !hasText(expected.payloadJson())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument");
    }
    ApprovalSourceRecord record = lookupBySourceKey(expected.tenantId(), expected.idempotencyKey());
    boolean sameIdentity = Objects.equals(expected.approvalType(), record.approvalType())
        && Objects.equals(expected.actionType(), record.actionType())
        && Objects.equals(expected.targetType(), record.targetType())
        && Objects.equals(expected.targetId(), record.targetId())
        && Objects.equals(expected.requesterId(), record.requesterId())
        && Objects.equals(expected.approvalReason(), record.sourceApprovalReason());
    try {
      Object expectedPayload = JsonUtils.fromJsonStrict(expected.payloadJson(), Object.class);
      Object actualPayload = JsonUtils.fromJsonStrict(record.payloadJson(), Object.class);
      if (!sameIdentity
          || EmptyChecks.isNull(expectedPayload)
          || !expectedPayload.equals(actualPayload)) {
        throw BizException.of(ResultCode.STATE_CONFLICT, "error.approval.idempotency_conflict");
      }
    } catch (IllegalArgumentException invalidPayload) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.approval.idempotency_conflict");
    }
    return record;
  }

  /**
   * 校验审批单在本租户为 APPROVED/EXECUTED，<b>且</b>其目标资源与 {@code binding} 一致。 未找到→{@code
   * NOT_FOUND}；状态未过→{@code STATE_CONFLICT}；目标不匹配→{@code FORBIDDEN}（消灭同租越权）。
   */
  public void requireApprovedApproval(
      String tenantId, String approvalNo, ApprovalTargetBinding binding) {
    ApprovalRecord approvalRecord = loadApprovedApproval(tenantId, approvalNo);
    if (!binding.matches(approvalRecord)) {
      throw BizException.of(ResultCode.FORBIDDEN, binding.mismatchMessageKey());
    }
  }

  /** 执行参数必须与批准的业务载荷一致；只有认证流程回填的审批号和操作者不参与业务参数比较。 */
  public void requireApprovedExecution(
      String tenantId, String approvalNo, String actionType, Object executionPayload) {
    ApprovalRecord approval = loadApprovedApproval(tenantId, approvalNo);
    if (!actionType.equals(approval.actionType()) || EmptyChecks.isNull(executionPayload)) {
      throw executionMismatch();
    }
    Object approvedPayload;
    try {
      approvedPayload =
          JsonUtils.fromJsonStrict(approval.payloadJson(), executionPayload.getClass());
    } catch (IllegalArgumentException invalidPayload) {
      throw executionMismatch();
    }
    if (EmptyChecks.isNull(approvedPayload)) {
      throw executionMismatch();
    }
    Map<String, Object> approvedBusinessPayload = businessPayload(approvedPayload);
    Map<String, Object> executionBusinessPayload = businessPayload(executionPayload);
    if (!approvedBusinessPayload.equals(executionBusinessPayload)) {
      throw executionMismatch();
    }
  }

  private Map<String, Object> businessPayload(Object payload) {
    Map<String, Object> fields = JsonUtils.toMap(payload);
    fields.remove("approvalId");
    fields.remove("operatorId");
    return fields;
  }

  private BizException executionMismatch() {
    return BizException.of(ResultCode.FORBIDDEN, "error.approval.execution_mismatch");
  }

  private ApprovalRecord loadApprovedApproval(String tenantId, String approvalNo) {
    ApprovalRecordResponse response = orchestratorInternalRestClient
        .client()
        .get()
        .uri("/internal/approvals/{approvalNo}?tenantId={tenantId}", approvalNo, tenantId)
        .retrieve()
        .body(ApprovalRecordResponse.class);
    ApprovalRecord approvalRecord = Guard.requireFound(
        response == null ? null : response.record(), "approval request not found");
    String status = approvalRecord.approvalStatus();
    if (!"APPROVED".equalsIgnoreCase(status) && !"EXECUTED".equalsIgnoreCase(status)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.approval.not_approved_yet");
    }
    return approvalRecord;
  }

  private static boolean hasText(String text) {
    return text != null && !text.isBlank();
  }

  /**
   * 提交审批的入参。{@code tenantId} 由调用方自行解析（ops 走 tenantGuard.resolveTenant，File 保留其原有的直接取 body tenantId
   * 行为，SelfService 已预先解析）。{@code requesterId} 为 null 时客户端回退到当前请求上下文的 operatorId。
   */
  @Builder
  public record ApprovalSubmitCommand(
      String tenantId,
      String approvalType,
      String actionType,
      String targetType,
      String targetId,
      String payloadJson,
      String requesterId,
      String approvalReason,
      String idempotencyKey,
      String emptyResponseMessageKey) {}

  /**
   * 文件审批二次校验的目标绑定：目标必须 1:1 命中本次请求资源，不提供关闭绑定的入口。
   */
  public record ApprovalTargetBinding(
      String targetType, String targetId, String mismatchMessageKey) {

    /** 文件下载场景：{@code targetType=FILE} + {@code targetId=fileId}，沿用文件专属的错误文案。 */
    public static ApprovalTargetBinding file(Long fileId) {
      return new ApprovalTargetBinding(
          "FILE",
          EmptyChecks.isNull(fileId) ? null : String.valueOf(fileId),
          FILE_TARGET_MISMATCH_MESSAGE);
    }

    boolean matches(ApprovalRecord record) {
      if (targetId == null || targetType == null) {
        return false;
      }
      return targetType.equalsIgnoreCase(record.targetType()) && targetId.equals(record.targetId());
    }
  }

  @Builder
  private record ApprovalSubmitBody(
      String tenantId,
      String approvalType,
      String actionType,
      String targetType,
      String targetId,
      String payloadJson,
      String requesterId,
      String sourceTraceId,
      String sourceIdempotencyKey,
      String approvalReason) {}

  /** 提交审批的响应（orchestrator 返回的工单号）。 */
  public record ApprovalSubmitResponse(String approvalNo) {}

  /** 审批单查询响应包装。 */
  public record ApprovalRecordResponse(ApprovalRecord record) {}

  /** 来源查询保留原始身份和业务载荷，供后续对账比对。 */
  public record ApprovalSourceLookupResponse(ApprovalSourceRecord record) {}

  @Builder
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ApprovalSourceRecord(
      String tenantId,
      String approvalNo,
      String sourceIdempotencyKey,
      String approvalType,
      String actionType,
      String targetType,
      String targetId,
      String payloadJson,
      String requesterId,
      String approvalReason,
      String sourceApprovalReason,
      String approvalStatus) {}

  /** 审批执行前读取状态、资源身份、动作和冻结载荷。 */
  public record ApprovalRecord(
      String approvalStatus,
      String targetType,
      String targetId,
      String actionType,
      String payloadJson) {}
}
