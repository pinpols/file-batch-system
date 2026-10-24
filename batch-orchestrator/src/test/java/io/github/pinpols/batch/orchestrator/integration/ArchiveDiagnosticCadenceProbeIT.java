package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
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
@Slf4j
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
    List<Interceptor> configuredInterceptors = sessions.getConfiguration().getInterceptors();
    assertThat(configuredInterceptors).contains(recorder);
    UUID outputIdentity = UUID.randomUUID();
    String outputDirectoryName = outputIdentity.toString();
    Path output = Path.of("target", "archive-diagnostic-cadence-probe", outputDirectoryName);
    Files.createDirectories(output);
    Path absoluteOutputDirectory = output.toAbsolutePath();
    log.info("归档诊断探针产物目录：{}", absoluteOutputDirectory);
    String runtimeSql = sql("runtime.sql");
    String runtime = jdbc.queryForObject(runtimeSql, String.class);
    Path postgresEvidencePath = output.resolve("postgres.json");
    String postgresEvidence = Objects.requireNonNull(runtime);
    Files.writeString(postgresEvidencePath, postgresEvidence, StandardCharsets.UTF_8);
    String javaRuntimeVersion = System.getProperty("java.runtime.version");
    String osName = System.getProperty("os.name");
    String osArchitecture = System.getProperty("os.arch");
    Runtime jvm = Runtime.getRuntime();
    int availableProcessors = jvm.availableProcessors();
    long maxHeapBytes = jvm.maxMemory();
    Map<String, Object> host = Map.of("java", javaRuntimeVersion, "os", osName, "arch", osArchitecture,
        "availableProcessors", availableProcessors, "maxHeapBytes", maxHeapBytes);
    Path hostEvidencePath = output.resolve("host.json");
    String hostEvidence = JsonUtils.toJson(host);
    Files.writeString(hostEvidencePath, hostEvidence, StandardCharsets.UTF_8);
    for (int pair = 0; pair < 3; pair++) {
      for (boolean legacy : new boolean[] {pair % 2 == 0, pair % 2 != 0}) {
        compareArm("pair-" + pair + (legacy ? "-legacy" : "-scheduler"), legacy, output);
      }
    }
  }

  private void compareArm(String label, boolean legacy, Path output) throws Throwable {
    Instant observedAt = BatchDateTimeSupport.utcNow();
    int retentionDays = properties.getRetentionDays();
    Duration retention = Duration.ofDays(retentionDays);
    Instant cutoff = observedAt.minus(retention);
    String globalCandidatesSql = sql("global-candidates.sql");
    OffsetDateTime cutoffTimestamp = cutoff.atOffset(ZoneOffset.UTC);
    Map<String, Object> cutoffParameters = Map.of("cutoff", cutoffTimestamp);
    Long existing = named.queryForObject(globalCandidatesSql, cutoffParameters, Long.class);
    assertThat(existing).as("global archive cannot touch another test's old instances").isZero();
    String tenant = "archive-probe-" + UUID.randomUUID();
    List<Map<String, Object>> preexistingDefinitions = state(ProbeTable.JOB_DEFINITION, tenant);
    assertThat(preexistingDefinitions).isEmpty();
    List<Map<String, Object>> preexistingHotInstances = state(ProbeTable.JOB_INSTANCE, tenant);
    assertThat(preexistingHotInstances).isEmpty();
    List<Map<String, Object>> preexistingColdInstances = state(ProbeTable.ARCHIVED_JOB_INSTANCE, tenant);
    assertThat(preexistingColdInstances).isEmpty();
    Throwable primary = null;
    try {
      prepareInstances(tenant);
      List<Map<String, Object>> expected = state(ProbeTable.JOB_INSTANCE, tenant);
      assertThat(expected).hasSize(3 * properties.getBatchSize() - 1);
      String analyzeSql = sql("analyze.sql");
      jdbc.execute(analyzeSql);
      Map<String, Object> diagnosticParameters = Map.of("cutoff", cutoff);
      Path diagnosticPlanPath = output.resolve(label + "-diagnostic-plan.json");
      explain(DIAGNOSTIC, diagnosticParameters, true, diagnosticPlanPath);
      Map<String, Object> candidateParameters = Map.of("cutoff", cutoff, "limit", properties.getBatchSize());
      Path candidatePlanPath = output.resolve(label + "-candidate-plan.json");
      explain(CANDIDATES, candidateParameters, false, candidatePlanPath);
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
        Stats recordedStats = recorder.active.get();
        result = Objects.requireNonNull(recordedStats);
        recorder.active.remove();
        result.elapsedNanos = System.nanoTime() - started;
      }
      Map<String, Object> metrics = result.metrics(expected.size());
      Path metricsPath = output.resolve(label + "-metrics.json");
      String metricsEvidence = JsonUtils.toJson(metrics);
      Files.writeString(metricsPath, metricsEvidence, StandardCharsets.UTF_8);
      assertThat(result.diagnostics).isEqualTo(legacy ? 3 : 1);
      assertThat(result.candidateSizes).containsExactly(10, 10, 9);
      assertThat(result.completionStatuses).hasSize(3).containsOnly(TransactionSynchronization.STATUS_COMMITTED);
      List<Map<String, Object>> remainingHotInstances = state(ProbeTable.JOB_INSTANCE, tenant);
      assertThat(remainingHotInstances).isEmpty();
      List<Map<String, Object>> archivedInstances = state(ProbeTable.ARCHIVED_JOB_INSTANCE, tenant);
      assertThat(archivedInstances).isEqualTo(expected);
    } catch (Throwable failure) {
      primary = failure;
      throw failure;
    } finally {
      try { cleanup(tenant); }
      catch (Throwable cleanupFailure) {
        if (EmptyChecks.isNull(primary)) throw cleanupFailure;
        primary.addSuppressed(cleanupFailure);
      }
    }
  }

  private void prepareInstances(String tenant) throws IOException {
    String jobSql = sql("seed-job.sql");
    String instanceSql = sql("seed-instance.sql");
    TransactionTemplate prepare = new TransactionTemplate(transactions);
    prepare.executeWithoutResult(status -> {
      Map<String, String> tenantParameters = Map.of("tenantId", tenant);
      Long definitionId = named.queryForObject(jobSql, tenantParameters, Long.class);
      List<Long> ids = new ArrayList<>();
      for (int index = 0; index < 3 * properties.getBatchSize() - 1; index++) {
        Long parentId = index % properties.getBatchSize() % 2 == 1 ? ids.getLast() : null;
        MapSqlParameterSource input = new MapSqlParameterSource()
            .addValue("tenantId", tenant).addValue("definitionId", definitionId)
            .addValue("identity", tenant + "-" + index).addValue("parentId", parentId, Types.BIGINT);
        Long id = named.queryForObject(instanceSql, input, Long.class);
        Long instanceId = Objects.requireNonNull(id);
        ids.add(instanceId);
      }
    });
  }

  private List<Map<String, Object>> state(ProbeTable table, String tenant) throws IOException {
    String stateTemplate = sql("owned-state.sql");
    String stateSql = stateTemplate.formatted(table.qualifiedName);
    Map<String, String> tenantParameters = Map.of("tenantId", tenant);
    return named.queryForList(stateSql, tenantParameters);
  }

  private void cleanup(String tenant) throws IOException {
    String clearParentsSql = sql("clear-parents.sql");
    String deleteTemplate = sql("delete-owned.sql");
    TransactionTemplate cleanup = new TransactionTemplate(transactions);
    cleanup.executeWithoutResult(status -> {
      Map<String, String> tenantParameters = Map.of("tenantId", tenant);
      named.update(clearParentsSql, tenantParameters);
      for (ProbeTable table : List.of(
          ProbeTable.ARCHIVED_JOB_INSTANCE, ProbeTable.JOB_INSTANCE, ProbeTable.JOB_DEFINITION)) {
        String deleteSql = deleteTemplate.formatted(table.qualifiedName);
        named.update(deleteSql, tenantParameters);
      }
    });
    List<Map<String, Object>> remainingHotInstances = state(ProbeTable.JOB_INSTANCE, tenant);
    assertThat(remainingHotInstances).isEmpty();
    List<Map<String, Object>> remainingColdInstances = state(ProbeTable.ARCHIVED_JOB_INSTANCE, tenant);
    assertThat(remainingColdInstances).isEmpty();
    List<Map<String, Object>> remainingDefinitions = state(ProbeTable.JOB_DEFINITION, tenant);
    assertThat(remainingDefinitions).isEmpty();
  }

  /** 原 BoundSql 与绑定器保持不变；FOR UPDATE 计划使用可写事务并回滚释放行锁。 */
  private void explain(String id, Map<String, Object> parameters, boolean readOnly, Path output) throws IOException {
    Configuration mybatis = sessions.getConfiguration();
    MappedStatement mapped = mybatis.getMappedStatement(id);
    assertThat(mapped.getCache()).isNull();
    BoundSql bound = mapped.getBoundSql(parameters);
    String explainSql = "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON, SETTINGS) " + bound.getSql();
    TransactionTemplate explain = new TransactionTemplate(transactions);
    explain.setReadOnly(readOnly);
    explain.setTimeout(120); // 仅为探针取消预算，不是生产 SLA。
    String plan = explain.execute(status -> {
      DataSource configuredDataSource = jdbc.getDataSource();
      DataSource dataSource = Objects.requireNonNull(configuredDataSource);
      Connection connection = DataSourceUtils.getConnection(dataSource);
      try (PreparedStatement statement = connection.prepareStatement(explainSql)) {
        DataSourceUtils.applyTransactionTimeout(statement, dataSource);
        ParameterHandler parameterHandler = mybatis.newParameterHandler(mapped, parameters, bound);
        parameterHandler.setParameters(statement);
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
    String capturedPlan = Objects.requireNonNull(plan);
    Files.writeString(output, capturedPlan, StandardCharsets.UTF_8);
    String planFileName = output.getFileName().toString();
    Path capturedSqlPath = output.resolveSibling(planFileName + ".sql");
    String capturedSql = bound.getSql();
    Files.writeString(capturedSqlPath, capturedSql, StandardCharsets.UTF_8);
    Path capturedParametersPath = output.resolveSibling(planFileName + ".parameters.txt");
    String capturedParameters = parameters.toString();
    Files.writeString(capturedParametersPath, capturedParameters, StandardCharsets.UTF_8);
  }

  private static String sql(String name) throws IOException {
    return new ClassPathResource("sql/archive-diagnostic-cadence-probe/" + name)
        .getContentAsString(StandardCharsets.UTF_8);
  }

  /** 固定表身份使状态回读与清理共享同一个闭集，调用方不能传入任意标识符。 */
  private enum ProbeTable {
    JOB_DEFINITION("batch.job_definition"),
    JOB_INSTANCE("batch.job_instance"),
    ARCHIVED_JOB_INSTANCE("archive.job_instance_archive");

    private final String qualifiedName;

    ProbeTable(String qualifiedName) {
      this.qualifiedName = qualifiedName;
    }
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
      boolean statementNeedsNoDiagnosticStats =
          EmptyChecks.isNull(stats) || (!DIAGNOSTIC.equals(id) && !CANDIDATES.equals(id));
      if (statementNeedsNoDiagnosticStats) {
        return invocation.proceed();
      }
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

