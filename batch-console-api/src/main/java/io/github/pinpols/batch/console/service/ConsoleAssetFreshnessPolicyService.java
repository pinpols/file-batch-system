package io.github.pinpols.batch.console.service;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.enums.AlertSeverity;
import io.github.pinpols.batch.common.enums.DictEnum;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.application.contract.response.AssetFreshnessPolicyResponse;
import io.github.pinpols.batch.console.domain.command.AssetFreshnessPolicyUpsertCommand;
import io.github.pinpols.batch.console.domain.entity.AssetFreshnessPolicyEntity;
import io.github.pinpols.batch.console.domain.param.AssetFreshnessPolicyUpsertParam;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.mapper.ConsoleAssetFreshnessPolicyMapper;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Asset freshness policy 的最小 Console 管理服务。 */
@Service
@RequiredArgsConstructor
public class ConsoleAssetFreshnessPolicyService {

  private static final String ASSET_TYPE_JOB = "JOB";
  private static final String DEFAULT_SEVERITY = AlertSeverity.WARN.code();
  private static final int DEFAULT_STALE_AFTER_SECONDS = 0;
  private static final int DEFAULT_LOOKBACK_DAYS = 1;
  private static final Set<String> VALID_SEVERITIES = DictEnum.codes(AlertSeverity.class);

  private final ConsoleAssetFreshnessPolicyMapper mapper;
  private final ConsoleTenantGuard tenantGuard;

  @Transactional(readOnly = true)
  public PageResponse<AssetFreshnessPolicyResponse> list(
      String tenantId, String assetCode, Boolean enabled, int pageNo, int pageSize) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    PageRequest page = new PageRequest(pageNo, pageSize);
    String normalizedAssetCode = Texts.hasText(assetCode) ? assetCode.trim() : null;
    long total = mapper.countByTenant(resolved, normalizedAssetCode, enabled);
    List<AssetFreshnessPolicyEntity> items =
        mapper.findByTenant(resolved, normalizedAssetCode, enabled, page);
    List<AssetFreshnessPolicyResponse> responses =
        items.stream().map(this::toResponse).toList();
    return new PageResponse<>(total, page.pageNo(), page.pageSize(), responses);
  }

  @Transactional(readOnly = true)
  public AssetFreshnessPolicyResponse get(String tenantId, Long id) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    AssetFreshnessPolicyEntity policy = mapper
        .findById(resolved, id)
        .orElseThrow(() -> BizException.of(ResultCode.NOT_FOUND, "error.common.not_found", id));
    return toResponse(policy);
  }

  private AssetFreshnessPolicyResponse toResponse(AssetFreshnessPolicyEntity entity) {
    return AssetFreshnessPolicyResponse.builder()
        .id(entity.id())
        .tenantId(entity.tenantId())
        .assetCode(entity.assetCode())
        .assetType(entity.assetType())
        .expectedByLocalTime(entity.expectedByLocalTime())
        .timezone(entity.timezone())
        .staleAfterSeconds(entity.staleAfterSeconds())
        .lookbackDays(entity.lookbackDays())
        .severity(entity.severity())
        .enabled(entity.enabled())
        .createdAt(entity.createdAt())
        .updatedAt(entity.updatedAt())
        .build();
  }

  @Transactional
  public void upsert(AssetFreshnessPolicyUpsertCommand param) {
    AssetFreshnessPolicyUpsertParam normalized = normalize(param);
    requireJobDefinition(normalized.tenantId(), normalized.assetCode());
    if (normalized.id() == null) {
      mapper.upsert(normalized);
      return;
    }
    int updated = mapper.updateById(normalized);
    if (updated == 0) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.common.not_found", normalized.id());
    }
  }

  private void requireJobDefinition(String tenantId, String jobCode) {
    Long jobDefinitionId = mapper.lockJobDefinitionForPolicyWrite(tenantId, jobCode);
    if (EmptyChecks.isNull(jobDefinitionId)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ResultCode.INVALID_ARGUMENT.detailKey(),
          "assetCode must reference a Job definition in this tenant");
    }
  }

  @Transactional
  public void setEnabled(String tenantId, Long id, boolean enabled) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    int updated = mapper.updateEnabled(resolved, id, enabled);
    if (updated == 0) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.common.not_found", id);
    }
  }

  private AssetFreshnessPolicyUpsertParam normalize(AssetFreshnessPolicyUpsertCommand param) {
    if (param == null || param.expectedByLocalTime() == null) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ResultCode.INVALID_ARGUMENT.detailKey(),
          "expectedByLocalTime is required");
    }
    String tenantId = tenantGuard.resolveTenant(param.tenantId());
    String assetCode = requireText(param.assetCode(), "assetCode is required");
    String assetType = Texts.hasText(param.assetType()) ? param.assetType().trim() : ASSET_TYPE_JOB;
    assetType = assetType.toUpperCase(Locale.ROOT);
    if (!ASSET_TYPE_JOB.equals(assetType)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ResultCode.INVALID_ARGUMENT.detailKey(),
          "assetType only supports JOB");
    }
    String timezone = Texts.hasText(param.timezone())
        ? param.timezone().trim()
        : CommonConstants.DEFAULT_TIMEZONE_ID;
    validateTimezone(timezone);
    int staleAfterSeconds =
        param.staleAfterSeconds() == null ? DEFAULT_STALE_AFTER_SECONDS : param.staleAfterSeconds();
    if (staleAfterSeconds < 0) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ResultCode.INVALID_ARGUMENT.detailKey(),
          "staleAfterSeconds must be >= 0");
    }
    int lookbackDays = param.lookbackDays() == null ? DEFAULT_LOOKBACK_DAYS : param.lookbackDays();
    if (lookbackDays < 1 || lookbackDays > 31) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ResultCode.INVALID_ARGUMENT.detailKey(),
          "lookbackDays must be between 1 and 31");
    }
    String severity = DEFAULT_SEVERITY;
    if (Texts.hasText(param.severity())) {
      String trimmedSeverity = param.severity().trim();
      severity = trimmedSeverity.toUpperCase(Locale.ROOT);
    }
    if (!VALID_SEVERITIES.contains(severity)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ResultCode.INVALID_ARGUMENT.detailKey(),
          "severity must be one of: " + VALID_SEVERITIES);
    }
    return AssetFreshnessPolicyUpsertParam.builder()
        .id(param.id())
        .tenantId(tenantId)
        .assetCode(assetCode)
        .assetType(assetType)
        .expectedByLocalTime(param.expectedByLocalTime())
        .timezone(timezone)
        .staleAfterSeconds(staleAfterSeconds)
        .lookbackDays(lookbackDays)
        .severity(severity)
        .enabled(param.enabled() == null ? Boolean.TRUE : param.enabled())
        .build();
  }

  private static String requireText(String value, String message) {
    if (!Texts.hasText(value)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, ResultCode.INVALID_ARGUMENT.detailKey(), message);
    }
    return value.trim();
  }

  private static void validateTimezone(String timezone) {
    try {
      ZoneId.of(timezone);
    } catch (DateTimeException ex) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ResultCode.INVALID_ARGUMENT.detailKey(),
          "timezone is invalid: " + timezone);
    }
  }
}
