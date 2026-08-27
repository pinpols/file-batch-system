package io.github.pinpols.batch.console.web.response.config;

import io.github.pinpols.batch.console.web.request.config.ConfigSyncBundlePayload;
import java.util.List;
import java.util.Map;

/** Read-only diff result for base-package and tenant-overlay operations. */
public record TenantConfigDiffPreviewResponse(
    String sourceTenantId,
    List<String> targetTenantIds,
    List<TenantDiffResult> tenants,
    Summary summary) {

  public record TenantDiffResult(
      String tenantId,
      int addCount,
      int updateCount,
      int unchangedCount,
      int deleteCandidateCount,
      List<ConfigDiffItem> items,
      List<ConfigImpactItem> impacts,
      ConfigSyncBundlePayload overlayBundle) {}

  public record ConfigDiffItem(
      String configType,
      String configKey,
      String action,
      String reason,
      Map<String, Object> source,
      Map<String, Object> target) {}

  public record ConfigImpactItem(String impactType, String ref, String message) {}

  public record Summary(
      int targetTenantCount,
      int addCount,
      int updateCount,
      int unchangedCount,
      int deleteCandidateCount) {}
}
