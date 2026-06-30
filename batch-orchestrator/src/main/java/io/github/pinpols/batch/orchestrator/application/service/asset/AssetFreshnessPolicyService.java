package io.github.pinpols.batch.orchestrator.application.service.asset;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.service.governance.AlertEventService;
import io.github.pinpols.batch.orchestrator.controller.request.AlertEmitRequest;
import io.github.pinpols.batch.orchestrator.domain.entity.AssetFreshnessPolicyRecord;
import io.github.pinpols.batch.orchestrator.mapper.AssetFreshnessPolicyMapper;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** JOB asset freshness SLA 扫描服务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetFreshnessPolicyService {

  static final String ALERT_TYPE_MISSING = "ASSET_FRESHNESS_MISSING";
  static final String ALERT_TYPE_STALE = "ASSET_FRESHNESS_STALE";
  private static final String DEFAULT_TIMEZONE = "UTC";
  private static final String DEFAULT_SEVERITY = "WARN";

  private final AssetFreshnessPolicyMapper policyMapper;
  private final AssetPartitionService assetPartitionService;
  private final AlertEventService alertEventService;
  private final BatchDateTimeSupport dateTimeSupport;

  public int scanDuePolicies(int policyLimit) {
    if (policyLimit <= 0) {
      return 0;
    }
    List<AssetFreshnessPolicyRecord> policies = policyMapper.selectEnabledPolicies(policyLimit);
    if (policies == null || policies.isEmpty()) {
      return 0;
    }
    Instant now = dateTimeSupport.nowInstant();
    int emitted = 0;
    for (AssetFreshnessPolicyRecord policy : policies) {
      emitted += scanPolicy(policy, now);
    }
    return emitted;
  }

  int scanPolicy(AssetFreshnessPolicyRecord policy, Instant now) {
    if (!validPolicy(policy)) {
      return 0;
    }
    ZoneId zone = resolveZone(policy.timezone());
    LocalDate today = ZonedDateTime.ofInstant(now, zone).toLocalDate();
    int emitted = 0;
    for (int offset = 0; offset < safeLookbackDays(policy); offset++) {
      LocalDate bizDate = today.minusDays(offset);
      FreshnessBreach breach = evaluate(policy, bizDate, now, zone);
      if (breach == null) {
        continue;
      }
      emitBreach(policy, breach);
      emitted++;
    }
    return emitted;
  }

  FreshnessBreach evaluate(
      AssetFreshnessPolicyRecord policy, LocalDate bizDate, Instant now, ZoneId zone) {
    Instant expectedAt = ZonedDateTime.of(bizDate, policy.expectedByLocalTime(), zone).toInstant();
    if (now.isBefore(expectedAt)) {
      return null;
    }
    boolean ready =
        RlsTenantContextHolder.runWithTenant(
            policy.tenantId(),
            () ->
                assetPartitionService.isJobPartitionReady(
                    policy.tenantId(), policy.assetCode(), bizDate));
    if (ready) {
      return null;
    }
    int staleAfterSeconds =
        policy.staleAfterSeconds() == null ? 0 : Math.max(0, policy.staleAfterSeconds());
    Instant staleAt = expectedAt.plusSeconds(staleAfterSeconds);
    String breachType = now.isBefore(staleAt) ? "MISSING" : "STALE";
    String alertType = "STALE".equals(breachType) ? ALERT_TYPE_STALE : ALERT_TYPE_MISSING;
    String severity =
        "STALE".equals(breachType)
            ? escalateSeverity(policy.severity())
            : safeSeverity(policy.severity());
    return new FreshnessBreach(bizDate, expectedAt, staleAt, breachType, alertType, severity);
  }

  private void emitBreach(AssetFreshnessPolicyRecord policy, FreshnessBreach breach) {
    String resourceKey =
        policy.tenantId() + ":" + policy.assetCode() + ":" + breach.bizDate().toString();
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("tenantId", policy.tenantId());
    detail.put("assetCode", policy.assetCode());
    detail.put("assetType", policy.assetType());
    detail.put("bizDate", breach.bizDate().toString());
    detail.put("expectedAt", breach.expectedAt().toString());
    detail.put("staleAt", breach.staleAt().toString());
    detail.put("breachType", breach.breachType());
    detail.put("policyId", policy.id());
    alertEventService.emit(
        AlertEmitRequest.builder()
            .tenantId(policy.tenantId())
            .serviceName("batch-orchestrator")
            .alertType(breach.alertType())
            .severity(breach.severity())
            .title(
                "Asset freshness "
                    + breach.breachType().toLowerCase()
                    + ": "
                    + policy.assetCode()
                    + " "
                    + breach.bizDate())
            .detailJson(JsonUtils.toJson(detail))
            .resourceKey(resourceKey)
            .build());
  }

  private boolean validPolicy(AssetFreshnessPolicyRecord policy) {
    return policy != null
        && Boolean.TRUE.equals(policy.enabled())
        && Texts.hasText(policy.tenantId())
        && Texts.hasText(policy.assetCode())
        && "JOB".equals(policy.assetType())
        && policy.expectedByLocalTime() != null;
  }

  private int safeLookbackDays(AssetFreshnessPolicyRecord policy) {
    Integer value = policy.lookbackDays();
    if (value == null) {
      return 1;
    }
    return Math.min(31, Math.max(1, value));
  }

  private ZoneId resolveZone(String timezone) {
    if (!Texts.hasText(timezone)) {
      return ZoneId.of(DEFAULT_TIMEZONE);
    }
    try {
      return ZoneId.of(timezone);
    } catch (DateTimeException ex) {
      log.warn("Invalid asset freshness timezone {}, fallback to UTC", timezone);
      return ZoneId.of(DEFAULT_TIMEZONE);
    }
  }

  private String safeSeverity(String severity) {
    return Texts.hasText(severity) ? severity : DEFAULT_SEVERITY;
  }

  private String escalateSeverity(String severity) {
    String value = safeSeverity(severity);
    if ("CRITICAL".equals(value) || "ERROR".equals(value)) {
      return value;
    }
    return "ERROR";
  }

  record FreshnessBreach(
      LocalDate bizDate,
      Instant expectedAt,
      Instant staleAt,
      String breachType,
      String alertType,
      String severity) {}
}
