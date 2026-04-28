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

  @Test
  void shouldUpsertValidTable() {
    service.upsert(
        new ArchivePolicyUpsertParam("t1", "job_instance", 30, true, false, 500, "desc", "admin"));

    verify(repository)
        .upsert(
            new ArchivePolicyUpsertParam(
                "t1", "job_instance", 30, true, false, 500, "desc", "admin"));
  }

  @Test
  void shouldRejectInvalidTable() {
    assertThatThrownBy(
            () ->
                service.upsert(
                    new ArchivePolicyUpsertParam(
                        "t1", "unknown_table", 30, true, false, 500, "desc", "admin")))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("target_table must be one of");
  }

  @Test
  void shouldRejectRetentionDaysLessThan1() {
    assertThatThrownBy(
            () ->
                service.upsert(
                    new ArchivePolicyUpsertParam(
                        "t1", "job_instance", 0, true, false, 500, "desc", "admin")))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("retention_days_min");
  }

  @Test
  void shouldNormalizeTableToLowercase() {
    service.upsert(
        new ArchivePolicyUpsertParam("t1", "JOB_INSTANCE", 30, true, false, 500, "desc", "admin"));

    verify(repository)
        .upsert(
            new ArchivePolicyUpsertParam(
                "t1", "job_instance", 30, true, false, 500, "desc", "admin"));
  }

  @Test
  void shouldEnforceBatchSizeMinimum() {
    service.upsert(
        new ArchivePolicyUpsertParam("t1", "job_instance", 30, true, false, 50, "desc", "admin"));

    verify(repository)
        .upsert(
            new ArchivePolicyUpsertParam(
                "t1", "job_instance", 30, true, false, 100, "desc", "admin"));
  }
}
