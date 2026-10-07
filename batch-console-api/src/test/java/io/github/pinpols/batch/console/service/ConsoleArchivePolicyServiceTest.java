package io.github.pinpols.batch.console.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.entity.ArchivePolicyEntity;
import io.github.pinpols.batch.console.domain.param.ArchivePolicyUpsertParam;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.mapper.ConsoleArchivePolicyMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("归档策略服务: 列表查询、目标表白名单校验与保留天数、批量大小归一化")
class ConsoleArchivePolicyServiceTest {

  private ConsoleArchivePolicyMapper repository;
  private ConsoleTenantGuard tenantGuard;
  private ConsoleArchivePolicyService service;

  @BeforeEach
  void setUp() {
    repository = mock(ConsoleArchivePolicyMapper.class);
    tenantGuard = mock(ConsoleTenantGuard.class);
    service = new ConsoleArchivePolicyService(repository, tenantGuard);
    when(tenantGuard.resolveTenant("t1")).thenReturn("t1");
  }

  @Test
  @DisplayName("按租户查询归档策略时, 返回持久层给出的策略记录")
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
  @DisplayName("目标表在允许清单内时, 归一化后的参数交给仓储落库")
  void shouldUpsertValidTable() {
    ArchivePolicyUpsertParam input = paramOf("job_instance", 500);
    service.upsert(input);

    ArchivePolicyUpsertParam expected = paramOf("job_instance", 500);
    verify(repository).upsert(expected);
  }

  @Test
  @DisplayName("目标表不在允许清单内时, 抛业务异常并提示可选表取值")
  void shouldRejectInvalidTable() {
    ArchivePolicyUpsertParam invalid = paramOf("unknown_table", 500);
    assertThatThrownBy(() -> service.upsert(invalid))
        .isInstanceOf(BizException.class)
        // i18n: BizException.getMessage() 返回 messageKey,改用 messageArgs 检查渲染前的 args 文本
        .satisfies(ex -> assertThat(((BizException) ex).getMessageArgs())
            .anyMatch(a -> a != null && a.toString().contains("target_table must be one of")));
  }

  @Test
  @DisplayName("保留天数小于 1 时, 抛业务异常并返回下限提示")
  void shouldRejectRetentionDaysLessThan1() {
    ArchivePolicyUpsertParam invalid = paramOfRetention("job_instance", 0);
    assertThatThrownBy(() -> service.upsert(invalid))
        .isInstanceOf(BizException.class)
        // 此 case key 本身含 retention_days_min(无 args),检查 messageKey
        .satisfies(
            ex -> assertThat(((BizException) ex).getMessageKey()).contains("retention_days_min"));
  }

  @Test
  @DisplayName("目标表名含大写字母时, 落库前统一转为小写")
  void shouldNormalizeTableToLowercase() {
    ArchivePolicyUpsertParam input = paramOf("JOB_INSTANCE", 500);
    service.upsert(input);

    ArchivePolicyUpsertParam expected = paramOf("job_instance", 500);
    verify(repository).upsert(expected);
  }

  @Test
  @DisplayName("批量大小低于下限时, 落库前抬升到允许的最小值")
  void shouldEnforceBatchSizeMinimum() {
    ArchivePolicyUpsertParam input = paramOf("job_instance", 50);
    service.upsert(input);

    ArchivePolicyUpsertParam expected = paramOf("job_instance", 100);
    verify(repository).upsert(expected);
  }
}
