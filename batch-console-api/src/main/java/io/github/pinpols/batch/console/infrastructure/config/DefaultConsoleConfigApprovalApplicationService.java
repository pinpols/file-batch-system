package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.enums.ConfigLifecycleStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.AuditLogConstants;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.application.config.ConfigReleaseApplyService;
import io.github.pinpols.batch.console.application.config.ConsoleConfigApprovalApplicationService;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigApprovalActionRequest;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigReleaseApprovalSubmitRequest;
import io.github.pinpols.batch.console.domain.entity.ConfigReleaseEntity;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleConfigApprovalDetailResponse;
import io.github.pinpols.batch.console.domain.ops.mapper.ConfigApprovalMapper;
import io.github.pinpols.batch.console.domain.ops.param.ConfigApprovalInsertParam;
import io.github.pinpols.batch.console.domain.ops.param.ConfigApprovalTransitionParam;
import io.github.pinpols.batch.console.domain.ops.view.ConfigApprovalRow;
import io.github.pinpols.batch.console.domain.param.ConfigChangeLogInsertParam;
import io.github.pinpols.batch.console.domain.param.ConfigReleaseStatusUpdateParam;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.mapper.ConfigChangeLogMapper;
import io.github.pinpols.batch.console.mapper.ConfigReleaseMapper;
import io.github.pinpols.batch.console.support.ConfigChangeLogBuilder;
import io.github.pinpols.batch.console.support.web.ConsoleMapSupport;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ConfigRelease 的审批流入口（submit / approve / reject），独立于 JOB/FILE 审批链路。
 *
 * <p>状态机驱动：
 *
 * <ul>
 *   <li>{@link #submit}：ConfigRelease 必须是 {@code DRAFT}；同一 release 不允许存在 PENDING approval
 *       （防重复提交）。成功后 release 推进到 {@code PENDING_APPROVAL}。
 *   <li>{@link #approve}：ApprovalEntity 必须 PENDING；mapper CAS 返回 0 行视为并发已处理，抛 {@code CONFLICT}
 *       （防双审批）。release 推进到 {@code PUBLISHED}，打 {@code publishedAt}。
 *   <li>{@link #reject}：类似 approve 的 CAS 保护，但 release 回退到 {@code DRAFT}（可重新编辑再提交）。
 * </ul>
 *
 * <p>全程写 {@code config_change_log}（SUBMIT_APPROVAL / APPROVE / REJECT 三种 action）， 带 operator /
 * reason / detail JSON，提供完整审计轨迹。
 *
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleConfigApprovalApplicationService
    implements ConsoleConfigApprovalApplicationService {

  private static final String PENDING_APPROVAL = ConfigLifecycleStatus.PENDING_APPROVAL.code();

  // ── 重复使用的字段名常量 ───────────────────────────────────────────────
  private static final String STATUS_PENDING = "PENDING";
  private static final String KEY_RELEASE_ID = "releaseId";
  private static final String KEY_NEXT_STATUS = "nextStatus";

  private final ConsoleTenantGuard tenantGuard;
  private final ConfigReleaseMapper configReleaseMapper;
  private final ConfigApprovalMapper configApprovalMapper;
  private final ConfigChangeLogMapper configChangeLogMapper;
  private final ConfigReleaseApplyService configReleaseApplyService;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;

  @Override
  @Transactional
  public ConsoleConfigApprovalDetailResponse submit(
      Long releaseId, ConfigReleaseApprovalSubmitRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    ConfigReleaseEntity release = loadRelease(tenantId, releaseId);
    acquireReleaseLock(release);
    release = loadRelease(tenantId, releaseId);
    validateLatestVersion(tenantId, release);
    if (!ConfigLifecycleStatus.DRAFT.code().equals(release.getConfigStatus())) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.config_release.only_draft_can_submit");
    }
    configReleaseApplyService.validate(
        release.getConfigType(), release.getConfigKey(), release.getConfigPayload());
    ConfigApprovalRow latest = configApprovalMapper.selectLatestByRelease(tenantId, releaseId);
    if (EmptyChecks.isNotNull(latest) && STATUS_PENDING.equals(latest.approvalStatus())) {
      throw BizException.of(ResultCode.CONFLICT, "error.config_approval.already_pending");
    }
    String submittedBy = currentOperator();
    String reviewComment = ConsoleTextSanitizer.safeInput(request.getReason(), 1024);
    ConfigApprovalInsertParam insertParam =
        new ConfigApprovalInsertParam(tenantId, releaseId, submittedBy, reviewComment, null);
    configApprovalMapper.insert(insertParam);
    transitionRelease(tenantId, release, PENDING_APPROVAL, null, currentOperator());
    logChange(
        tenantId,
        release,
        "SUBMIT_APPROVAL",
        currentOperator(),
        request.getReason(),
        Map.of(
            KEY_RELEASE_ID, releaseId,
            KEY_NEXT_STATUS, PENDING_APPROVAL));
    return detail(tenantId, releaseId);
  }

  @Override
  public ConsoleConfigApprovalDetailResponse detail(String tenantId, Long releaseId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    ConfigReleaseEntity release = loadRelease(resolved, releaseId);
    ConfigApprovalRow approval = configApprovalMapper.selectLatestByRelease(resolved, releaseId);
    ConsoleConfigApprovalDetailResponse.ApprovalRow responseApproval = EmptyChecks.isNull(approval)
        ? null
        : ConsoleConfigApprovalDetailResponse.ApprovalRow.builder()
            .id(approval.id())
            .tenantId(approval.tenantId())
            .releaseId(approval.releaseId())
            .approvalStatus(approval.approvalStatus())
            .requestedBy(approval.requestedBy())
            .requestedAt(approval.requestedAt())
            .reviewedBy(approval.reviewedBy())
            .reviewedAt(approval.reviewedAt())
            .reviewComment(approval.reviewComment())
            .expiredAt(approval.expiredAt())
            .createdAt(approval.createdAt())
            .updatedAt(approval.updatedAt())
            .build();
    return new ConsoleConfigApprovalDetailResponse(
        release.getId(),
        release.getTenantId(),
        release.getConfigType(),
        release.getConfigKey(),
        release.getConfigStatus(),
        responseApproval);
  }

  @Override
  @Transactional
  public ConsoleConfigApprovalDetailResponse approve(
      Long approvalId, ConfigApprovalActionRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    ConfigApprovalRow approval = requireApproval(tenantId, approvalId);
    if (!STATUS_PENDING.equals(approval.approvalStatus())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.config_approval.not_pending");
    }
    String operatorForSelfApprovalCheck = currentOperator();
    String requestedBy = approval.requestedBy();
    if (operatorForSelfApprovalCheck.equals(requestedBy)) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.config_approval.self_approval_forbidden");
    }
    Long releaseId = approval.releaseId();
    ConfigReleaseEntity release = loadRelease(tenantId, releaseId);
    acquireReleaseLock(release);
    release = loadRelease(tenantId, releaseId);
    validateLatestVersion(tenantId, release);
    if (!PENDING_APPROVAL.equals(release.getConfigStatus())) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.config_approval.release_not_pending");
    }
    String reviewedBy = currentOperator();
    String reviewComment = ConsoleTextSanitizer.safeInput(request.getReason(), 1024);
    ConfigApprovalTransitionParam transitionParam =
        ConfigApprovalTransitionParam.approved(tenantId, approvalId, reviewedBy, reviewComment);
    int rows = configApprovalMapper.transition(transitionParam);
    if (rows == 0) {
      throw BizException.of(ResultCode.CONFLICT, "error.config_approval.already_processed");
    }
    configReleaseApplyService.apply(release, currentOperator(), "config-approval-" + approvalId);
    transitionRelease(
        tenantId,
        release,
        ConfigLifecycleStatus.PUBLISHED.code(),
        BatchDateTimeSupport.utcNow(),
        currentOperator());
    logChange(
        tenantId,
        release,
        "APPROVE",
        currentOperator(),
        request.getReason(),
        Map.of("approvalId", approvalId, KEY_NEXT_STATUS, ConfigLifecycleStatus.PUBLISHED.code()));
    return detail(tenantId, releaseId);
  }

  @Override
  @Transactional
  public ConsoleConfigApprovalDetailResponse reject(
      Long approvalId, ConfigApprovalActionRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    ConfigApprovalRow approval = requireApproval(tenantId, approvalId);
    if (!STATUS_PENDING.equals(approval.approvalStatus())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.config_approval.not_pending");
    }
    Long releaseId = approval.releaseId();
    ConfigReleaseEntity release = loadRelease(tenantId, releaseId);
    acquireReleaseLock(release);
    release = loadRelease(tenantId, releaseId);
    validateLatestVersion(tenantId, release);
    String reviewedBy = currentOperator();
    String reviewComment = ConsoleTextSanitizer.safeInput(request.getReason(), 1024);
    ConfigApprovalTransitionParam transitionParam =
        ConfigApprovalTransitionParam.rejected(tenantId, approvalId, reviewedBy, reviewComment);
    int rows = configApprovalMapper.transition(transitionParam);
    if (rows == 0) {
      throw BizException.of(ResultCode.CONFLICT, "error.config_approval.already_processed");
    }
    transitionRelease(
        tenantId, release, ConfigLifecycleStatus.DRAFT.code(), null, currentOperator());
    logChange(
        tenantId,
        release,
        "REJECT",
        currentOperator(),
        request.getReason(),
        Map.of("approvalId", approvalId, KEY_NEXT_STATUS, ConfigLifecycleStatus.DRAFT.code()));
    return detail(tenantId, releaseId);
  }

  private ConfigApprovalRow requireApproval(String tenantId, Long approvalId) {
    ConfigApprovalRow approval = configApprovalMapper.selectById(tenantId, approvalId);
    return Guard.requireFound(approval, "config approval not found");
  }

  private ConfigReleaseEntity loadRelease(String tenantId, Long releaseId) {

    ConfigReleaseEntity release = configReleaseMapper.selectById(tenantId, releaseId);
    return Guard.requireFound(release, "config release not found");
  }

  private void acquireReleaseLock(ConfigReleaseEntity release) {
    String tenantId = release.getTenantId();
    String configType = release.getConfigType();
    String canonicalConfigType = configReleaseApplyService.canonicalType(configType);
    String configKey = release.getConfigKey();

    configReleaseMapper.acquireVersionLock(tenantId, canonicalConfigType, configKey);
  }

  private void logChange(
      String tenantId,
      ConfigReleaseEntity release,
      String action,
      String operatorId,
      String reason,
      Map<String, Object> detail) {
    String traceId = currentTraceId();
    ConfigChangeLogBuilder changeLogBuilder = ConfigChangeLogBuilder.create(
            tenantId, operatorId, traceId)
        .forType(release.getConfigType())
        .withKey(release.getConfigKey())
        .versionNo(release.getVersionNo())
        .action(action)
        .operatorType(AuditLogConstants.OPERATOR_TYPE_API);
    String safeReason = ConsoleTextSanitizer.safeInput(reason, 512);
    Map<String, Object> changeSummary =
        ConsoleMapSupport.mapOf("reason", safeReason, "detail", detail);
    String summaryJson = JsonUtils.toJson(changeSummary);
    ConfigChangeLogInsertParam changeLog = changeLogBuilder.summary(summaryJson).build();
    configChangeLogMapper.insertConfigChangeLog(changeLog);
  }

  private String currentOperator() {
    ConsoleRequestMetadata metadata = requestMetadataResolver.current();
    String operatorId = Texts.hasText(metadata.operatorId()) ? metadata.operatorId() : "system";
    return ConsoleTextSanitizer.safeInput(operatorId, 64);
  }

  private String currentTraceId() {
    ConsoleRequestMetadata metadata = requestMetadataResolver.current();
    String traceId = metadata.traceId();
    return ConsoleTextSanitizer.safeInput(traceId, 128);
  }

  private void validateLatestVersion(String tenantId, ConfigReleaseEntity release) {

    Integer latestVersionNo = configReleaseMapper.selectLatestVersionNo(
        tenantId, release.getConfigType(), release.getConfigKey());
    if (!Objects.equals(latestVersionNo, release.getVersionNo())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.config.release_not_latest",
          release.getVersionNo(),
          latestVersionNo);
    }
  }

  private void transitionRelease(
      String tenantId,
      ConfigReleaseEntity release,
      String nextStatus,
      Instant publishedAt,
      String operatorId) {
    ConfigReleaseStatusUpdateParam releaseStatusUpdate = ConfigReleaseStatusUpdateParam.builder()
        .tenantId(tenantId)
        .releaseId(release.getId())
        .nextStatus(nextStatus)
        .expectedStatus(release.getConfigStatus())
        .expectedVersionNo(release.getVersionNo())
        .publishedAt(publishedAt)
        .rolledBackAt(null)
        .updatedBy(ConsoleTextSanitizer.safeInput(operatorId, 64))
        .build();
    int rows = configReleaseMapper.updateConfigReleaseStatus(releaseStatusUpdate);
    if (rows != 1) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.config.release_concurrent_change");
    }
  }
}
