package com.example.batch.console.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.exception.BizException;
import com.example.batch.console.domain.entity.ArchivePolicyEntity;
import com.example.batch.console.repository.ArchivePolicyUpsertParam;
import com.example.batch.console.repository.ConsoleArchivePolicyRepository;
import com.example.batch.console.support.ConsoleTenantGuard;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConsoleArchivePolicyServiceTest {

  private ConsoleArchivePolicyRepository repository;
  private ConsoleTenantGuard tenantGuard;
  private ConsoleArchivePolicyService service;

  @BeforeEach
  void setUp() {
    repository = mock(ConsoleArchivePolicyRepository.class);
    tenantGuard = mock(ConsoleTenantGuard.class);
    service = new ConsoleArchivePolicyService(repository, tenantGuard);
    when(tenantGuard.resolveTenant("t1")).thenReturn("t1");
  }

  @Test
  void shouldListPolicies() {
    ArchivePolicyEntity entity = new ArchivePolicyEntity();
    when(repository.findAllByTenant("t1")).thenReturn(List.of(entity));

    List<ArchivePolicyEntity> result = service.list("t1");

    assertThat(result).hasSize(1);
  }

  private static ArchivePolicyUpsertParam paramOf(String table, int batchSize) {
    return ArchivePolicyUpsertParam.builder()
        .tenantId("t1")
        .targetTable(table)
        .retentionDays(30)
        .archiveEnabled(true)
        .cleanupEnabled(false)
        .batchSize(batchSize)
        .description("desc")
        .operator("admin")
        .build();
  }

  private static ArchivePolicyUpsertParam paramOfRetention(String table, int retentionDays) {
    return ArchivePolicyUpsertParam.builder()
        .tenantId("t1")
        .targetTable(table)
        .retentionDays(retentionDays)
        .archiveEnabled(true)
        .cleanupEnabled(false)
        .batchSize(500)
        .description("desc")
        .operator("admin")
        .build();
  }

  @Test
  void shouldUpsertValidTable() {
    ArchivePolicyUpsertParam input = paramOf("job_instance", 500);
    service.upsert(input);

    ArchivePolicyUpsertParam expected = paramOf("job_instance", 500);
    verify(repository).upsert(expected);
  }

  @Test
  void shouldRejectInvalidTable() {
    ArchivePolicyUpsertParam invalid = paramOf("unknown_table", 500);
    assertThatThrownBy(() -> service.upsert(invalid))
        .isInstanceOf(BizException.class)
        // i18n: BizException.getMessage() 返回 messageKey,改用 messageArgs 检查渲染前的 args 文本
        .satisfies(
            ex ->
                assertThat(((BizException) ex).getMessageArgs())
                    .anyMatch(
                        a -> a != null && a.toString().contains("target_table must be one of")));
  }

  @Test
  void shouldRejectRetentionDaysLessThan1() {
    ArchivePolicyUpsertParam invalid = paramOfRetention("job_instance", 0);
    assertThatThrownBy(() -> service.upsert(invalid))
        .isInstanceOf(BizException.class)
        // 此 case key 本身含 retention_days_min(无 args),检查 messageKey
        .satisfies(
            ex -> assertThat(((BizException) ex).getMessageKey()).contains("retention_days_min"));
  }

  @Test
  void shouldNormalizeTableToLowercase() {
    ArchivePolicyUpsertParam input = paramOf("JOB_INSTANCE", 500);
    service.upsert(input);

    ArchivePolicyUpsertParam expected = paramOf("job_instance", 500);
    verify(repository).upsert(expected);
  }

  @Test
  void shouldEnforceBatchSizeMinimum() {
    ArchivePolicyUpsertParam input = paramOf("job_instance", 50);
    service.upsert(input);

    ArchivePolicyUpsertParam expected = paramOf("job_instance", 100);
    verify(repository).upsert(expected);
  }
}
