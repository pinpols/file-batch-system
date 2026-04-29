package com.example.batch.console.service;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.domain.entity.ArchivePolicyEntity;
import com.example.batch.console.repository.ArchivePolicyUpsertParam;
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
  public void upsert(ArchivePolicyUpsertParam param) {
    String resolved = tenantGuard.resolveTenant(param.tenantId());
    String normalized = param.targetTable().toLowerCase(Locale.ROOT);
    if (!VALID_TABLES.contains(normalized)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "target_table must be one of: " + VALID_TABLES);
    }
    if (param.retentionDays() < 1) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.retention_days_min");
    }
    repository.upsert(
        new ArchivePolicyUpsertParam(
            resolved,
            normalized,
            param.retentionDays(),
            param.archiveEnabled(),
            param.cleanupEnabled(),
            Math.max(param.batchSize(), 100),
            param.description(),
            param.operator()));
  }
}
