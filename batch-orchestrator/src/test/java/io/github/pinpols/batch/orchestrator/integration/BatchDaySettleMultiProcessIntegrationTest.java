package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** R33 证据：同一 Job 的输出版本在生产冷归档后仍须保留准确血缘。 */
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
  @DisplayName("四个独立 JVM 同时结算时只推进一次终态版本")
  void shouldSettleOnce_whenIndependentProcessesRaceOnSameBusinessDay() throws Exception {
    final String selectAcquireBatchDaySettlementFenceSql = """
        select batch.acquire_batch_day_settlement_fence(?, ?, ?)
    """;
    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;

    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')
    """;
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone)\s\
        values (?, ?, ?, 'UTC')\
        """;
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type, version,\s\
        enabled, calendar_code) values (?, ?, ?, 'DAG', 1, true, ?) returning id\
        """;
    final String insertWorkflowRunSql = """
        insert into batch.workflow_run (tenant_id, workflow_definition_id, biz_date, run_status, dry_run)\s\
        values (?, ?, ?, 'SUCCESS', false)\
        """;
    final String insertBatchDayInstanceSql = """
        insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at,\s\
        cutoff_at, late_count, catchup_count, timezone_snapshot, dst_policy_snapshot, frozen, version,\s\
        created_at, updated_at, dry_run)\s\
        values (?, ?, ?, 'CUTOFF', current_timestamp, current_timestamp - interval '1 second', 0, 0, 'UTC',\s\
        'gap=RUN_AT_NEXT_VALID_TIME;overlap=RUN_ONCE_EARLIER_OFFSET', false, 0, current_timestamp,\s\
        current_timestamp, false)\
        """;
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;
    final String selectVersionSql = """
        select version from batch.batch_day_instance where tenant_id = ? and calendar_code = ? and biz_date = ?\
        """;

    String tenantId = "settle-process-" + UUID.randomUUID().toString().replace("-", "");
    String calendarCode = "CAL_" + tenantId;
    String workflowCode = "WF_" + tenantId;
    LocalDate bizDate = LocalDate.of(2026, 10, 17);
    jdbcTemplate.update(insertTenantSql, tenantId, tenantId);
    jdbcTemplate.update(insertBusinessCalendarSql, tenantId, calendarCode, calendarCode);
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql,
        Long.class,
        tenantId,
        workflowCode,
        workflowCode,
        calendarCode);
    jdbcTemplate.update(insertWorkflowRunSql, tenantId, workflowDefinitionId, bizDate);
    jdbcTemplate.update(insertBatchDayInstanceSql, tenantId, calendarCode, bizDate);

    int contenderCount = 4;
    String[] instanceNames = new String[contenderCount];
    Path[] configs = new Path[contenderCount];
    Path[] logs = new Path[contenderCount];
    Process[] processes = new Process[contenderCount];
    for (int index = 0; index < contenderCount; index++) {
      String suffix = UUID.randomUUID().toString().replace("-", "");
      instanceNames[index] = "settle-mp-" + suffix;
      configs[index] = Files.createTempFile("batch-settle-" + index + "-", ".properties");
      logs[index] = Files.createTempFile("batch-settle-" + index + "-", ".log");
    }
    try (Connection fence = jdbcTemplate.getDataSource().getConnection()) {
      fence.setAutoCommit(false);
      try (var statement = fence.prepareStatement(selectAcquireBatchDaySettlementFenceSql)) {
        statement.setString(1, tenantId);
        statement.setString(2, calendarCode);
        statement.setObject(3, bizDate);
        statement.executeQuery().close();
      }
      int blockerPid;
      try (var statement = fence.createStatement();
          var result = statement.executeQuery(selectPgBackendPidSql)) {
        assertThat(result.next()).isTrue();
        blockerPid = result.getInt(1);
      }

      for (int index = 0; index < contenderCount; index++) {
        writeProcessConfig(configs[index], instanceNames[index]);
        processes[index] =
            startSettlementProcess(configs[index], logs[index], tenantId, calendarCode, bizDate);
        awaitBlockedProcessCount(blockerPid, instanceNames[index], 1);
      }

      fence.commit();
      for (int index = 0; index < contenderCount; index++) {
        assertProcessSucceeded(processes[index], logs[index]);
      }
    } finally {
      for (int index = 0; index < contenderCount; index++) {
        if (processes[index] != null && processes[index].isAlive()) {
          processes[index].destroyForcibly();
        }
        Files.deleteIfExists(configs[index]);
        Files.deleteIfExists(logs[index]);
      }
    }

    String selectedDayStatus = jdbcTemplate.queryForObject(
        selectDayStatusSql, String.class, tenantId, calendarCode, bizDate);
    assertThat(selectedDayStatus).isEqualTo("SETTLED");
    Long selectedVersion =
        jdbcTemplate.queryForObject(selectVersionSql, Long.class, tenantId, calendarCode, bizDate);
    assertThat(selectedVersion).isEqualTo(2L);
  }

  @Test
  @DisplayName("多实例混合接纳、人工释放与结算并发时不丢失已接纳义务")
  void shouldPreserveAcceptedWorkDuringMixedAdmissionDispositionAndSettlementContention()
      throws Exception {
    final String insertTenantSql = """
            insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')
        """;
    final String insertBusinessCalendarSql = """
            insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone)\s\
            values (?, ?, ?, 'UTC')\
            """;
    final String insertWorkflowDefinitionSql = """
            insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type,\s\
            version, enabled, calendar_code) values (?, ?, ?, 'DAG', 1, true, ?) returning id\
            """;
    final String insertWorkflowRunSql = """
            insert into batch.workflow_run (tenant_id, workflow_definition_id, biz_date, run_status, dry_run)\s\
            values (?, ?, ?, 'SUCCESS', false)\
            """;
    final String insertBatchDayInstanceSql = """
        insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at,\s\
        cutoff_at, late_count, catchup_count, timezone_snapshot, dst_policy_snapshot, frozen, version,\s\
        created_at, updated_at, dry_run)\s\
        values (?, ?, ?, 'CUTOFF', current_timestamp, current_timestamp - interval '1 second', 0, 0, 'UTC',\s\
        'gap=RUN_AT_NEXT_VALID_TIME;overlap=RUN_ONCE_EARLIER_OFFSET', false, 0, current_timestamp,\s\
        current_timestamp, false)\
        """;
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;
    final String selectCountSql = """
        select count(*) from batch.trigger_request where tenant_id = ? and workflow_definition_id = ?\s\
        and biz_date = ?\
        """;

    String tenantId = "mixed-r11-" + UUID.randomUUID().toString().replace("-", "");
    String calendarCode = "CAL_" + tenantId;
    String workflowCode = "WF_" + tenantId;
    int dayCount = 9;
    LocalDate firstBizDate = LocalDate.of(2026, 10, 1);
    jdbcTemplate.update(insertTenantSql, tenantId, tenantId);
    jdbcTemplate.update(insertBusinessCalendarSql, tenantId, calendarCode, calendarCode);
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql,
        Long.class,
        tenantId,
        workflowCode,
        workflowCode,
        calendarCode);
    List<LocalDate> bizDates = new ArrayList<>();
    for (int index = 0; index < dayCount; index++) {
      LocalDate bizDate = firstBizDate.plusDays(index);
      bizDates.add(bizDate);
      jdbcTemplate.update(insertWorkflowRunSql, tenantId, workflowDefinitionId, bizDate);
      jdbcTemplate.update(insertBatchDayInstanceSql, tenantId, calendarCode, bizDate);
    }

    int settlementProcessCount = 2;
    int processCount = settlementProcessCount + 1;
    String[] instanceNames = new String[processCount];
    Path[] configs = new Path[processCount];
    Path[] logs = new Path[processCount];
    Process[] processes = new Process[processCount];
    List<Future<String>> admissions = new ArrayList<>();
    try (Connection blocker = jdbcTemplate.getDataSource().getConnection();
        ExecutorService admissionExecutor = Executors.newFixedThreadPool(dayCount)) {
      blocker.setAutoCommit(false);
      for (LocalDate bizDate : bizDates) {
        acquireSettlementFence(blocker, tenantId, calendarCode, bizDate);
      }
      int blockerPid = backendPid(blocker);

      for (int index = 0; index < processCount; index++) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        instanceNames[index] = "mixed-r11-" + suffix;
        configs[index] = Files.createTempFile("batch-mixed-r11-" + index + "-", ".properties");
        logs[index] = Files.createTempFile("batch-mixed-r11-" + index + "-", ".log");
        writeProcessConfig(configs[index], instanceNames[index]);
        if (index < settlementProcessCount) {
          processes[index] = startSettlementProcess(
              configs[index], logs[index], tenantId, calendarCode, firstBizDate);
        }
      }

      String operationPlan = bizDates.stream()
          .map(date -> date + ":"
              + switch (date.getDayOfMonth() % 3) {
                case 1 -> "SKIP";
                case 2 -> "CLOSE";
                default -> "RELEASE";
              })
          .collect(Collectors.joining(","));
      processes[settlementProcessCount] = startOperationProcess(
          configs[settlementProcessCount],
          logs[settlementProcessCount],
          tenantId,
          calendarCode,
          operationPlan);

      for (LocalDate bizDate : bizDates) {
        admissions.add(admissionExecutor.submit(
            () -> tryInsertWorkflowRequest(tenantId, workflowDefinitionId, bizDate)));
      }
      awaitBlockedConnectionCount(blockerPid, dayCount + processCount);
      blocker.commit();

      for (int index = 0; index < processCount; index++) {
        assertProcessSucceeded(processes[index], logs[index]);
      }
      for (int index = 0; index < dayCount; index++) {
        String admissionResult = admissions.get(index).get(30, TimeUnit.SECONDS);
        assertThat(admissionResult).isIn("ACCEPTED", "CLOSED_DAY_REJECTED");
        LocalDate bizDate = bizDates.get(index);
        String finalStatus = jdbcTemplate.queryForObject(
            selectDayStatusSql, String.class, tenantId, calendarCode, bizDate);
        Integer requestCount = jdbcTemplate.queryForObject(
            selectCountSql, Integer.class, tenantId, workflowDefinitionId, bizDate);
        Integer unlaunchedCount = triggerRequestCount(tenantId, calendarCode, bizDate);
        if ("ACCEPTED".equals(admissionResult)) {
          assertThat(requestCount).isOne();
          assertThat(unlaunchedCount).isOne();
          assertThat(finalStatus).isIn("CUTOFF", "IN_FLIGHT");
        } else {
          assertThat(requestCount).isZero();
          assertThat(unlaunchedCount).isZero();
          assertThat(finalStatus).isIn("SETTLED", "SKIPPED", "MANUAL_RELEASED");
        }
      }
    } finally {
      for (Process process : processes) {
        if (process != null && process.isAlive()) {
          process.destroyForcibly();
        }
      }
      for (int index = 0; index < processCount; index++) {
        if (configs[index] != null) {
          Files.deleteIfExists(configs[index]);
        }
        if (logs[index] != null) {
          Files.deleteIfExists(logs[index]);
        }
      }
    }
  }

  private void writeProcessConfig(Path path, String applicationName) throws Exception {
    Properties properties = new Properties();
    properties.setProperty(
        "spring.datasource.url", platformJdbcUrl() + "&ApplicationName=" + applicationName);
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
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
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

  private Process startOperationProcess(
      Path config, Path log, String tenantId, String calendarCode, String operationPlan)
      throws Exception {
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    assertThat(classpath).isNotBlank();
    return new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            classpath,
            BatchDayOperationChildProcess.class.getName(),
            config.toString(),
            tenantId,
            calendarCode,
            operationPlan)
        .redirectErrorStream(true)
        .redirectOutput(log.toFile())
        .start();
  }

  private String tryInsertWorkflowRequest(
      String tenantId, Long workflowDefinitionId, LocalDate bizDate) throws Exception {
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (tenant_id, request_id, trigger_type, workflow_definition_id,\s\
        biz_date, dedup_key, request_status) values (?, ?, 'EVENT', ?, ?, ?, 'ACCEPTED')\
        """;

    try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
      connection.setAutoCommit(false);
      try (var statement = connection.prepareStatement(insertTriggerRequestSql)) {
        statement.setString(1, tenantId);
        statement.setString(2, "r11-mixed-" + UUID.randomUUID().toString().replace("-", ""));
        statement.setLong(3, workflowDefinitionId);
        statement.setObject(4, bizDate);
        statement.setString(5, "r11-mixed-dedup-" + UUID.randomUUID().toString().replace("-", ""));
        statement.executeUpdate();
        connection.commit();
        return "ACCEPTED";
      } catch (SQLException failure) {
        connection.rollback();
        boolean isClosedBatchDayConstraintFailure = "23514".equals(failure.getSQLState())
            && failure.getMessage() != null
            && failure.getMessage().contains("batch day ")
            && failure.getMessage().contains(" is closed");
        if (isClosedBatchDayConstraintFailure) {
          return "CLOSED_DAY_REJECTED";
        }
        return "SQL_ERROR:" + failure.getSQLState() + ":" + failure.getMessage();
      }
    }
  }

  private int triggerRequestCount(String tenantId, String calendarCode, LocalDate bizDate) {
    final String selectCountSql = """
        select count(*) from batch.trigger_request request\s\
        join batch.workflow_definition workflow on workflow.tenant_id = request.tenant_id\s\
        and workflow.id = request.workflow_definition_id where request.tenant_id = ?\s\
        and workflow.calendar_code = ? and request.biz_date = ?\s\
        and request.request_status in ('PENDING', 'PROCESSING', 'ACCEPTED', 'WAITING')\
        """;

    return jdbcTemplate.queryForObject(
        selectCountSql, Integer.class, tenantId, calendarCode, bizDate);
  }

  private void acquireSettlementFence(
      Connection connection, String tenantId, String calendarCode, LocalDate bizDate)
      throws Exception {
    final String selectAcquireBatchDaySettlementFenceSql = """
        select batch.acquire_batch_day_settlement_fence(?, ?, ?)
    """;

    try (var statement = connection.prepareStatement(selectAcquireBatchDaySettlementFenceSql)) {
      statement.setString(1, tenantId);
      statement.setString(2, calendarCode);
      statement.setObject(3, bizDate);
      statement.executeQuery().close();
    }
  }

  private int backendPid(Connection connection) throws Exception {
    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;

    try (var statement = connection.createStatement();
        var result = statement.executeQuery(selectPgBackendPidSql)) {
      assertThat(result.next()).isTrue();
      return result.getInt(1);
    }
  }

  private void awaitBlockedConnectionCount(int blockerPid, int expectedCount) {
    final String selectCountSql = """
        select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))
    """;

    Awaitility.await()
        .atMost(Duration.ofSeconds(90))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          Integer selectedPgStatActivityCount =
              jdbcTemplate.queryForObject(selectCountSql, Integer.class, blockerPid);
          assertThat(selectedPgStatActivityCount)
              .as("admissions and independent Orchestrator processes must reach the fence")
              .isGreaterThanOrEqualTo(expectedCount);
        });
  }

  private void awaitBlockedProcessCount(int blockerPid, String applicationName, int count) {
    final String selectCountSql = """
        select count(*) from pg_stat_activity where application_name = ? and state = 'active'\s\
        and wait_event_type = 'Lock' and ? = any(pg_blocking_pids(pid))\
        """;

    Awaitility.await()
        .atMost(Duration.ofSeconds(90))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          Integer selectedPgStatActivityCount = jdbcTemplate.queryForObject(
              selectCountSql, Integer.class, applicationName, blockerPid);
          assertThat(selectedPgStatActivityCount).isGreaterThanOrEqualTo(count);
        });
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
