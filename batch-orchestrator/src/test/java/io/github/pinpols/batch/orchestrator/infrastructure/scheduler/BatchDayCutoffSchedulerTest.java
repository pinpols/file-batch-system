package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.service.BatchDayTimePolicyResolver;
import io.github.pinpols.batch.orchestrator.service.CutoffScheduleResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@DisplayName("截止调度的租户上下文边界")
class BatchDayCutoffSchedulerTest {

  private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

  @AfterEach
  void clearTenantContext() {
    RlsTenantContextHolder.clear();
  }

  @DisplayName("全局候选逐个在所属租户上下文处理并及时清除")
  @Test
  void shouldProcessEachGlobalCandidateInsideItsTenantContextAndClearItAfterward() {
    BatchDayInstanceMapper batchDayMapper = mock(BatchDayInstanceMapper.class);
    OrchestratorConfigCacheService configCache = mock(OrchestratorConfigCacheService.class);
    JobExecutionLogMapper executionLogMapper = mock(JobExecutionLogMapper.class);
    OrchestratorGracefulShutdown gracefulShutdown = mock(OrchestratorGracefulShutdown.class);
    PlatformTransactionManager transactionManager = new NoopTransactionManager();
    List<String> observedTenants = new CopyOnWriteArrayList<>();
    when(gracefulShutdown.isDraining()).thenReturn(false);
    when(batchDayMapper.selectByDayStatusIn(List.of("OPEN")))
        .thenReturn(List.of(candidate("tenant-a"), candidate("tenant-b")));
    when(batchDayMapper.updateWithCas(any())).thenAnswer(invocation -> {
      assertThat(RlsTenantContextHolder.get())
          .isEqualTo(((BatchDayInstanceEntity) invocation.getArgument(0)).tenantId());
      observedTenants.add(RlsTenantContextHolder.get());
      return 1;
    });
    doAnswer(invocation -> {
          JobExecutionLogEntity logEntity = invocation.getArgument(0);
          assertThat(RlsTenantContextHolder.get()).isEqualTo(logEntity.getTenantId());
          return 1;
        })
        .when(executionLogMapper)
        .insert(any());

    BatchTimezoneProvider timezoneProvider =
        new BatchTimezoneProvider(new BatchTimezoneProperties());
    BatchDayCutoffScheduler scheduler = new BatchDayCutoffScheduler(
        batchDayMapper,
        configCache,
        executionLogMapper,
        gracefulShutdown,
        new BatchDayTimePolicyResolver(timezoneProvider, new CutoffScheduleResolver()),
        new BatchDateTimeSupport(Clock.fixed(NOW, ZoneOffset.UTC), timezoneProvider),
        transactionManager);

    scheduler.advance();

    assertThat(observedTenants).containsExactly("tenant-a", "tenant-b");
    assertThat(RlsTenantContextHolder.get()).isNull();
  }

  private static BatchDayInstanceEntity candidate(String tenantId) {
    return BatchDayInstanceEntity.builder()
        .tenantId(tenantId)
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, 10, 16))
        .dayStatus("OPEN")
        .cutoffAt(NOW.minusSeconds(1))
        .frozen(false)
        .version(0L)
        .build();
  }

  private static final class NoopTransactionManager implements PlatformTransactionManager {
    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {}

    @Override
    public void rollback(TransactionStatus status) {}
  }
}
