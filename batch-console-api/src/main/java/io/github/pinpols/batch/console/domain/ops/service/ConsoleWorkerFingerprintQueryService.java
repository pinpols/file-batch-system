package io.github.pinpols.batch.console.domain.ops.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.WorkerFingerprintResponse;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.WorkerFingerprintSummaryResponse;
import io.github.pinpols.batch.console.domain.ops.dto.WorkerCompatibility;
import io.github.pinpols.batch.console.domain.ops.mapper.WorkerFingerprintMapper;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.support.cache.ConsoleQueryCacheService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConsoleWorkerFingerprintQueryService {

  private final WorkerFingerprintMapper mapper;
  private final TenantIdResolver tenantGuard;
  private final ConsoleQueryCacheService cacheService;
  private final WorkerCompatibilityEvaluator compatibilityEvaluator;

  public List<WorkerFingerprintResponse> list(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return cacheService.getOrLoad(
        "workers:" + ConsoleQueryCacheService.keySegment(resolved) + ":fingerprints",
        ConsoleQueryCacheService.DIAGNOSTIC_TTL,
        new TypeReference<List<WorkerFingerprintResponse>>() {},
        () -> loadFingerprints(resolved));
  }

  private List<WorkerFingerprintResponse> loadFingerprints(String resolvedTenantId) {
    return mapper.selectFingerprintsByTenant(resolvedTenantId).stream()
        .map(row -> {
          String reportedSdkVersion = row.getSdkVersion();
          WorkerCompatibility compatibility = compatibilityEvaluator.evaluate(reportedSdkVersion);
          return WorkerFingerprintResponse.from(row, compatibility);
        })
        .toList();
  }

  public List<WorkerFingerprintSummaryResponse> summary(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return cacheService.getOrLoad(
        "workers:" + ConsoleQueryCacheService.keySegment(resolved) + ":fingerprints-summary",
        ConsoleQueryCacheService.DIAGNOSTIC_TTL,
        new TypeReference<List<WorkerFingerprintSummaryResponse>>() {},
        () -> mapper.selectFingerprintSummaryByTenant(resolved).stream()
            .map(WorkerFingerprintSummaryResponse::from)
            .toList());
  }
}
