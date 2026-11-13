package io.github.pinpols.batch.console.domain.governance.service;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.governance.command.ArchivePolicyUpsertCommand;
import io.github.pinpols.batch.console.domain.governance.entity.ArchivePolicyEntity;
import io.github.pinpols.batch.console.domain.governance.mapper.ConsoleArchivePolicyMapper;
import io.github.pinpols.batch.console.domain.governance.param.ArchivePolicyUpsertParam;
import io.github.pinpols.batch.console.shared.query.TenantScopeResolver;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConsoleArchivePolicyService {

  /** 归档策略批大小下限，低于该值按 100 保存。 */
  private static final int MIN_BATCH_SIZE = 100;

  private static final Set<String> VALID_TABLES = Set.of(
      "job_instance",
      "workflow_run",
      "job_partition",
      "file_record",
      "audit_log",
      "outbox_event",
      "event_delivery_log",
      "webhook_delivery_log",
      "console_operation_audit");

  private final ConsoleArchivePolicyMapper repository;
  private final TenantScopeResolver tenantGuard;

  public List<ArchivePolicyEntity> list(String tenantId) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    return repository.findAllByTenant(resolvedTenantId);
  }

  @Transactional
  public void upsert(ArchivePolicyUpsertCommand param) {
    String resolved = tenantGuard.resolveTenant(param.tenantId());
    String normalized = param.targetTable().toLowerCase(Locale.ROOT);
    if (!VALID_TABLES.contains(normalized)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ResultCode.INVALID_ARGUMENT.detailKey(),
          "target_table must be one of: " + VALID_TABLES);
    }
    if (param.retentionDays() < 1) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.retention_days_min");
    }
    ArchivePolicyUpsertParam.ArchivePolicyUpsertParamBuilder upsertParamBuilder =
        ArchivePolicyUpsertParam.builder()
            .tenantId(resolved)
            .targetTable(normalized)
            .retentionDays(param.retentionDays())
            .archiveEnabled(param.archiveEnabled())
            .cleanupEnabled(param.cleanupEnabled());
    int effectiveBatchSize = Math.max(param.batchSize(), MIN_BATCH_SIZE);
    ArchivePolicyUpsertParam upsertParam = upsertParamBuilder
        .batchSize(effectiveBatchSize)
        .description(param.description())
        .operator(param.operator())
        .build();
    repository.upsert(upsertParam);
  }
}
