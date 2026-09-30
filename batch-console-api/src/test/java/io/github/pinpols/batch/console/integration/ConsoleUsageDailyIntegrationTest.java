package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.observability.mapper.ConsoleUsageDailyMapper;
import io.github.pinpols.batch.console.domain.observability.mapper.ConsoleUsageDailyMapper.DailyUsageRow;
import io.github.pinpols.batch.console.domain.observability.service.ConsoleUsageDailyRecorder;
import io.github.pinpols.batch.console.domain.observability.service.ConsoleUsageSummaryService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ConsoleUsageDailyIntegrationTest extends AbstractIntegrationTest {

  private static final String RLS_ROLE = "console_usage_rls_test";

  private final ConsoleUsageDailyRecorder recorder;
  private final ConsoleUsageSummaryService summaryService;
  private final ConsoleUsageDailyMapper mapper;
  private final BatchTimezoneProvider timezoneProvider;
  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;

  @Autowired
  ConsoleUsageDailyIntegrationTest(
      ConsoleUsageDailyRecorder recorder,
      ConsoleUsageSummaryService summaryService,
      ConsoleUsageDailyMapper mapper,
      BatchTimezoneProvider timezoneProvider,
      JdbcTemplate jdbcTemplate,
      PlatformTransactionManager transactionManager) {
    this.recorder = recorder;
    this.summaryService = summaryService;
    this.mapper = mapper;
    this.timezoneProvider = timezoneProvider;
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  @BeforeAll
  static void grantRlsTestRole(@Autowired JdbcTemplate jdbcTemplate) {
    jdbcTemplate.execute("""
        DO $$
        BEGIN
          IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'console_usage_rls_test') THEN
            CREATE ROLE console_usage_rls_test NOSUPERUSER NOBYPASSRLS;
          END IF;
        END
        $$
        """);
    jdbcTemplate.execute("GRANT USAGE ON SCHEMA batch TO " + RLS_ROLE);
    jdbcTemplate.execute("GRANT SELECT ON batch.console_usage_daily TO " + RLS_ROLE);
  }

  @Test
  void concurrentRecordsAccumulateCountsAndPreserveLatestTimestamp() throws Exception {
    String tenantId = unique("usage-concurrent");
    Instant baseTime = LocalDate.now(timezoneProvider.defaultZone())
        .atTime(12, 0)
        .atZone(timezoneProvider.defaultZone())
        .toInstant();
    int workers = 8;
    int recordsPerWorker = 12;

    try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {
      List<? extends Future<?>> futures = java.util.stream.IntStream.range(0, workers)
          .mapToObj(worker -> executor.submit(() -> {
            for (int record = 0; record < recordsPerWorker; record++) {
              int sequence = worker * recordsPerWorker + record;
              recorder.record(
                  tenantId, "job.run", sequence % 3 != 0, baseTime.plusSeconds(sequence));
            }
          }))
          .toList();
      for (Future<?> future : futures) {
        future.get();
      }
    }

    LocalDate statDate = baseTime.atZone(timezoneProvider.defaultZone()).toLocalDate();
    List<DailyUsageRow> rows =
        summaryService.query(tenantId, statDate, statDate, "operation.job.run", null);

    assertThat(rows).singleElement().satisfies(row -> {
      assertThat(row.eventCount()).isEqualTo((long) workers * recordsPerWorker);
      assertThat(row.successCount()).isEqualTo(64L);
      assertThat(row.failureCount()).isEqualTo(32L);
      assertThat(row.lastSeenAt().toInstant())
          .isEqualTo(baseTime.plusSeconds((long) workers * recordsPerWorker - 1));
    });
  }

  @Test
  void summaryQueryRemainsTenantIsolatedUnderDatabaseRls() {
    String tenantA = unique("usage-tenant-a");
    String tenantB = unique("usage-tenant-b");
    Instant eventTime = Instant.now();
    LocalDate statDate = eventTime.atZone(timezoneProvider.defaultZone()).toLocalDate();
    recorder.record(tenantA, "job.run", true, eventTime);
    recorder.record(tenantB, "job.run", true, eventTime);

    transactionTemplate.executeWithoutResult(status -> {
      jdbcTemplate.execute("SET LOCAL ROLE " + RLS_ROLE);
      mapper.setTenantContext(tenantA);

      assertThat(mapper.querySummary(tenantB, statDate, statDate, "operation.job.run", null))
          .isEmpty();
      assertThat(mapper.querySummary(tenantA, statDate, statDate, "operation.job.run", null))
          .hasSize(1);
    });

    transactionTemplate.executeWithoutResult(status -> {
      jdbcTemplate.execute("SET LOCAL ROLE " + RLS_ROLE);
      assertThat(mapper.querySummary(tenantA, statDate, statDate, "operation.job.run", null))
          .isEmpty();
    });
  }

  private static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }
}
