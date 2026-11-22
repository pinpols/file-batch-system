package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.application.readiness.ReadinessWaitService;
import io.github.pinpols.batch.orchestrator.application.readiness.TriggerReadinessRecoveryClient;
import io.github.pinpols.batch.orchestrator.application.service.readiness.ReadinessResult;
import io.github.pinpols.batch.orchestrator.application.service.readiness.ReadinessService;
import io.github.pinpols.batch.orchestrator.domain.command.ReadinessWaitHandoverCommand;
import io.github.pinpols.batch.orchestrator.mapper.ReadinessWaitMapper;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DisplayName("readiness 持久等待: 超过扫描批次后已就绪记录仍可推进")
class ReadinessWaitFairnessIntegrationTest {
  @Test
  @DisplayName("扫描后并发取消，超时条件更新零行时不计超时")
  void shouldNotCountTimeoutAfterConcurrentCancellation() throws Exception {
    try (PostgreSQLContainer postgres = TestPostgresContainers.platform()) {
      postgres.start();
      PGSimpleDataSource dataSource = new PGSimpleDataSource();
      dataSource.setURL(postgres.getJdbcUrl());
      dataSource.setUser(postgres.getUsername());
      dataSource.setPassword(postgres.getPassword());
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      String createSchemaSql = "CREATE SCHEMA batch";
      jdbc.execute(createSchemaSql);
      new ResourceDatabasePopulator(new ClassPathResource("db/migration/V351__readiness_wait.sql"))
          .execute(dataSource);
      Environment environment =
          new Environment("cancel-race", new JdbcTransactionFactory(), dataSource);
      Configuration configuration = new Configuration(environment);
      configuration.addMapper(ReadinessWaitMapper.class);
      String resource = "mapper/ReadinessWaitMapper.xml";
      try (InputStream input = Resources.getResourceAsStream(resource)) {
        new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments())
            .parse();
      }
      SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
      try (SqlSession session = factory.openSession(true)) {
        ReadinessWaitMapper mapper = session.getMapper(ReadinessWaitMapper.class);
        ReadinessService readiness = mock(ReadinessService.class);
        TriggerReadinessRecoveryClient recovery = mock(TriggerReadinessRecoveryClient.class);
        SimpleMeterRegistry meter = new SimpleMeterRegistry();
        ReadinessWaitService service = new ReadinessWaitService(mapper, readiness, recovery, meter);
        Instant now = Instant.now();
        ReadinessWaitHandoverCommand command = new ReadinessWaitHandoverCommand(
            "tenant-cancel",
            7L,
            "WF",
            "CAL",
            1,
            List.of("UPSTREAM"),
            LocalDate.parse("2026-10-26"),
            now.minusSeconds(7200),
            now.minusSeconds(7200),
            now.minusSeconds(60),
            "trace");
        assertThat(service.handover(command)).isTrue();
        String cancelSql =
            "update batch.readiness_wait set wait_status='CANCELLED' where tenant_id=? and workflow_definition_id=?";
        when(readiness.checkJobReady(anyString(), anyString(), any())).thenAnswer(invocation -> {
          assertThat(jdbc.update(cancelSql, "tenant-cancel", 7L)).isEqualTo(1);
          return ReadinessResult.ofNotReady("not-ready");
        });
        ReadinessWaitService.SweepOutcome outcome = service.sweep(10);
        String selectStatus =
            "select wait_status from batch.readiness_wait where tenant_id=? and workflow_definition_id=?";
        assertThat(jdbc.queryForObject(selectStatus, String.class, "tenant-cancel", 7L))
            .isEqualTo("CANCELLED");
        assertThat(outcome.timedOut()).isZero();
        assertThat(meter.counter("batch.readiness.wait.timeout.total").count()).isZero();
      }
    }
  }

  @Test
  @DisplayName("前百条持续未就绪时, 第一百零一条在下一轮恢复")
  void shouldRotateDeferredWaits_whenMoreThanOneBatch() throws Exception {
    try (PostgreSQLContainer postgres = TestPostgresContainers.platform()) {
      postgres.start();
      PGSimpleDataSource dataSource = new PGSimpleDataSource();
      dataSource.setURL(postgres.getJdbcUrl());
      dataSource.setUser(postgres.getUsername());
      dataSource.setPassword(postgres.getPassword());
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      String createSchemaSql = "CREATE SCHEMA batch";
      jdbc.execute(createSchemaSql);
      ClassPathResource migration = new ClassPathResource("db/migration/V351__readiness_wait.sql");
      new ResourceDatabasePopulator(migration).execute(dataSource);
      Environment environment =
          new Environment("fairness", new JdbcTransactionFactory(), dataSource);
      Configuration configuration = new Configuration(environment);
      configuration.addMapper(ReadinessWaitMapper.class);
      String resource = "mapper/ReadinessWaitMapper.xml";
      try (InputStream input = Resources.getResourceAsStream(resource)) {
        new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments())
            .parse();
      }
      SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
      try (SqlSession session = factory.openSession(true)) {
        ReadinessWaitMapper mapper = session.getMapper(ReadinessWaitMapper.class);
        ReadinessService readiness = mock(ReadinessService.class);
        TriggerReadinessRecoveryClient recovery = mock(TriggerReadinessRecoveryClient.class);
        SimpleMeterRegistry meter = new SimpleMeterRegistry();
        ReadinessWaitService service = new ReadinessWaitService(mapper, readiness, recovery, meter);
        when(readiness.checkJobReady(anyString(), anyString(), any()))
            .thenAnswer(invocation -> ReadinessResult.builder()
                .ready("READY".equals(invocation.getArgument(1)))
                .build());
        when(recovery.recover(any()))
            .thenReturn(TriggerReadinessRecoveryClient.RecoveryOutcome.LAUNCHED);
        Instant fire = Instant.now();
        for (int index = 1; index <= 101; index++) {
          String dependency = index == 101 ? "READY" : "BLOCKED";
          ReadinessWaitHandoverCommand command = new ReadinessWaitHandoverCommand(
              "tenant-fairness",
              (long) index,
              "WF_" + index,
              "CAL",
              1,
              List.of(dependency),
              LocalDate.parse("2026-10-26"),
              fire,
              fire,
              fire.plusSeconds(3600 + index),
              "trace-fairness");
          service.handover(command);
        }

        ReadinessWaitService.SweepOutcome first = service.sweep(100);
        ReadinessWaitService.SweepOutcome second = service.sweep(100);
        String selectReleasedSql = """
            SELECT count(*) FROM batch.readiness_wait WHERE wait_status = 'RELEASED'
            """;
        Integer released = jdbc.queryForObject(selectReleasedSql, Integer.class);

        assertThat(first.deferred()).isEqualTo(100);
        assertThat(second.released()).isEqualTo(1);
        assertThat(released).isEqualTo(1);
      }
    }
  }
}
