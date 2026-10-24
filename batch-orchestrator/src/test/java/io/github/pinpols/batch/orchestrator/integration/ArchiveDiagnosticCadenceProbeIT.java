package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.archive.SuccessInstanceArchiveService;
import io.github.pinpols.batch.orchestrator.config.SuccessInstanceArchiveProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.archive.SuccessInstanceArchiveScheduler;
import io.github.pinpols.batch.orchestrator.mapper.SuccessInstanceArchiveMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 仅显式 -Dtest 执行的诊断成本对照；完整运行树和回滚语义由既有归档 IT 独立证明。 */
@SpringBootTest(classes = BatchOrchestratorApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
  "batch.job-instance.archive.enabled=true",
  "batch.job-instance.archive.retention-days=1",
  "batch.job-instance.archive.batch-size=10",
  "batch.job-instance.archive.cron=-"
})
@Import(ArchiveDiagnosticCadenceProbeIT.ProbeConfiguration.class)
@DisplayName("归档诊断成本探针：相同实例夹具的逐批诊断与调度轮诊断对照")
class ArchiveDiagnosticCadenceProbeIT extends AbstractIntegrationTest {

  private static final String PREFIX = SuccessInstanceArchiveMapper.class.getName() + ".";
  private static final String DIAGNOSTIC = PREFIX + "selectInvalidArchiveReferenceSummaries";
  private static final String CANDIDATES = PREFIX + "selectArchivableInstanceIds";
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;
  private final SuccessInstanceArchiveService service;
  private final SuccessInstanceArchiveScheduler scheduler;
  private final SuccessInstanceArchiveProperties properties;
  private final PlatformTransactionManager transactions;
  private final SqlSessionFactory sessions;
  private final Recorder recorder;

  @Autowired
  ArchiveDiagnosticCadenceProbeIT(JdbcTemplate jdbc, SuccessInstanceArchiveService service,
      SuccessInstanceArchiveScheduler scheduler, SuccessInstanceArchiveProperties properties,
      PlatformTransactionManager transactions, SqlSessionFactory sessions, Recorder recorder) {
    this.jdbc = jdbc;
    this.named = new NamedParameterJdbcTemplate(jdbc);
    this.service = service;
    this.scheduler = scheduler;
    this.properties = properties;
    this.transactions = transactions;
    this.sessions = sessions;
    this.recorder = recorder;
  }

  @Test
  @DisplayName("29 个最小合法成功实例按 10/10/9 归档，诊断次数由三次降为一次且冷行完全一致")
  void shouldCompareDiagnosticCadenceWithIdenticalInstanceShape() throws Throwable {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(properties.isEnabled()).isTrue();
    assertThat(properties.getBatchSize()).isEqualTo(10);
    assertThat(properties.getRetentionDays()).isEqualTo(1);
    assertThat(sessions.getConfiguration().getInterceptors()).contains(recorder);
    Path output = Path.of("target", "archive-diagnostic-cadence-probe", UUID.randomUUID().toString());
    Files.createDirectories(output);
    System.out.println("ARCHIVE_DIAGNOSTIC_PROBE_ARTIFACTS=" + output.toAbsolutePath());
    String runtimeSql = sql("runtime.sql");
    String runtime = jdbc.queryForObject(runtimeSql, String.class);
    Files.writeString(output.resolve("postgres.json"), Objects.requireNonNull(runtime), StandardCharsets.UTF_8);
    Map<String, Object> host = Map.of("java", System.getProperty("java.runtime.version"),
        "os", System.getProperty("os.name"), "arch", System.getProperty("os.arch"),
        "availableProcessors", Runtime.getRuntime().availableProcessors(),
        "maxHeapBytes", Runtime.getRuntime().maxMemory());
    Files.writeString(output.resolve("host.json"), JsonUtils.toJson(host), StandardCharsets.UTF_8);
    for (int pair = 0; pair < 3; pair++) {
      for (boolean legacy : new boolean[] {pair % 2 == 0, pair % 2 != 0}) {
        compareArm("pair-" + pair + (legacy ? "-legacy" : "-scheduler"), legacy, output);
      }
    }
  }

  private void compareArm(String label, boolean legacy, Path output) throws Throwable {
    Instant cutoff = BatchDateTimeSupport.utcNow().minus(Duration.ofDays(properties.getRetentionDays()));
    String globalCandidatesSql = sql("global-candidates.sql");
    Long existing = named.queryForObject(globalCandidatesSql, Map.of("cutoff", cutoff), Long.class);
    assertThat(existing).as("global archive cannot touch another test's old instances").isZero();
    String tenant = "archive-probe-" + UUID.randomUUID();
    assertThat(state("batch.job_definition", tenant)).isEmpty();
    assertThat(state("batch.job_instance", tenant)).isEmpty();
    assertThat(state("archive.job_instance_archive", tenant)).isEmpty();
    Throwable primary = null;
    try {
      prepareInstances(tenant);
      List<Map<String, Object>> expected = state("batch.job_instance", tenant);
      assertThat(expected).hasSize(3 * properties.getBatchSize() - 1);
      jdbc.execute(sql("analyze.sql"));
      explain(DIAGNOSTIC, Map.of("cutoff", cutoff), true, output.resolve(label + "-diagnostic-plan.json"));
      explain(CANDIDATES, Map.of("cutoff", cutoff, "limit", properties.getBatchSize()), false,
          output.resolve(label + "-candidate-plan.json"));
      recorder.active.set(new Stats());
      long started = System.nanoTime();
      Stats result;
      try {
        if (legacy) {
          int batches = 0;
          SuccessInstanceArchiveService.ArchiveBatchResult batch;
          do {
            batch = service.archiveOnce();
            assertThat(++batches).isLessThanOrEqualTo(3);
          } while (batch.hasMore(properties.getBatchSize()));
        } else {
          scheduler.archive();
        }
      } finally {
        result = Objects.requireNonNull(recorder.active.get());
        recorder.active.remove();
        result.elapsedNanos = System.nanoTime() - started;
      }
      Map<String, Object> metrics = result.metrics(expected.size());
      Files.writeString(output.resolve(label + "-metrics.json"), JsonUtils.toJson(metrics), StandardCharsets.UTF_8);
      assertThat(result.diagnostics).isEqualTo(legacy ? 3 : 1);
      assertThat(result.candidateSizes).containsExactly(10, 10, 9);
      assertThat(result.completionStatuses).hasSize(3).containsOnly(TransactionSynchronization.STATUS_COMMITTED);
      assertThat(state("batch.job_instance", tenant)).isEmpty();
      assertThat(state("archive.job_instance_archive", tenant)).isEqualTo(expected);
    } catch (Throwable failure) {
      primary = failure;
      throw failure;
    } finally {
      try { cleanup(tenant); }
      catch (Throwable cleanupFailure) {
        if (primary == null) throw cleanupFailure;
        primary.addSuppressed(cleanupFailure);
      }
    }
  }

  private void prepareInstances(String tenant) throws IOException {
    String jobSql = sql("seed-job.sql");
    String instanceSql = sql("seed-instance.sql");
    TransactionTemplate prepare = new TransactionTemplate(transactions);
    prepare.executeWithoutResult(status -> {
      Long definitionId = named.queryForObject(jobSql, Map.of("tenantId", tenant), Long.class);
      List<Long> ids = new ArrayList<>();
      for (int index = 0; index < 3 * properties.getBatchSize() - 1; index++) {
        Long parentId = index % properties.getBatchSize() % 2 == 1 ? ids.getLast() : null;
        MapSqlParameterSource input = new MapSqlParameterSource()
            .addValue("tenantId", tenant).addValue("definitionId", definitionId)
            .addValue("identity", tenant + "-" + index).addValue("parentId", parentId, Types.BIGINT);
        Long id = named.queryForObject(instanceSql, input, Long.class);
        ids.add(Objects.requireNonNull(id));
      }
    });
  }

  private List<Map<String, Object>> state(String table, String tenant) throws IOException {
    String stateSql = sql("owned-state.sql").formatted(table);
    return named.queryForList(stateSql, Map.of("tenantId", tenant));
  }

  private void cleanup(String tenant) throws IOException {
    String clearParentsSql = sql("clear-parents.sql");
    String deleteTemplate = sql("delete-owned.sql");
    TransactionTemplate cleanup = new TransactionTemplate(transactions);
    cleanup.executeWithoutResult(status -> {
      named.update(clearParentsSql, Map.of("tenantId", tenant));
      for (String table : List.of("archive.job_instance_archive", "batch.job_instance", "batch.job_definition")) {
        String deleteSql = deleteTemplate.formatted(table);
        named.update(deleteSql, Map.of("tenantId", tenant));
      }
    });
    assertThat(state("batch.job_instance", tenant)).isEmpty();
    assertThat(state("archive.job_instance_archive", tenant)).isEmpty();
    assertThat(state("batch.job_definition", tenant)).isEmpty();
  }

  /** 原 BoundSql 与绑定器保持不变；FOR UPDATE 计划使用可写事务并回滚释放行锁。 */
  private void explain(String id, Map<String, Object> parameters, boolean readOnly, Path output) throws IOException {
    MappedStatement mapped = sessions.getConfiguration().getMappedStatement(id);
    assertThat(mapped.getCache()).isNull();
    BoundSql bound = mapped.getBoundSql(parameters);
    String explainSql = "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON, SETTINGS) " + bound.getSql();
    TransactionTemplate explain = new TransactionTemplate(transactions);
    explain.setReadOnly(readOnly);
    explain.setTimeout(120); // 仅为探针取消预算，不是生产 SLA。
    String plan = explain.execute(status -> {
      DataSource dataSource = Objects.requireNonNull(jdbc.getDataSource());
      Connection connection = DataSourceUtils.getConnection(dataSource);
      try (PreparedStatement statement = connection.prepareStatement(explainSql)) {
        DataSourceUtils.applyTransactionTimeout(statement, dataSource);
        sessions.getConfiguration().newParameterHandler(mapped, parameters, bound).setParameters(statement);
        try (ResultSet rows = statement.executeQuery()) {
          if (!rows.next()) throw new IllegalStateException("EXPLAIN returned no rows");
          return rows.getString(1);
        }
      } catch (SQLException failure) {
        throw new IllegalStateException("archive plan failed: " + id, failure);
      } finally {
        status.setRollbackOnly();
        DataSourceUtils.releaseConnection(connection, dataSource);
      }
    });
    Files.writeString(output, Objects.requireNonNull(plan), StandardCharsets.UTF_8);
    Files.writeString(output.resolveSibling(output.getFileName() + ".sql"), bound.getSql(), StandardCharsets.UTF_8);
    Files.writeString(output.resolveSibling(output.getFileName() + ".parameters.txt"),
        parameters.toString(), StandardCharsets.UTF_8);
  }

  private static String sql(String name) throws IOException {
    return new ClassPathResource("sql/archive-diagnostic-cadence-probe/" + name)
        .getContentAsString(StandardCharsets.UTF_8);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ProbeConfiguration {
    @Bean
    Recorder archiveDiagnosticRecorder() { return new Recorder(); }
  }

  @Intercepts(@Signature(type = Executor.class, method = "query",
      args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
  static final class Recorder implements Interceptor {
    private final ThreadLocal<Stats> active = new ThreadLocal<>();

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
      Stats stats = active.get();
      MappedStatement mapped = (MappedStatement) invocation.getArgs()[0];
      String id = mapped.getId();
      if (stats == null || (!DIAGNOSTIC.equals(id) && !CANDIDATES.equals(id))) return invocation.proceed();
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
      assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
      if (!TransactionSynchronizationManager.isCurrentTransactionReadOnly()
          && TransactionSynchronizationManager.getSynchronizations().stream()
              .noneMatch(sync -> sync instanceof BatchTiming)) {
        TransactionSynchronizationManager.registerSynchronization(new BatchTiming(stats));
      }
      long started = System.nanoTime();
      Object value = invocation.proceed();
      long elapsed = System.nanoTime() - started;
      if (DIAGNOSTIC.equals(id)) {
        stats.diagnostics++;
        stats.diagnosticNanos.add(elapsed);
      } else {
        stats.candidateSizes.add(((List<?>) value).size());
        stats.candidateNanos.add(elapsed);
      }
      return value;
    }
  }

  private static final class BatchTiming implements TransactionSynchronization {
    private final Stats stats;
    private final long firstSqlNanos = System.nanoTime();
    BatchTiming(Stats stats) { this.stats = stats; }

    @Override
    public void afterCompletion(int status) {
      stats.batchNanos.add(System.nanoTime() - firstSqlNanos);
      stats.completionStatuses.add(status);
    }
  }

  private static final class Stats {
    private int diagnostics;
    private long elapsedNanos;
    private final List<Integer> candidateSizes = new ArrayList<>();
    private final List<Long> diagnosticNanos = new ArrayList<>();
    private final List<Long> candidateNanos = new ArrayList<>();
    private final List<Long> batchNanos = new ArrayList<>();
    private final List<Integer> completionStatuses = new ArrayList<>();

    Map<String, Object> metrics(int instances) {
      return Map.of("diagnosticCalls", diagnostics, "candidateSizes", candidateSizes,
          "diagnosticSelectNanos", diagnosticNanos, "candidateSelectNanos", candidateNanos,
          "firstSqlToCompletionNanos", batchNanos,
          "transactionStatuses", completionStatuses, "instances", instances,
          "elapsedNanos", elapsedNanos, "instancesPerSecond", instances * 1_000_000_000d / elapsedNanos);
    }
  }
}

