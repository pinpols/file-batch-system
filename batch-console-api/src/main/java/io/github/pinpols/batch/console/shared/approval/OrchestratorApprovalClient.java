package io.github.pinpols.batch.console.shared.approval;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.console.domain.ops.infrastructure.OrchestratorInternalRestClient;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
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
 *       APPROVED 的审批单解锁任意同类资源）。此前 File 服务版漏了该绑定，现强制补齐； 作业运维路径显式声明 {@link
 *       ApprovalTargetBinding#none()} 保留其原有不绑定行为（后续加固项）。
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
    String requesterId =
        ConsoleTextSanitizer.safeInput(
            command.requesterId() != null ? command.requesterId() : metadata.operatorId(), 64);
    ApprovalSubmitBody body =
        new ApprovalSubmitBody(
            command.tenantId(),
            command.approvalType(),
            command.actionType(),
            command.targetType(),
            command.targetId(),
            command.payloadJson(),
            requesterId,
            metadata.traceId(),
            command.idempotencyKey(),
            ConsoleTextSanitizer.safeInput(command.approvalReason(), 512));
    ApprovalSubmitResponse response =
        orchestratorInternalRestClient
            .build()
            .post()
            .uri("/internal/approvals")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, command.idempotencyKey())
            .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, metadata.requestId())
            .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, metadata.traceId())
            .body(body)
            .retrieve()
            .body(ApprovalSubmitResponse.class);
    if (response == null || !hasText(response.approvalNo())) {
      String messageKey =
          command.emptyResponseMessageKey() != null
              ? command.emptyResponseMessageKey()
              : DEFAULT_EMPTY_RESPONSE_MESSAGE;
      throw BizException.of(ResultCode.SYSTEM_ERROR, messageKey);
    }
    return response.approvalNo();
  }

  /**
   * 校验审批单在本租户为 APPROVED/EXECUTED，<b>且</b>其目标资源与 {@code binding} 一致。 未找到→{@code
   * NOT_FOUND}；状态未过→{@code STATE_CONFLICT}；目标不匹配→{@code FORBIDDEN}（消灭同租越权）。
   */
  public void requireApprovedApproval(
      String tenantId, String approvalNo, ApprovalTargetBinding binding) {
    ApprovalRecordResponse response =
        orchestratorInternalRestClient
            .build()
            .get()
            .uri("/internal/approvals/{approvalNo}?tenantId={tenantId}", approvalNo, tenantId)
            .retrieve()
            .body(ApprovalRecordResponse.class);
    ApprovalRecord record =
        Guard.requireFound(
            response == null ? null : response.record(), "approval request not found");
    String status = record.approvalStatus();
    if (!"APPROVED".equalsIgnoreCase(status) && !"EXECUTED".equalsIgnoreCase(status)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.approval.not_approved_yet");
    }
    if (!binding.matches(record)) {
      throw BizException.of(ResultCode.FORBIDDEN, binding.mismatchMessageKey());
    }
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
   * 审批二次校验的目标绑定（必选参数）：绑定生效（{@code enforced=true}）时审批单目标必须 1:1 命中本次请求资源。
   * 设计为必选而非可空，逼调用方显式声明"绑定到什么"或"有意不绑定"（{@link #none()}），杜绝再次静默漂移出安全缺口。
   */
  public record ApprovalTargetBinding(
      boolean enforced, String targetType, String targetId, String mismatchMessageKey) {

    /** 文件下载场景：{@code targetType=FILE} + {@code targetId=fileId}，沿用文件专属的错误文案。 */
    public static ApprovalTargetBinding file(Long fileId) {
      return new ApprovalTargetBinding(
          true,
          "FILE",
          fileId == null ? null : String.valueOf(fileId),
          FILE_TARGET_MISMATCH_MESSAGE);
    }

    /** 显式声明不做目标绑定（保留调用方原有行为）。仅供既有作业运维路径过渡使用—— 新增校验点一律应绑定目标。 */
    public static ApprovalTargetBinding none() {
      return new ApprovalTargetBinding(false, null, null, null);
    }

    boolean matches(ApprovalRecord record) {
      if (!enforced) {
        return true;
      }
      if (targetId == null || targetType == null) {
        return false;
      }
      return targetType.equalsIgnoreCase(record.targetType()) && targetId.equals(record.targetId());
    }
  }

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

  /** 审批单记录（二次校验只关心状态与目标绑定三元组）。 */
  public record ApprovalRecord(String approvalStatus, String targetType, String targetId) {}
}
