package com.example.batch.console.infrastructure.config;

import com.example.batch.common.enums.ConfigLifecycleStatus;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.console.application.config.ConsoleConfigApprovalApplicationService;
import com.example.batch.console.domain.entity.ConfigReleaseEntity;
import com.example.batch.console.domain.ops.mapper.ConfigApprovalMapper;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.ConfigReleaseMapper;
import com.example.batch.console.support.ConfigChangeLogBuilder;
import com.example.batch.console.web.request.config.ConfigApprovalActionRequest;
import com.example.batch.console.web.request.config.ConfigReleaseApprovalSubmitRequest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * <p>submit 接收可选 {@code expiredAt}（ISO-8601 Instant），用于后续超期自动回滚的定时器钩子。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleConfigApprovalApplicationService
    implements ConsoleConfigApprovalApplicationService {

  private static final String PENDING_APPROVAL = "PENDING_APPROVAL";

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String STATUS_PENDING = "PENDING";
  private static final String KEY_APPROVAL_STATUS = "approvalStatus";
  private static final String KEY_TENANT_ID = "tenantId";
  private static final String KEY_RELEASE_ID = "releaseId";
  private static final String KEY_NEXT_STATUS = "nextStatus";

  private final ConsoleTenantGuard tenantGuard;
  private final ConfigReleaseMapper configReleaseMapper;
  private final ConfigApprovalMapper configApprovalMapper;
  private final ConfigChangeLogMapper configChangeLogMapper;

  @Override
  @Transactional
  public Map<String, Object> submit(Long releaseId, ConfigReleaseApprovalSubmitRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    ConfigReleaseEntity release = loadRelease(tenantId, releaseId);
    if (!ConfigLifecycleStatus.DRAFT.code().equals(release.getConfigStatus())) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.config_release.only_draft_can_submit");
    }
    Map<String, Object> latest = configApprovalMapper.selectLatestByRelease(tenantId, releaseId);
    if (latest != null && STATUS_PENDING.equals(String.valueOf(latest.get(KEY_APPROVAL_STATUS)))) {
      throw BizException.of(ResultCode.CONFLICT, "error.config_approval.already_pending");
    }
    configApprovalMapper.insert(
        mapOf(
            KEY_TENANT_ID,
            tenantId,
            KEY_RELEASE_ID,
            releaseId,
            KEY_APPROVAL_STATUS,
            STATUS_PENDING,
            "requestedBy",
            ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64),
            "reviewComment",
            ConsoleTextSanitizer.safeInput(request.getReason(), 1024),
            "expiredAt",
            parseInstant(request.getExpiredAt())));
    configReleaseMapper.updateConfigReleaseStatus(
        mapOf(
            KEY_TENANT_ID,
            tenantId,
            KEY_RELEASE_ID,
            releaseId,
            KEY_NEXT_STATUS,
            PENDING_APPROVAL,
            "publishedAt",
            null,
            "rolledBackAt",
            null,
            "updatedBy",
            ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64)));
    logChange(
        tenantId,
        release,
        "SUBMIT_APPROVAL",
        request.getOperatorId(),
        request.getReason(),
        Map.of(
            KEY_RELEASE_ID, releaseId,
            KEY_NEXT_STATUS, PENDING_APPROVAL));
    return detail(tenantId, releaseId);
  }

  @Override
  public Map<String, Object> detail(String tenantId, Long releaseId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    ConfigReleaseEntity release = loadRelease(resolved, releaseId);
    Map<String, Object> approval = configApprovalMapper.selectLatestByRelease(resolved, releaseId);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put(KEY_RELEASE_ID, release.getId());
    result.put(KEY_TENANT_ID, release.getTenantId());
    result.put("configType", release.getConfigType());
    result.put("configKey", release.getConfigKey());
    result.put("configStatus", release.getConfigStatus());
    result.put("approval", approval);
    return result;
  }

  @Override
  @Transactional
  public Map<String, Object> approve(Long approvalId, ConfigApprovalActionRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    Map<String, Object> approval = requireApproval(tenantId, approvalId);
    if (!STATUS_PENDING.equals(String.valueOf(approval.get(KEY_APPROVAL_STATUS)))) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.config_approval.not_pending");
    }
    Long releaseId = longValue(approval.get(KEY_RELEASE_ID));
    ConfigReleaseEntity release = loadRelease(tenantId, releaseId);
    int rows =
        configApprovalMapper.approve(
            mapOf(
                KEY_TENANT_ID,
                tenantId,
                "id",
                approvalId,
                "reviewedBy",
                ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64),
                "reviewComment",
                ConsoleTextSanitizer.safeInput(request.getReason(), 1024)));
    if (rows == 0) {
      throw BizException.of(ResultCode.CONFLICT, "error.config_approval.already_processed");
    }
    configReleaseMapper.updateConfigReleaseStatus(
        mapOf(
            KEY_TENANT_ID,
            tenantId,
            KEY_RELEASE_ID,
            releaseId,
            KEY_NEXT_STATUS,
            ConfigLifecycleStatus.PUBLISHED.code(),
            "publishedAt",
            BatchDateTimeSupport.utcNow(),
            "rolledBackAt",
            null,
            "updatedBy",
            ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64)));
    logChange(
        tenantId,
        release,
        "APPROVE",
        request.getOperatorId(),
        request.getReason(),
        Map.of("approvalId", approvalId, KEY_NEXT_STATUS, ConfigLifecycleStatus.PUBLISHED.code()));
    return detail(tenantId, releaseId);
  }

  @Override
  @Transactional
  public Map<String, Object> reject(Long approvalId, ConfigApprovalActionRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    Map<String, Object> approval = requireApproval(tenantId, approvalId);
    if (!STATUS_PENDING.equals(String.valueOf(approval.get(KEY_APPROVAL_STATUS)))) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.config_approval.not_pending");
    }
    Long releaseId = longValue(approval.get(KEY_RELEASE_ID));
    ConfigReleaseEntity release = loadRelease(tenantId, releaseId);
    int rows =
        configApprovalMapper.reject(
            mapOf(
                KEY_TENANT_ID,
                tenantId,
                "id",
                approvalId,
                "reviewedBy",
                ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64),
                "reviewComment",
                ConsoleTextSanitizer.safeInput(request.getReason(), 1024)));
    if (rows == 0) {
      throw BizException.of(ResultCode.CONFLICT, "error.config_approval.already_processed");
    }
    configReleaseMapper.updateConfigReleaseStatus(
        mapOf(
            KEY_TENANT_ID,
            tenantId,
            KEY_RELEASE_ID,
            releaseId,
            KEY_NEXT_STATUS,
            ConfigLifecycleStatus.DRAFT.code(),
            "publishedAt",
            null,
            "rolledBackAt",
            null,
            "updatedBy",
            ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64)));
    logChange(
        tenantId,
        release,
        "REJECT",
        request.getOperatorId(),
        request.getReason(),
        Map.of("approvalId", approvalId, KEY_NEXT_STATUS, ConfigLifecycleStatus.DRAFT.code()));
    return detail(tenantId, releaseId);
  }

  private Map<String, Object> requireApproval(String tenantId, Long approvalId) {
    return Guard.requireFound(
        configApprovalMapper.selectById(tenantId, approvalId), "config approval not found");
  }

  private ConfigReleaseEntity loadRelease(String tenantId, Long releaseId) {
    return Guard.requireFound(
        configReleaseMapper.selectById(mapOf(KEY_TENANT_ID, tenantId, KEY_RELEASE_ID, releaseId)),
        "config release not found");
  }

  private void logChange(
      String tenantId,
      ConfigReleaseEntity release,
      String action,
      String operatorId,
      String reason,
      Map<String, Object> detail) {
    configChangeLogMapper.insertConfigChangeLog(
        ConfigChangeLogBuilder.create(tenantId, operatorId, null)
            .forType(release.getConfigType())
            .withKey(release.getConfigKey())
            .versionNo(release.getVersionNo())
            .action(action)
            .operatorType("API")
            .summary(
                JsonUtils.toJson(
                    mapOf("reason", ConsoleTextSanitizer.safeInput(reason, 512), "detail", detail)))
            .build());
  }

  private Instant parseInstant(String text) {
    if (!Texts.hasText(text)) {
      return null;
    }
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException ex) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.expired_at_format");
    }
  }

  private Long longValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    return value == null ? null : Long.valueOf(String.valueOf(value));
  }

  private Map<String, Object> mapOf(Object... pairs) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      result.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    return result;
  }
}
