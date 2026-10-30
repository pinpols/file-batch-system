package io.github.pinpols.batch.worker.core.reportoutbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.worker.core.config.OrchestratorTaskClientProperties;
import io.github.pinpols.batch.worker.core.config.WorkerBatchClaimProperties;
import io.github.pinpols.batch.worker.core.config.WorkerLeaseProperties;
import io.github.pinpols.batch.worker.core.domain.TaskExecutionReport;
import io.github.pinpols.batch.worker.core.infrastructure.ActiveTaskLeaseRegistry;
import io.github.pinpols.batch.worker.core.infrastructure.HttpTaskExecutionClient;
import io.github.pinpols.batch.worker.core.infrastructure.OrchestratorReportHttpSubmitter;
import io.github.pinpols.batch.worker.core.infrastructure.WorkerTaskLeaseRenewer;
import io.github.pinpols.batch.worker.core.mapper.WorkerReportOutboxPgMapper;
import io.github.pinpols.batch.worker.core.reportoutbox.sqlite.SqliteSessionFactorySupport;
import io.github.pinpols.batch.worker.core.reportoutbox.sqlite.WorkerReportOutboxSqliteMapper;
import io.github.pinpols.batch.worker.core.support.TaskExecutionClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/** ADR-015：HTTP REPORT 失败后写入 SQLite outbox，poller 抢占并重投 orchestrator。 */
@DisplayName("上报发件箱协调器: 上报失败落库待投与轮询重投")
class WorkerReportOutboxCoordinatorTest {

  @Test
  @DisplayName("上报失败时结果落入本地发件箱待投, 轮询重投成功后记录被清理")
  void shouldDeferFailedReport_thenResendOnPoll() throws Exception {
    Path dbFile = Files.createTempFile("worker-outbox-", ".db");
    dbFile.toFile().deleteOnExit();

    WorkerReportOutboxProperties props = new WorkerReportOutboxProperties();
    props.setStorage(WorkerReportOutboxStorage.SQLITE);
    props.setSqlitePath(dbFile.toString());
    props.setMaxPublishAttempts(5);
    props.setPollBatchSize(8);

    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setDriverClassName("org.sqlite.JDBC");
    ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
    JdbcTemplate jdbc = new JdbcTemplate(ds);
    SqlSessionFactory sf = SqliteSessionFactorySupport.createSqlSessionFactory(ds);
    // SqlSessionTemplate 原 close() 抛 UnsupportedOperationException(Spring 托管 session),
    // 覆写为 destroy()(DisposableBean)走真正的清理,这样可放进 try-with-resources。
    try (MockWebServer server = new MockWebServer();
        SqlSessionTemplate sessionTemplate = new SqlSessionTemplate(sf) {
          @Override
          public void close() {
            try {
              destroy();
            } catch (Exception e) {
              throw new IllegalStateException("destroy SqlSessionTemplate failed", e);
            }
          }
        }) {
      WorkerReportOutboxSqliteMapper sqliteMapper =
          sessionTemplate.getMapper(WorkerReportOutboxSqliteMapper.class);
      WorkerReportOutboxRepository repo = new WorkerReportOutboxRepository(
          props, WorkerReportOutboxDialect.SQLITE, null, sqliteMapper, jdbc);

      TransactionTemplate tt = new TransactionTemplate(new DataSourceTransactionManager(ds));
      tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      WorkerReportOutboxPollClaimer pollClaimer = new WorkerReportOutboxPollClaimer(repo, tt);

      server.enqueue(new MockResponse.Builder().code(503).build());
      server.enqueue(new MockResponse.Builder().code(503).build());
      server.start();

      OrchestratorTaskClientProperties httpProps = new OrchestratorTaskClientProperties();
      httpProps.setBaseUrl("http://127.0.0.1:" + server.getPort());
      httpProps.setReportMaxAttempts(2);
      httpProps.setReportInitialBackoffMillis(2);
      httpProps.setReportMaxBackoffMillis(10);

      AtomicReference<WorkerReportOutboxCoordinator> coordinatorRef = new AtomicReference<>();
      @SuppressWarnings("unchecked")
      ObjectProvider<WorkerReportOutboxCoordinator> coordinatorProvider =
          mock(ObjectProvider.class);
      when(coordinatorProvider.getIfAvailable()).thenAnswer(invocation -> coordinatorRef.get());

      HttpTaskExecutionClient client = new HttpTaskExecutionClient(
          httpProps,
          new BatchSecurityProperties(),
          restClientBuilderProvider(),
          new MockEnvironment(),
          null,
          coordinatorProvider,
          new WorkerLeaseProperties(),
          new WorkerBatchClaimProperties());

      @SuppressWarnings("unchecked")
      ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);
      when(meterRegistryProvider.getIfAvailable()).thenReturn(null);

      @SuppressWarnings("unchecked")
      ObjectProvider<WorkerTaskLeaseRenewer> leaseRenewerProvider = mock(ObjectProvider.class);
      ActiveTaskLeaseRegistry leases = mock(ActiveTaskLeaseRegistry.class);
      TaskExecutionClient leaseClient = mock(TaskExecutionClient.class);
      WorkerLeaseProperties leaseProperties = new WorkerLeaseProperties();
      leaseProperties.setCircuitHalfOpenTickInterval(1);
      WorkerTaskLeaseRenewer renewer =
          new WorkerTaskLeaseRenewer(leases, leaseClient, meterRegistryProvider, leaseProperties);
      ActiveTaskLeaseRegistry.ActiveTaskLease activeLease =
          new ActiveTaskLeaseRegistry.ActiveTaskLease("100", "t1", "w1", null);
      when(leases.snapshot()).thenReturn(List.of(activeLease));
      when(leaseClient.renewLeasesBatch(anyList())).thenThrow(new IllegalStateException("offline"));
      renewer.renewActiveTaskLeases();
      assertThat(renewer.isRenewCircuitOpen()).isTrue();
      when(leaseRenewerProvider.getIfAvailable()).thenReturn(renewer);

      WorkerReportOutboxCoordinator coordinator = new WorkerReportOutboxCoordinator(
          repo, props, client, meterRegistryProvider, leaseRenewerProvider, pollClaimer);
      coordinatorRef.set(coordinator);

      TaskExecutionReport report = report();
      Map<String, Object> outputs =
          Map.of("lastResultSet", List.of(Map.of("payload", Map.of("answer", 42))));
      report.setOutputs(outputs);
      client.report(report);

      final String selectWorkerReportOutboxSql = """
          SELECT COUNT(*) FROM worker_report_outbox WHERE publish_status='NEW'
      """;
      Integer selectedWorkerReportOutboxCount =
          jdbc.queryForObject(selectWorkerReportOutboxSql, Integer.class);
      assertThat(selectedWorkerReportOutboxCount).isEqualTo(1);

      server.enqueue(new MockResponse.Builder().code(200).build());
      // 模拟 wrapper 完成后移除最后租约; 发件箱必须在没有新任务时恢复重投。
      when(leases.snapshot()).thenReturn(List.of());
      renewer.renewActiveTaskLeases();
      coordinator.pollDeferredReports();

      assertThat(server.takeRequest(1, TimeUnit.SECONDS)).isNotNull();
      assertThat(server.takeRequest(1, TimeUnit.SECONDS)).isNotNull();
      var retryRequest = server.takeRequest(1, TimeUnit.SECONDS);
      assertThat(retryRequest).isNotNull();
      Map<?, ?> retryBody = JsonUtils.fromJson(retryRequest.getBody().utf8(), Map.class);
      assertThat(retryBody.get("outputs")).isEqualTo(outputs);

      final String selectWorkerReportOutboxSqlValue1 = """
          SELECT COUNT(*) FROM worker_report_outbox
      """;
      Integer selectedWorkerReportOutboxCountRead2 =
          jdbc.queryForObject(selectWorkerReportOutboxSqlValue1, Integer.class);
      assertThat(selectedWorkerReportOutboxCountRead2).isZero();
    }
  }

  @ParameterizedTest
  @CsvSource({"true,0", "true,1", "false,0", "false,1"})
  @DisplayName("损坏载荷或重试耗尽，仅在当前领取实际放弃后计入告警指标")
  void shouldCountGiveUpOnlyAfterCurrentClaimWasChanged_whenPayloadOrTransportFails(
      boolean corruptPayload, int affectedRows) {
    WorkerReportOutboxProperties props = new WorkerReportOutboxProperties();
    props.setPausePollWhenRenewCircuitOpen(false);
    props.setPollBatchSize(1);
    WorkerReportOutboxPgMapper mapper = mock(WorkerReportOutboxPgMapper.class);
    if (corruptPayload) {
      when(mapper.giveUpRow(any())).thenReturn(affectedRows);
    } else {
      when(mapper.selectAttemptCount(71L, "claim-owner", "PUBLISHING"))
          .thenReturn(props.getMaxPublishAttempts() - 1);
      when(mapper.updateGiveUp(any())).thenReturn(affectedRows);
    }
    WorkerReportOutboxRepository repository = new WorkerReportOutboxRepository(
        props, WorkerReportOutboxDialect.POSTGRESQL, mapper, null, null);
    WorkerReportOutboxPollClaimer claimer = mock(WorkerReportOutboxPollClaimer.class);
    String payload = corruptPayload ? "invalid-json" : JsonUtils.toJson(report());
    WorkerReportOutboxRow row = new WorkerReportOutboxRow(71L, payload, "claim-owner");
    when(claimer.claimNext(anyLong())).thenReturn(Optional.of(row));
    @SuppressWarnings("unchecked")
    ObjectProvider<MeterRegistry> metrics = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<WorkerTaskLeaseRenewer> renewers = mock(ObjectProvider.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try {
      when(metrics.getIfAvailable()).thenReturn(registry);
      OrchestratorReportHttpSubmitter http = mock(OrchestratorReportHttpSubmitter.class);
      if (!corruptPayload) {
        doThrow(new IllegalStateException("HTTP unavailable"))
            .when(http)
            .submitReportOverHttp(any());
      }
      WorkerReportOutboxCoordinator coordinator =
          new WorkerReportOutboxCoordinator(repository, props, http, metrics, renewers, claimer);

      coordinator.pollDeferredReports();

      Counter counter =
          registry.find("batch.worker.report.outbox.give_up.total").counter();
      double counted = counter == null ? 0 : counter.count();
      assertThat(counted).isEqualTo(affectedRows);
    } finally {
      registry.close();
    }
  }

  private static RestClient.Builder jsonRestClientBuilder() {
    return RestClient.builder()
        .configureMessageConverters(b -> b.configureMessageConvertersList(
            converters -> converters.addFirst(new JacksonJsonHttpMessageConverter())));
  }

  private static ObjectProvider<RestClient.Builder> restClientBuilderProvider() {
    return new ObjectProvider<>() {
      @Override
      public RestClient.Builder getObject(Object... args) {
        return jsonRestClientBuilder();
      }

      @Override
      public RestClient.Builder getObject() {
        return jsonRestClientBuilder();
      }

      @Override
      public RestClient.Builder getIfAvailable() {
        return jsonRestClientBuilder();
      }

      @Override
      public RestClient.Builder getIfUnique() {
        return jsonRestClientBuilder();
      }
    };
  }

  private static TaskExecutionReport report() {
    TaskExecutionReport r = new TaskExecutionReport();
    r.setTaskId(77L);
    r.setTenantId("t-outbox");
    r.setWorkerId("w1");
    r.setSuccess(true);
    return r;
  }
}
