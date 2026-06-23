package io.github.pinpols.batch.worker.core.reportoutbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.worker.core.config.OrchestratorTaskClientProperties;
import io.github.pinpols.batch.worker.core.domain.TaskExecutionReport;
import io.github.pinpols.batch.worker.core.infrastructure.HttpTaskExecutionClient;
import io.github.pinpols.batch.worker.core.infrastructure.WorkerTaskLeaseRenewer;
import io.github.pinpols.batch.worker.core.reportoutbox.sqlite.WorkerReportOutboxSqliteMapper;
import io.github.pinpols.batch.worker.core.reportoutbox.sqlite.WorkerReportOutboxSqliteSessionFactorySupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
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
class WorkerReportOutboxCoordinatorTest {

  @Test
  void defersFailedReportThenPollSucceeds() throws Exception {
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
    SqlSessionFactory sf =
        WorkerReportOutboxSqliteSessionFactorySupport.createSqlSessionFactory(ds);
    // SqlSessionTemplate 原 close() 抛 UnsupportedOperationException(Spring 托管 session),
    // 覆写为 destroy()(DisposableBean)走真正的清理,这样可放进 try-with-resources。
    try (MockWebServer server = new MockWebServer();
        SqlSessionTemplate sessionTemplate =
            new SqlSessionTemplate(sf) {
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
      WorkerReportOutboxRepository repo =
          new WorkerReportOutboxRepository(
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

      HttpTaskExecutionClient client =
          new HttpTaskExecutionClient(
              httpProps,
              new BatchSecurityProperties(),
              restClientBuilderProvider(),
              new MockEnvironment(),
              null,
              coordinatorProvider,
              new io.github.pinpols.batch.worker.core.config.WorkerLeaseProperties());

      @SuppressWarnings("unchecked")
      ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);
      when(meterRegistryProvider.getIfAvailable()).thenReturn(null);

      @SuppressWarnings("unchecked")
      ObjectProvider<WorkerTaskLeaseRenewer> leaseRenewerProvider = mock(ObjectProvider.class);
      when(leaseRenewerProvider.getIfAvailable()).thenReturn(null);

      WorkerReportOutboxCoordinator coordinator =
          new WorkerReportOutboxCoordinator(
              repo, props, client, meterRegistryProvider, leaseRenewerProvider, pollClaimer);
      coordinatorRef.set(coordinator);

      TaskExecutionReport report = report();
      client.report(report);

      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM worker_report_outbox WHERE publish_status='NEW'",
                  Integer.class))
          .isEqualTo(1);

      server.enqueue(new MockResponse.Builder().code(200).build());
      coordinator.pollDeferredReports();

      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM worker_report_outbox", Integer.class))
          .isZero();
    }
  }

  private static RestClient.Builder jsonRestClientBuilder() {
    return RestClient.builder()
        .configureMessageConverters(
            b ->
                b.configureMessageConvertersList(
                    converters -> converters.add(0, new JacksonJsonHttpMessageConverter())));
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
