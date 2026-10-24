package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exercises the PostgreSQL settlement fence from two independent Orchestrator JVMs. */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("批量日结算:独立 Orchestrator 进程通过 PostgreSQL 围栏收敛")
class BatchDaySettleMultiProcessIntegrationTest extends AbstractIntegrationTest {

  private final JdbcTemplate jdbcTemplate;

  @Autowired
  BatchDaySettleMultiProcessIntegrationTest(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Test
  @DisplayName("两个独立 JVM 同时结算时只推进一次终态版本")
  void shouldSettleOnce_whenIndependentProcessesRaceOnSameBusinessDay() throws Exception {
    String tenantId = "settle-process-" + UUID.randomUUID().toString().replace("-", "");
    String calendarCode = "CAL_" + tenantId;
    String workflowCode = "WF_" + tenantId;
    LocalDate bizDate = LocalDate.of(2026, 10, 17);
    jdbcTemplate.update(
        "insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')",
        tenantId,
        tenantId);
    jdbcTemplate.update(
        "insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone)"
            + " values (?, ?, ?, 'UTC')",
        tenantId,
        calendarCode,
        calendarCode);
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        "insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name,"
            + " workflow_type, version, enabled, calendar_code)"
            + " values (?, ?, ?, 'DAG', 1, true, ?) returning id",
        Long.class,
        tenantId,
        workflowCode,
        workflowCode,
        calendarCode);
    jdbcTemplate.update(
        "insert into batch.workflow_run (tenant_id, workflow_definition_id, biz_date, run_status,"
            + " dry_run) values (?, ?, ?, 'SUCCESS', false)",
        tenantId,
        workflowDefinitionId,
        bizDate);
    jdbcTemplate.update(
        "insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status,"
            + " open_at, cutoff_at, late_count, catchup_count, timezone_snapshot,"
            + " dst_policy_snapshot, frozen, version, created_at, updated_at, dry_run)"
            + " values (?, ?, ?, 'CUTOFF', current_timestamp, current_timestamp - interval '1 second',"
            + " 0, 0, 'UTC', 'gap=RUN_AT_NEXT_VALID_TIME;overlap=RUN_ONCE_EARLIER_OFFSET',"
            + " false, 0, current_timestamp, current_timestamp, false)",
        tenantId,
        calendarCode,
        bizDate);

    String instanceA = "settle-mp-a-" + UUID.randomUUID().toString().replace("-", "");
    String instanceB = "settle-mp-b-" + UUID.randomUUID().toString().replace("-", "");
    Path configA = Files.createTempFile("batch-settle-a-", ".properties");
    Path configB = Files.createTempFile("batch-settle-b-", ".properties");
    Path logA = Files.createTempFile("batch-settle-a-", ".log");
    Path logB = Files.createTempFile("batch-settle-b-", ".log");
    Process processA = null;
    Process processB = null;
    try (Connection fence = jdbcTemplate.getDataSource().getConnection()) {
      fence.setAutoCommit(false);
      try (var statement = fence.prepareStatement(
          "select batch.acquire_batch_day_settlement_fence(?, ?, ?)")) {
        statement.setString(1, tenantId);
        statement.setString(2, calendarCode);
        statement.setObject(3, bizDate);
        statement.executeQuery().close();
      }
      int blockerPid;
      try (var statement = fence.createStatement();
          var result = statement.executeQuery("select pg_backend_pid()")) {
        assertThat(result.next()).isTrue();
        blockerPid = result.getInt(1);
      }

      writeProcessConfig(configA, instanceA);
      writeProcessConfig(configB, instanceB);
      processA = startSettlementProcess(configA, logA, tenantId, calendarCode, bizDate);
      awaitBlockedProcessCount(blockerPid, instanceA, 1);
      processB = startSettlementProcess(configB, logB, tenantId, calendarCode, bizDate);
      awaitBlockedProcessCount(blockerPid, instanceB, 1);
      awaitBlockedProcessCount(blockerPid, instanceA, 1);

      fence.commit();
      assertProcessSucceeded(processA, logA);
      assertProcessSucceeded(processB, logB);
    } finally {
      if (processA != null && processA.isAlive()) processA.destroyForcibly();
      if (processB != null && processB.isAlive()) processB.destroyForcibly();
      Files.deleteIfExists(configA);
      Files.deleteIfExists(configB);
      Files.deleteIfExists(logA);
      Files.deleteIfExists(logB);
    }

    assertThat(jdbcTemplate.queryForObject(
            "select day_status from batch.batch_day_instance where tenant_id = ?"
                + " and calendar_code = ? and biz_date = ?",
            String.class,
            tenantId,
            calendarCode,
            bizDate))
        .isEqualTo("SETTLED");
    assertThat(jdbcTemplate.queryForObject(
            "select version from batch.batch_day_instance where tenant_id = ?"
                + " and calendar_code = ? and biz_date = ?",
            Long.class,
            tenantId,
            calendarCode,
            bizDate))
        .isEqualTo(2L);
  }

  private void writeProcessConfig(Path path, String applicationName) throws Exception {
    Properties properties = new Properties();
    properties.setProperty(
        "spring.datasource.url",
        platformJdbcUrl() + "&ApplicationName=" + applicationName);
    properties.setProperty("spring.datasource.username", platformJdbcUsername());
    properties.setProperty("spring.datasource.password", platformJdbcPassword());
    properties.setProperty("spring.datasource.driver-class-name", "org.postgresql.Driver");
    properties.setProperty("spring.datasource.hikari.maximum-pool-size", "20");
    properties.setProperty("batch.datasource.business.url", businessJdbcUrl());
    properties.setProperty("batch.datasource.business.username", businessJdbcUsername());
    properties.setProperty("batch.datasource.business.password", businessJdbcPassword());
    properties.setProperty("batch.datasource.business.schema", "biz");
    properties.setProperty("spring.kafka.bootstrap-servers", kafkaBootstrapServers());
    properties.setProperty("batch.storage.backend", "s3");
    properties.setProperty("batch.storage.s3.endpoint", s3Endpoint());
    properties.setProperty("batch.storage.s3.access-key", s3AccessKey());
    properties.setProperty("batch.storage.s3.secret-key", s3SecretKey());
    properties.setProperty("batch.storage.s3.bucket", s3Bucket());
    properties.setProperty("batch.storage.s3.region", s3Region());
    properties.setProperty(
        "batch.storage.s3.path-style-enabled", Boolean.toString(s3PathStyleEnabled()));
    properties.setProperty("spring.data.redis.host", redisHost());
    properties.setProperty("spring.data.redis.port", Integer.toString(redisPort()));
    properties.setProperty("spring.quartz.scheduler-name", applicationName);
    properties.setProperty("batch.security.bypass-mode", "true");
    properties.setProperty("batch.security.kms.default-key-ref", "DEFAULT_TEST");
    properties.setProperty("batch.security.kms.keys.DEFAULT_TEST", "AAAAAAAAAAAAAAAAAAAAAA==");
    properties.setProperty("batch.sensor.enabled", "false");
    properties.setProperty("batch.batch-day.settle-scan-interval-millis", "3600000");
    properties.setProperty("batch.batch-day.open-scan-interval-millis", "3600000");
    properties.setProperty("batch.worker.drain.heartbeat-timeout-scheduler-enabled", "false");
    properties.setProperty("batch.partition-lease.reclaim-scheduler-enabled", "false");
    properties.setProperty("spring.flyway.enabled", "true");
    properties.setProperty("spring.flyway.default-schema", "batch");
    properties.setProperty("spring.flyway.schemas", "batch,quartz");
    try (OutputStream output = Files.newOutputStream(path)) {
      properties.store(output, "isolated BatchDaySettleScheduler JVM");
    }
  }

  private Process startSettlementProcess(
      Path config, Path log, String tenantId, String calendarCode, LocalDate bizDate)
      throws Exception {
    String classpath = System.getProperty(
        "surefire.test.class.path", System.getProperty("java.class.path"));
    assertThat(classpath).isNotBlank();
    return new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            classpath,
            BatchDaySettleChildProcess.class.getName(),
            config.toString(),
            tenantId,
            calendarCode,
            bizDate.toString())
        .redirectErrorStream(true)
        .redirectOutput(log.toFile())
        .start();
  }

  private void awaitBlockedProcessCount(int blockerPid, String applicationName, int count) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(90))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                "select count(*) from pg_stat_activity where application_name = ?"
                    + " and state = 'active' and wait_event_type = 'Lock'"
                    + " and ? = any(pg_blocking_pids(pid))",
                Integer.class,
                applicationName,
                blockerPid))
            .isGreaterThanOrEqualTo(count));
  }

  private void assertProcessSucceeded(Process process, Path log) throws Exception {
    assertThat(process.waitFor(90, TimeUnit.SECONDS))
        .as("child Orchestrator process completes; log: %s", Files.readString(log))
        .isTrue();
    assertThat(process.exitValue())
        .as("child Orchestrator process exits successfully; log: %s", Files.readString(log))
        .isZero();
  }
}
