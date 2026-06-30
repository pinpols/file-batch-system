package io.github.pinpols.batch.console.service;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.Texts;
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
  private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";
  private static final String DEFAULT_SEVERITY = "WARN";
  private static final int DEFAULT_STALE_AFTER_SECONDS = 0;
  private static final int DEFAULT_LOOKBACK_DAYS = 1;
  private static final int MAX_LIMIT = 500;
  private static final Set<String> VALID_SEVERITIES = Set.of("INFO", "WARN", "ERROR", "CRITICAL");

  private final ConsoleAssetFreshnessPolicyMapper mapper;
  private final ConsoleTenantGuard tenantGuard;

  @Transactional(readOnly = true)
  public List<AssetFreshnessPolicyEntity> list(
      String tenantId, String assetCode, Boolean enabled, Integer limit) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    int normalizedLimit = Math.min(MAX_LIMIT, Math.max(1, limit == null ? 100 : limit));
    String normalizedAssetCode = Texts.hasText(assetCode) ? assetCode.trim() : null;
    return mapper.findByTenant(resolved, normalizedAssetCode, enabled, normalizedLimit);
  }

  @Transactional(readOnly = true)
  public AssetFreshnessPolicyEntity get(String tenantId, Long id) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return mapper
        .findById(resolved, id)
        .orElseThrow(() -> BizException.of(ResultCode.NOT_FOUND, "error.common.not_found", id));
  }

  @Transactional
  public void upsert(AssetFreshnessPolicyUpsertParam param) {
    AssetFreshnessPolicyUpsertParam normalized = normalize(param);
    if (normalized.id() == null) {
      mapper.upsert(normalized);
      return;
    }
    int updated = mapper.updateById(normalized);
    if (updated == 0) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.common.not_found", normalized.id());
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

  private AssetFreshnessPolicyUpsertParam normalize(AssetFreshnessPolicyUpsertParam param) {
    if (param == null || param.expectedByLocalTime() == null) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "expectedByLocalTime is required");
    }
    String tenantId = tenantGuard.resolveTenant(param.tenantId());
    String assetCode = requireText(param.assetCode(), "assetCode is required");
    String assetType = Texts.hasText(param.assetType()) ? param.assetType().trim() : ASSET_TYPE_JOB;
    assetType = assetType.toUpperCase(Locale.ROOT);
    if (!ASSET_TYPE_JOB.equals(assetType)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "assetType only supports JOB");
    }
    String timezone = Texts.hasText(param.timezone()) ? param.timezone().trim() : DEFAULT_TIMEZONE;
    validateTimezone(timezone);
    int staleAfterSeconds =
        param.staleAfterSeconds() == null ? DEFAULT_STALE_AFTER_SECONDS : param.staleAfterSeconds();
    if (staleAfterSeconds < 0) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "staleAfterSeconds must be >= 0");
    }
    int lookbackDays = param.lookbackDays() == null ? DEFAULT_LOOKBACK_DAYS : param.lookbackDays();
    if (lookbackDays < 1 || lookbackDays > 31) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "lookbackDays must be between 1 and 31");
    }
    String severity =
        Texts.hasText(param.severity())
            ? param.severity().trim().toUpperCase(Locale.ROOT)
            : DEFAULT_SEVERITY;
    if (!VALID_SEVERITIES.contains(severity)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
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
          ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument_detail", message);
    }
    return value.trim();
  }

  private static void validateTimezone(String timezone) {
    try {
      ZoneId.of(timezone);
    } catch (DateTimeException ex) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "timezone is invalid: " + timezone);
    }
  }
}
