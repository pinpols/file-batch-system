package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** 全局保留期脚本仅在本类独占的真实PG执行，避免影响共享Spring测试容器及其它任务数据。 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("等待原请求外键与保留期清理的真实PostgreSQL契约")
class WaitRequestRetentionIntegrationTest {
  @Container
  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.platform();

  enum Kind {
    JOB,
    WORKFLOW
  }

  private SingleConnectionDataSource dataSource;
  private JdbcTemplate jdbc;
  private String tenant;
  private String otherTenant;
  private Long definition;
  private Long parentId;
  private String rootRequestId;
  private static final LocalDate DATE = LocalDate.of(2026, 10, 18);

  @BeforeAll
  static void migrateFreshDatabase() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas("batch", "quartz")
        .defaultSchema("batch")
        .locations("classpath:db/migration")
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        .load()
        .migrate();
  }

  @BeforeEach
  void prepareOwnedParent() {
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar(tenant_id,calendar_code,calendar_name,timezone,enabled)
        values (?,'CAL','Retention','UTC',true)
        """;
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition(tenant_id,workflow_code,workflow_name,calendar_code)
        values (?,'RETENTION','Retention','CAL') returning id
        """;
    final String insertWorkflowRunSql = """
        insert into batch.workflow_run(tenant_id,workflow_definition_id,trigger_request_id,biz_date,run_status)
        values (?,?,?,?,'RUNNING') returning id
        """;
    final String updateTriggerRequestSql = """
        update batch.trigger_request set created_at=now(),updated_at=now() where id=?
    """;

    dataSource = new SingleConnectionDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    jdbc = new JdbcTemplate(dataSource);
    tenant = "wait-retain-" + UUID.randomUUID();
    otherTenant = "wait-other-" + UUID.randomUUID();
    jdbc.update(insertBusinessCalendarSql, tenant);
    definition = jdbc.queryForObject(insertWorkflowDefinitionSql, Long.class, tenant);
    rootRequestId = "root-" + UUID.randomUUID();
    long rootId = insertRequest(rootRequestId, "LAUNCHED", false);
    parentId = jdbc.queryForObject(insertWorkflowRunSql, Long.class, tenant, definition, rootId, DATE);
    // 根请求不参与本轮旧请求候选。
    jdbc.update(
        updateTriggerRequestSql, rootId);
  }

  @ParameterizedTest
  @EnumSource(Kind.class)
  @DisplayName("父请求在等待仍存在时不能被删除，包括终态等待审计")
  void shouldRestrictParentDeleteWhileWaitExists(Kind kind) {
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id=? and request_id=?
    """;

    String request = "fk-parent-" + UUID.randomUUID();
    insertRequest(request, "REJECTED", kind == Kind.JOB);
    insertWait(kind, tenant, request, kind == Kind.JOB ? "CANCELLED" : "REJECTED");
    assertForeignKeyFailure(() -> jdbc.update(
        deleteTriggerRequestSql, tenant, request));
    assertThat(count(waitTable(kind), "request_id", request)).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "foreign"})
  @DisplayName("Job等待原生复合外键拒绝缺失及异租户原请求")
  void shouldRejectMissingOrCrossTenantJobWaitAtDatabase(String scenario) {
    String request = "fk-invalid-" + UUID.randomUUID();
    if ("foreign".equals(scenario)) insertRequest(request, "REJECTED", true);
    String rowTenant = "foreign".equals(scenario) ? otherTenant : tenant;
    assertForeignKeyFailure(() -> insertWait(Kind.JOB, rowTenant, request, "WAITING"));
  }

  @Test
  @DisplayName("等待原请求外键均为已验证同租户关系且删除为NO ACTION")
  void shouldExposeValidatedCompositeNoActionWaitForeignKeys() {
    final String selectConrelidSql = """
        select conrelid::regclass::text as child,confdeltype::text as delete_action,convalidated,
               pg_get_constraintdef(oid) as definition
        from pg_constraint where contype='f' and confrelid='batch.trigger_request'::regclass
          and conrelid in ('batch.job_dependency_waiting_launch'::regclass,'batch.workflow_launch_wait'::regclass)
        order by conrelid
        """;

    List<Map<String, Object>> rows = jdbc.queryForList(selectConrelidSql);
    assertThat(rows).hasSize(2);
    for (Map<String, Object> row : rows) {
      assertThat(row).containsEntry("delete_action", "a").containsEntry("convalidated", true);
      assertThat(row.get("definition").toString()).contains("FOREIGN KEY (tenant_id, request_id)");
    }
  }

  @Test
  @DisplayName("计划Occurrence与每日预期记录保留同租户原请求，父删除必须先处理两类账本")
  void shouldProtectScheduledAndExpectedRequestReferences() {
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id=? and request_id=?
    """;

    String request = "ledger-parent-" + UUID.randomUUID();
    insertRequest(request, "LAUNCHED", false);
    insertExpectedWork(request);
    insertScheduledOccurrence(request);
    assertForeignKeyFailure(() -> jdbc.update(
        deleteTriggerRequestSql, tenant, request));
    assertThat(count("batch.expected_work_occurrence", "trigger_request_id", request))
        .isEqualTo(1);
    assertThat(count("batch.scheduled_occurrence", "request_id", request)).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"expected", "scheduled"})
  @DisplayName("计划及预期账本拒绝缺失的原请求")
  void shouldRejectOrphanScheduledOrExpectedRequestReference(String ledger) {
    String request = "ledger-missing-" + UUID.randomUUID();
    assertForeignKeyFailure(() -> {
      if ("expected".equals(ledger)) {
        insertExpectedWork(request);
      } else {
        insertScheduledOccurrence(request);
      }
    });
  }

  @Test
  @DisplayName("计划及预期请求外键均已验证且删除动作保持NO ACTION")
  void shouldExposeValidatedScheduledAndExpectedRequestForeignKeys() {
    final String selectConrelidSql = """
        select conrelid::regclass::text as child,confdeltype::text as delete_action,convalidated,
               pg_get_constraintdef(oid) as definition
        from pg_constraint
        where contype='f' and confrelid='batch.trigger_request'::regclass
          and conrelid in ('batch.expected_work_occurrence'::regclass,
                           'batch.scheduled_occurrence'::regclass)
        order by conrelid
        """;

    List<Map<String, Object>> rows = jdbc.queryForList(selectConrelidSql);
    assertThat(rows).hasSize(2);
    for (Map<String, Object> row : rows) {
      assertThat(row).containsEntry("delete_action", "a").containsEntry("convalidated", true);
      assertThat(row.get("definition").toString())
          .contains("FOREIGN KEY (tenant_id,")
          .contains("REFERENCES batch.trigger_request(tenant_id, request_id)");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"CREATED", "RUNNING", "PAUSED", "FAILED"})
  @DisplayName("历史拒绝仍关联保留的Workflow时，清理不得删除请求或取消等待证据")
  void shouldKeepRejectedWaitReferencedByRetainedParentWorkflow(String state) throws Exception {
    final String updateWorkflowRunSql = """
        update batch.workflow_run set run_status=? where id=?
    """;

    String request = "unpropagated-" + UUID.randomUUID();
    insertRequest(request, "REJECTED", true);
    insertWait(Kind.JOB, tenant, request, "CANCELLED");
    jdbc.update(updateWorkflowRunSql, state, parentId);
    assertSuccessfulCleanup("failure");
    assertThat(count("batch.trigger_request", "request_id", request)).isEqualTo(1);
    assertThat(count(waitTable(Kind.JOB), "request_id", request)).isEqualTo(1);
  }

  @ParameterizedTest
  @EnumSource(Kind.class)
  @DisplayName("等待未终结时，即使父请求为旧拒绝终态也必须保留")
  void shouldKeepActiveWaitEvenIfOriginalRequestIsTerminal(Kind kind) throws Exception {
    final String deleteWorkflowRunSql = """
        delete from batch.workflow_run where id=?
    """;

    String request = "active-wait-" + UUID.randomUUID();
    insertRequest(request, "REJECTED", kind == Kind.JOB);
    insertWait(kind, tenant, request, "WAITING");
    if (kind == Kind.JOB) jdbc.update(deleteWorkflowRunSql, parentId);
    assertSuccessfulCleanup("failure");
    assertThat(count("batch.trigger_request", "request_id", request)).isEqualTo(1);
    assertThat(count(waitTable(kind), "request_id", request)).isEqualTo(1);
  }

  @Test
  @DisplayName("FORWARD_FAILED仍可重试，不能按终态拒绝清理")
  void shouldKeepRecoverableForwardFailure() throws Exception {
    String request = "forward-retry-" + UUID.randomUUID();
    insertRequest(request, "FORWARD_FAILED", false);
    assertSuccessfulCleanup("failure");
    assertThat(count("batch.trigger_request", "request_id", request)).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "failure:JOB:CANCELLED",
        "failure:JOB:RELEASED",
        "failure:WORKFLOW:REJECTED",
        "failure:WORKFLOW:RESUMED",
        "success:JOB:RELEASED",
        "success:WORKFLOW:RESUMED"
      })
  @DisplayName("保留期届满且无其它引用时，显式先清终态等待再清父请求，不留孤儿")
  void shouldCleanEligibleTerminalWaitAndParentTogether(String scenario) throws Exception {
    final String deleteWorkflowRunSql = """
        delete from batch.workflow_run where id=?
    """;

    String[] parts = scenario.split(":");
    Kind kind = Kind.valueOf(parts[1]);
    String request = "eligible-" + UUID.randomUUID();
    insertRequest(
        request,
        "CANCELLED".equals(parts[2]) || "REJECTED".equals(parts[2]) ? "REJECTED" : "LAUNCHED",
        kind == Kind.JOB);
    insertWait(kind, tenant, request, parts[2]);
    if (kind == Kind.JOB) jdbc.update(deleteWorkflowRunSql, parentId);
    assertSuccessfulCleanup(parts[0]);
    assertThat(count("batch.trigger_request", "request_id", request)).isZero();
    assertThat(count(waitTable(kind), "request_id", request)).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"request", "wait"})
  @DisplayName("最近终结的请求或等待仍需完整审计窗口，不按首次创建年龄过早删除")
  void shouldKeepRecentTerminalAuditEvidence(String recent) throws Exception {
    String request = "recent-audit-" + UUID.randomUUID();
    insertRequest(request, "REJECTED", false);
    insertWait(Kind.WORKFLOW, tenant, request, "REJECTED");
    String updateRecentAuditEvidenceSql =
        "request".equals(recent)
            ? "update batch.trigger_request set updated_at=now() where tenant_id=? and request_id=?"
            : "update batch.workflow_launch_wait set updated_at=now() where tenant_id=? and request_id=?";
    jdbc.update(updateRecentAuditEvidenceSql, tenant, request);
    assertSuccessfulCleanup("failure");
    assertThat(count("batch.trigger_request", "request_id", request)).isEqualTo(1);
    assertThat(count(waitTable(Kind.WORKFLOW), "request_id", request)).isEqualTo(1);
  }

  @Test
  @DisplayName("冷Workflow仍保留父身份时保留被拒绝子请求的审计")
  void shouldKeepRejectedChildReferencedByColdWorkflow() throws Exception {
    final String insertWorkflowRunArchiveSql = """
        insert into archive.workflow_run_archive(id,tenant_id,workflow_definition_id,trigger_request_id,biz_date,run_status)
        select id,tenant_id,workflow_definition_id,trigger_request_id,biz_date,'FAILED' from batch.workflow_run\s\
        where id=?
        """;
    final String deleteWorkflowRunSql = """
        delete from batch.workflow_run where id=?
    """;

    String request = "cold-child-" + UUID.randomUUID();
    insertRequest(request, "REJECTED", true);
    insertWait(Kind.JOB, tenant, request, "CANCELLED");
    jdbc.update(insertWorkflowRunArchiveSql, parentId);
    jdbc.update(deleteWorkflowRunSql, parentId);
    assertSuccessfulCleanup("failure");
    assertThat(count("batch.trigger_request", "request_id", request)).isEqualTo(1);
    assertThat(count(waitTable(Kind.JOB), "request_id", request)).isEqualTo(1);
  }

  @ParameterizedTest
  @EnumSource(Kind.class)
  @DisplayName("父请求删除与原生等待插入并发时，插入等待行锁后因父已删除而失败，不提交孤儿")
  void shouldSerializeParentDeleteAndConcurrentWaitInsert(Kind kind) throws Exception {
    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id=? and request_id=?
    """;
    final String selectCoalesceSql = """
        select coalesce(?=any(pg_blocking_pids(?)),false)
    """;

    String request = "fk-race-" + UUID.randomUUID();
    insertRequest(request, "REJECTED", kind == Kind.JOB);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection parent = DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      parent.setAutoCommit(false);
      JdbcTemplate parentJdbc = new JdbcTemplate(new SingleConnectionDataSource(parent, true));
      Integer parentPid = parentJdbc.queryForObject(selectPgBackendPidSql, Integer.class);
      parentJdbc.update(
          deleteTriggerRequestSql, tenant, request);
      AtomicInteger childPid = new AtomicInteger();
      CountDownLatch childReady = new CountDownLatch(1);
      Future<?> child = executor.submit(() -> {
        try (Connection connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
          JdbcTemplate childJdbc =
              new JdbcTemplate(new SingleConnectionDataSource(connection, true));
          childPid.set(childJdbc.queryForObject(selectPgBackendPidSql, Integer.class));
          childReady.countDown();
          insertWait(childJdbc, kind, tenant, request, "WAITING");
          return null;
        }
      });
      try {
        assertThat(childReady.await(10, TimeUnit.SECONDS)).isTrue();
        Awaitility.await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> {
              Integer childBackendPid = childPid.get();
              Boolean parentBlockedByChild = jdbc.queryForObject(
                  selectCoalesceSql,
                  Boolean.class,
                  parentPid,
                  childBackendPid);
              assertThat(parentBlockedByChild).isTrue();
            });
        parent.commit();
        assertThatThrownBy(() -> child.get(10, TimeUnit.SECONDS))
            .rootCause()
            .isInstanceOfSatisfying(
                SQLException.class, ex -> assertThat(ex.getSQLState()).isEqualTo("23503"));
      } finally {
        parent.rollback();
      }
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(count(waitTable(kind), "request_id", request)).isZero();
  }

  @Test
  @DisplayName("请求清理批次有界且可重复推进，保留其它未到期原请求")
  void shouldBoundRequestCleanupAndContinueAcrossBatches() throws Exception {
    final String selectCountSql = """
        select count(*) from batch.trigger_request where tenant_id=? and request_id like 'batch-%'
    """;

    for (int index = 0; index < 3; index++)
      insertRequest("batch-" + index + "-" + UUID.randomUUID(), "REJECTED", false);
    for (int remaining = 2; remaining >= 0; remaining--) {
      ExecResult result =
          executeCleanup("failure", Map.of("trigger_request_cleanup_batch_size", "1"));
      assertThat(result.getExitCode()).as(result.getStderr()).isZero();
      Integer selectedTriggerRequestCount =
          jdbc.queryForObject(selectCountSql, Integer.class, tenant);
      assertThat(selectedTriggerRequestCount).isEqualTo(remaining);
    }
    assertThat(count("batch.trigger_request", "request_id", rootRequestId)).isEqualTo(1);
  }

  @Test
  @DisplayName("请求清理参数非法时，前序实例删除也回滚，不产生半批清理")
  void shouldRollbackEarlierDeletesWhenRetentionOptionsAreInvalid() throws Exception {
    final String selectCountSql = """
        select count(*) from batch.job_instance where id=?
    """;

    Long job = insertOldJob("FAILED");
    ExecResult result =
        executeCleanup("failure", Map.of("trigger_request_cleanup_batch_size", "0"));
    assertThat(result.getExitCode()).isEqualTo(3);
    assertThat(result.getStderr()).contains("invalid request retention");
    Integer selectedJobInstanceCount = jdbc.queryForObject(selectCountSql, Integer.class, job);
    assertThat(selectedJobInstanceCount).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"failure:hot", "failure:cold", "success:hot", "success:cold"})
  @DisplayName("直接实例清理发现冷热Workflow节点引用时在任何写入前拒绝，保留实例与血缘")
  void shouldRejectDirectInstancePurgeOfWorkflowLinkedJob(String scenario) throws Exception {
    final String insertWorkflowRunArchiveSql = """
          insert into archive.workflow_run_archive(id,tenant_id,workflow_definition_id,biz_date,run_status)
          select id,tenant_id,workflow_definition_id,biz_date,'FAILED' from batch.workflow_run where id=?
          """;
    final String insertWorkflowNodeRunArchiveSql = """
          insert into archive.workflow_node_run_archive(id,workflow_run_id,node_code,node_type,run_seq,node_status,
            retry_count,job_instance_id) values (?,?, 'JOB','JOB',1,'FAILED',0,?)
          """;
    final String insertWorkflowNodeRunSql = """
          insert into batch.workflow_node_run(workflow_run_id,node_code,node_type,run_seq,node_status,retry_count,job_instance_id)
          values (?,'JOB','JOB',1,'FAILED',0,?)
          """;
    final String selectCountSql = """
        select count(*) from batch.job_instance where id=?
    """;

    String[] parts = scenario.split(":");
    Long job = insertOldJob("success".equals(parts[0]) ? "SUCCESS" : "FAILED");
    if ("cold".equals(parts[1])) {
      jdbc.update(insertWorkflowRunArchiveSql, parentId);
      jdbc.update(insertWorkflowNodeRunArchiveSql, job + 1000000, parentId, job);
    } else {
      jdbc.update(insertWorkflowNodeRunSql, parentId, job);
    }
    ExecResult result = executeCleanup(parts[0], Map.of());
    assertThat(result.getExitCode()).isEqualTo(3);
    assertThat(result.getStderr()).contains("Workflow-linked instances require");
    Integer selectedJobInstanceCount = jdbc.queryForObject(selectCountSql, Integer.class, job);
    assertThat(selectedJobInstanceCount).isEqualTo(1);
  }

  @Test
  @DisplayName("SDK自有fixture清理包含内部子请求与两类等待，保留同租户其它Workflow和其它租户")
  void shouldCleanOnlyOwnedSdkWorkflowRequestsAndWaits() throws Exception {
    final String selectJobDefinitionIdSql = """
        select job_definition_id from batch.job_instance where id=?
    """;
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set workflow_code=?,single_job_definition_id=? where id=?
    """;
    final String updateJobInstanceSql = """
        update batch.job_instance set trigger_request_id=? where id=?
    """;
    final String updateTriggerRequestSql = """
        update batch.trigger_request set related_job_instance_id=? where id=?
    """;
    final String insertWorkflowNodeRunSql = """
        insert into batch.workflow_node_run(workflow_run_id,node_code,node_type,run_seq,node_status,retry_count,job_instance_id)
        values (?,'JOB','JOB',1,'FAILED',0,?)
        """;
    final String insertBusinessCalendarSql = """
          insert into batch.business_calendar(tenant_id,calendar_code,calendar_name,timezone,enabled)
          values (?,'CAL','Control','UTC',true)
          """;
    final String insertWorkflowDefinitionSql = """
          insert into batch.workflow_definition(tenant_id,workflow_code,workflow_name,calendar_code)
          values (?,'SDK_CONTROL','Control','CAL') returning id
          """;
    final String insertTriggerRequestSql = """
          insert into batch.trigger_request(tenant_id,request_id,trigger_type,workflow_definition_id,biz_date,dedup_key,request_status)
          values (?,?,'API',?,?,?,'PROCESSING') returning id
          """;
    final String insertWorkflowLaunchWaitSql = """
          insert into batch.workflow_launch_wait(tenant_id,request_id,workflow_definition_id,biz_date,launch_payload,wait_status)
          values (?,?,?,?,'{}','WAITING')
          """;
    final String selectCountSql = """
        select count(*) from batch.job_instance where id=?
    """;
    final String selectCountSql2 = """
        select count(*) from batch.workflow_run where id=?
    """;
    final String selectCountSql3 = """
        select count(*) from batch.workflow_definition where id=?
    """;
    final String selectCountSql4 = """
        select count(*) from batch.trigger_request where id=?
    """;
    final String selectCountSql5 = """
          select count(*) from batch.workflow_launch_wait waiting join batch.trigger_request request
          on request.tenant_id=waiting.tenant_id and request.request_id=waiting.request_id where request.id=?
          """;

    Long job = insertOldJob("FAILED");
    Long jobDefinition = jdbc.queryForObject(
        selectJobDefinitionIdSql, Long.class, job);
    jdbc.update(
        updateWorkflowDefinitionSql,
        "JOB-WF-" + jobDefinition,
        jobDefinition,
        definition);
    String child = "sdk-child-" + UUID.randomUUID();
    long childId = insertRequest(child, "REJECTED", true);
    jdbc.update(updateJobInstanceSql, childId, job);
    jdbc.update(
        updateTriggerRequestSql, job, childId);
    insertWait(Kind.JOB, tenant, child, "CANCELLED");
    insertWait(Kind.WORKFLOW, tenant, rootRequestId, "RESUMED");
    jdbc.update(insertWorkflowNodeRunSql, parentId, job);
    List<Long> controlRequests = new ArrayList<>();
    for (String controlTenant : List.of(tenant, otherTenant)) {
      if (!controlTenant.equals(tenant)) jdbc.update(insertBusinessCalendarSql, controlTenant);
      Long controlDefinition = jdbc.queryForObject(insertWorkflowDefinitionSql, Long.class, controlTenant);
      String request = "sdk-control-" + UUID.randomUUID();
      controlRequests.add(jdbc.queryForObject(
          insertTriggerRequestSql, Long.class, controlTenant, request, controlDefinition, DATE, request));
      jdbc.update(insertWorkflowLaunchWaitSql, controlTenant, request, controlDefinition, DATE);
    }
    Path script = Path.of(System.getProperty("user.dir"))
        .getParent()
        .resolve("scripts/lib/sql/cleanup-sdk-e2e.sql");
    POSTGRES.copyFileToContainer(MountableFile.forHostPath(script), "/tmp/cleanup-sdk-e2e.sql");
    ExecResult result = POSTGRES.execInContainer(
        "psql",
        "-X",
        "-v",
        "ON_ERROR_STOP=1",
        "-U",
        POSTGRES.getUsername(),
        "-d",
        POSTGRES.getDatabaseName(),
        "-v",
        "tenant_id=" + tenant,
        "-v",
        "job_code=WAIT_JOB",
        "-v",
        "queue_code=SDK_QUEUE",
        "-v",
        "worker_code=SDK_WORKER",
        "-f",
        "/tmp/cleanup-sdk-e2e.sql");
    assertThat(result.getExitCode()).as(result.getStdout() + result.getStderr()).isZero();
    assertThat(count("batch.trigger_request", "request_id", rootRequestId)).isZero();
    assertThat(count("batch.trigger_request", "request_id", child)).isZero();
    assertThat(count(waitTable(Kind.JOB), "request_id", child)).isZero();
    assertThat(count(waitTable(Kind.WORKFLOW), "request_id", rootRequestId)).isZero();
    Integer selectedJobInstanceCount = jdbc.queryForObject(selectCountSql, Integer.class, job);
    assertThat(selectedJobInstanceCount).isZero();
    Integer selectedWorkflowRunCount =
        jdbc.queryForObject(selectCountSql2, Integer.class, parentId);
    assertThat(selectedWorkflowRunCount).isZero();
    Integer selectedWorkflowDefinitionCount =
        jdbc.queryForObject(selectCountSql3, Integer.class, definition);
    assertThat(selectedWorkflowDefinitionCount).isZero();
    for (Long control : controlRequests) {
      Integer selectedTriggerRequestCount =
          jdbc.queryForObject(selectCountSql4, Integer.class, control);
      assertThat(selectedTriggerRequestCount).isEqualTo(1);
      Integer selectedWorkflowLaunchWaitCount =
          jdbc.queryForObject(selectCountSql5, Integer.class, control);
      assertThat(selectedWorkflowLaunchWaitCount).isEqualTo(1);
    }
  }

  private Long insertOldJob(String status) {
    final String insertJobDefinitionSql = """
        insert into batch.job_definition(tenant_id,job_code,job_name,job_type,schedule_type,timezone,calendar_code)
        values (?,'WAIT_JOB','Retention','GENERAL','MANUAL','UTC','CAL') returning id
        """;
    final String insertJobInstanceSql = """
        insert into batch.job_instance(tenant_id,job_definition_id,job_code,instance_no,biz_date,trigger_type,
          instance_status,dedup_key,created_at,finished_at)
        values (?,?,'WAIT_JOB',?,?,'MANUAL',?,?,now()-interval '40 days',now()-interval '40 days') returning id
        """;

    Long jobDefinition = jdbc.queryForObject(insertJobDefinitionSql, Long.class, tenant);
    return jdbc.queryForObject(
        insertJobInstanceSql,
        Long.class,
        tenant,
        jobDefinition,
        "old-job-" + UUID.randomUUID(),
        DATE,
        status,
        "old-job-" + UUID.randomUUID());
  }

  private void insertExpectedWork(String request) {
    final String insertExpectedWorkOccurrenceSql = """
        insert into batch.expected_work_occurrence(
          tenant_id,calendar_code,biz_date,workflow_definition_id,source_type,source_key,
          generation,occurrence_status,trigger_request_id)
        values (?, 'CAL', ?, ?, 'WORKFLOW', ?, 1, 'EXPECTED', ?)
        """;

    jdbc.update(insertExpectedWorkOccurrenceSql, tenant, DATE, definition, "expected-" + request, request);
  }

  private void insertScheduledOccurrence(String request) {
    final String insertScheduledOccurrenceSql = """
        insert into batch.scheduled_occurrence(
          tenant_id,workflow_definition_id,workflow_code,schedule_version,calendar_code,
          scheduled_fire_time,biz_date,occurrence_status,request_id)
        values (?, ?, 'RETENTION', 1, 'CAL', now(), ?, 'REQUESTED', ?)
        """;

    jdbc.update(insertScheduledOccurrenceSql, tenant, definition, DATE, request);
  }

  private long insertRequest(String request, String status, boolean child) {
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request(tenant_id,request_id,trigger_type,job_code,workflow_definition_id,
          parent_workflow_run_id,parent_node_code,biz_date,dedup_key,request_status,created_at,updated_at)
        values (?,?,'API',?,?,?, ?,?,?,?,now()-interval '40 days',now()-interval '40 days') returning id
        """;

    return jdbc.queryForObject(
        insertTriggerRequestSql,
        Long.class,
        tenant,
        request,
        child ? "WAIT_JOB" : null,
        child ? null : definition,
        child ? parentId : null,
        child ? "JOB" : null,
        DATE,
        request,
        status);
  }

  private void insertWait(Kind kind, String rowTenant, String request, String status) {
    insertWait(jdbc, kind, rowTenant, request, status);
  }

  private void insertWait(
      JdbcTemplate target, Kind kind, String rowTenant, String request, String status) {
    final String insertJobDependencyWaitingLaunchSql = """
          insert into batch.job_dependency_waiting_launch(tenant_id,request_id,job_code,biz_date,
            dependency_snapshot,launch_payload,wait_status,created_at,updated_at)
          values (?,?,'WAIT_JOB',?,'[]','{}',?,now()-interval '40 days',now()-interval '40 days')
          """;
    final String insertWorkflowLaunchWaitSql = """
          insert into batch.workflow_launch_wait(tenant_id,request_id,workflow_definition_id,biz_date,
            launch_payload,wait_status,created_at,updated_at)
          values (?,?,?,?,'{}',?,now()-interval '40 days',now()-interval '40 days')
          """;

    if (kind == Kind.JOB) {
      target.update(insertJobDependencyWaitingLaunchSql, rowTenant, request, DATE, status);
    } else {
      target.update(insertWorkflowLaunchWaitSql, rowTenant, request, definition, DATE, status);
    }
  }

  private void assertSuccessfulCleanup(String mode) throws Exception {
    ExecResult result = executeCleanup(mode, Map.of());
    assertThat(result.getExitCode()).as(result.getStdout() + result.getStderr()).isZero();
  }

  private ExecResult executeCleanup(String mode, Map<String, String> variables) throws Exception {
    Path repository = Path.of(System.getProperty("user.dir")).getParent();
    String file = "success".equals(mode)
        ? "cleanup-success-instances.sql"
        : "cleanup-historical-failures.sql";
    POSTGRES.copyFileToContainer(
        MountableFile.forHostPath(repository.resolve("scripts/db/" + file)), "/tmp/" + file);
    POSTGRES.copyFileToContainer(
        MountableFile.forHostPath(
            repository.resolve("scripts/db/cleanup-trigger-request-retention.sql")),
        "/tmp/cleanup-trigger-request-retention.sql");
    List<String> command = new ArrayList<>(List.of(
        "psql",
        "-X",
        "-v",
        "ON_ERROR_STOP=1",
        "-U",
        POSTGRES.getUsername(),
        "-d",
        POSTGRES.getDatabaseName()));
    variables.forEach((key, value) -> {
      command.add("-v");
      command.add(key + "=" + value);
    });
    command.addAll(List.of("-f", "/tmp/" + file));
    return POSTGRES.execInContainer(command.toArray(String[]::new));
  }

  private static String waitTable(Kind kind) {
    return kind == Kind.JOB ? "batch.job_dependency_waiting_launch" : "batch.workflow_launch_wait";
  }

  private int count(String table, String column, String value) {
    final String selectCountSql = """
        select count(*) from %s where %s=?\
        """.formatted(table, column);
    return jdbc.queryForObject(
        selectCountSql, Integer.class, value);
  }

  private void assertForeignKeyFailure(Runnable action) {
    assertThatThrownBy(action::run)
        .rootCause()
        .isInstanceOfSatisfying(
            SQLException.class, ex -> assertThat(ex.getSQLState()).isEqualTo("23503"));
  }

  @AfterEach
  void removeOwnedRows() {
    final String deleteExpectedWorkOccurrenceSql = """
        delete from batch.expected_work_occurrence where tenant_id=?
    """;
    final String deleteScheduledOccurrenceSql = """
        delete from batch.scheduled_occurrence where tenant_id=?
    """;
    final String deleteJobDependencyWaitingLaunchSql = """
        delete from batch.job_dependency_waiting_launch where tenant_id=?
    """;
    final String deleteWorkflowLaunchWaitSql = """
        delete from batch.workflow_launch_wait where tenant_id=?
    """;
    final String deleteWorkflowNodeRunArchiveSql = """
        delete from archive.workflow_node_run_archive where workflow_run_id in (select id\s\
        from archive.workflow_run_archive where tenant_id=?)
    """;
    final String deleteWorkflowRunArchiveSql = """
        delete from archive.workflow_run_archive where tenant_id=?
    """;
    final String deleteWorkflowNodeRunSql = """
        delete from batch.workflow_node_run where workflow_run_id in (select id from batch.workflow_run\s\
        where tenant_id=?)
    """;
    final String deleteJobInstanceSql = """
        delete from batch.job_instance where tenant_id=?
    """;
    final String deleteWorkflowRunSql = """
        delete from batch.workflow_run where tenant_id=?
    """;
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id=?
    """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where tenant_id=?
    """;
    final String deleteJobDefinitionSql = """
        delete from batch.job_definition where tenant_id=?
    """;
    final String deleteBatchDayCatchupControlSql = """
        delete from batch.batch_day_catchup_control where tenant_id=?
    """;
    final String deleteBusinessCalendarSql = """
        delete from batch.business_calendar where tenant_id=?
    """;

    for (String scoped : List.of(tenant, otherTenant)) {
      jdbc.update(deleteExpectedWorkOccurrenceSql, scoped);
      jdbc.update(deleteScheduledOccurrenceSql, scoped);
      jdbc.update(deleteJobDependencyWaitingLaunchSql, scoped);
      jdbc.update(deleteWorkflowLaunchWaitSql, scoped);
      jdbc.update(
          deleteWorkflowNodeRunArchiveSql,
          scoped);
      jdbc.update(deleteWorkflowRunArchiveSql, scoped);
      jdbc.update(
          deleteWorkflowNodeRunSql,
          scoped);
      jdbc.update(deleteJobInstanceSql, scoped);
      jdbc.update(deleteWorkflowRunSql, scoped);
      jdbc.update(deleteTriggerRequestSql, scoped);
      jdbc.update(deleteWorkflowDefinitionSql, scoped);
      jdbc.update(deleteJobDefinitionSql, scoped);
      jdbc.update(deleteBatchDayCatchupControlSql, scoped);
      jdbc.update(deleteBusinessCalendarSql, scoped);
    }
    dataSource.destroy();
  }
}
