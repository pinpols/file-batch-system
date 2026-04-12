package com.example.batch.console.service;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.domain.entity.ArchivePolicyEntity;
import com.example.batch.console.repository.ConsoleArchivePolicyRepository;
import com.example.batch.console.support.ConsoleTenantGuard;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConsoleArchivePolicyService {

  private static final Set<String> VALID_TABLES =
      Set.of(
          "job_instance",
          "workflow_run",
          "job_partition",
          "file_record",
          "audit_log",
          "outbox_event",
          "event_delivery_log",
          "webhook_delivery_log");

  private final ConsoleArchivePolicyRepository repository;
  private final ConsoleTenantGuard tenantGuard;

  public List<ArchivePolicyEntity> list(String tenantId) {
    return repository.findAllByTenant(tenantGuard.resolveTenant(tenantId));
  }

  @Transactional
  public void upsert(
      String tenantId,
      String targetTable,
      int retentionDays,
      boolean archiveEnabled,
      boolean cleanupEnabled,
      int batchSize,
      String description,
      String operator) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    String normalized = targetTable.toLowerCase(Locale.ROOT);
    if (!VALID_TABLES.contains(normalized)) {
      throw new BizException(
          ResultCode.INVALID_ARGUMENT, "target_table must be one of: " + VALID_TABLES);
    }
    if (retentionDays < 1) {
      throw new BizException(ResultCode.INVALID_ARGUMENT, "retention_days must be >= 1");
    }
    repository.upsert(
        resolved,
        normalized,
        retentionDays,
        archiveEnabled,
        cleanupEnabled,
        Math.max(batchSize, 100),
        description,
        operator);
  }
}
