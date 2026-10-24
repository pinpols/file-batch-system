package io.github.pinpols.batch.orchestrator.infrastructure.file;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.BatchDaySettleScheduler;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowLaunchSeed;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "batch.file-governance.arrival.enabled=true")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DisplayName("文件到达组并发触发集成:真实 PostgreSQL 与 Workflow 根入口下重复扫描只创建一个束实例")
class BundleArrivalLauncherConcurrencyIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 1, 15);

  private final BundleArrivalLauncher launcher;
  private final BatchDaySettleScheduler batchDaySettleScheduler;
  private final FileGovernanceScheduler fileGovernanceScheduler;
  private final MeterRegistry meterRegistry;
  private final JdbcTemplate jdbcTemplate;

  BundleArrivalLauncherConcurrencyIntegrationTest(
      BundleArrivalLauncher launcher,
      BatchDaySettleScheduler batchDaySettleScheduler,
      FileGovernanceScheduler fileGovernanceScheduler,
      MeterRegistry meterRegistry,
      JdbcTemplate jdbcTemplate) {
    this.launcher = launcher;
    this.batchDaySettleScheduler = batchDaySettleScheduler;
    this.fileGovernanceScheduler = fileGovernanceScheduler;
    this.meterRegistry = meterRegistry;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Test
  @DisplayName("两个扫描线程同时触发同一完整文件束时只落一个实例和一组分区")
  void shouldLaunchOnce_whenConcurrentArrivalScansRace() throws Exception {
  final String selectCountSql = """
      select count(*) from batch.trigger_request where tenant_id = ? and request_id = ?
  """;
  final String selectWorkflowDefinitionIdSql = """
      select workflow_definition_id from batch.trigger_request where tenant_id = ? and request_id = ?\
      """;
  final String selectCountSql2 = """
          select count(*)
          from batch.workflow_node_run node_run
          join batch.workflow_run run
            on run.id = node_run.workflow_run_id
          where run.tenant_id = ?
            and run.trigger_request_id = (
              select id from batch.trigger_request where tenant_id = ? and request_id = ?
            )
            and node_run.job_instance_id is not null
          """;
  final String selectCountSql3 = """
          select count(*)
          from batch.job_partition p
          join batch.job_instance i
            on i.tenant_id = p.tenant_id and i.id = p.job_instance_id
          join batch.workflow_node_run node_run
            on node_run.job_instance_id = i.id
          join batch.workflow_run run
            on run.id = node_run.workflow_run_id
          where run.tenant_id = ?
            and run.trigger_request_id = (
              select id from batch.trigger_request where tenant_id = ? and request_id = ?
            )
          """;
  final String selectCountSql4 = """
          select count(*)
          from batch.outbox_event e
          where e.tenant_id = ?
            and e.event_key in (
              select ? || ':dispatch:' || t.id
              from batch.job_task t
              join batch.job_instance i
                on i.tenant_id = t.tenant_id and i.id = t.job_instance_id
              join batch.workflow_node_run node_run
                on node_run.job_instance_id = i.id
              join batch.workflow_run run
                on run.id = node_run.workflow_run_id
              where run.tenant_id = ?
                and run.trigger_request_id = (
                  select id from batch.trigger_request where tenant_id = ? and request_id = ?
                )
            )
          """;
  final String selectCountSql5 = """
      select count(*) from batch.expected_work_occurrence where tenant_id = ? and calendar_code = 'IT_CALENDAR'\s\
      and workflow_definition_id = ? and source_type = 'FILE_GROUP' and source_key = ? and biz_date = ?\s\
      and occurrence_status = 'SATISFIED' and trigger_request_id = ?\
      """;
  final String selectCountSql6 = """
          select count(*) from batch.workflow_run run
          join batch.trigger_request request
            on request.tenant_id = run.tenant_id and request.id = run.trigger_request_id
          where request.tenant_id = ? and request.request_id = ?
            and request.workflow_definition_id is not null and request.job_code is null
          """;

    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareBundleLaunchWithWorker(
        jdbcTemplate, TENANT, "BUNDLE_IMPORT", "IMPORT");
    String workflowCode = "WF-" + seed.jobCode();
    String fileGroupCode = "arrival-race-" + Long.toUnsignedString(System.nanoTime());
    long orderFileId = insertArrivalFile(fileGroupCode, "orders.csv", workflowCode, "TPL_ORDER");
    long customerFileId =
        insertArrivalFile(fileGroupCode, "customers.csv", workflowCode, "TPL_CUSTOMER");
    List<FileGovernanceArrivalViews.ArrivalCandidateView> candidates = List.of(
        candidate(orderFileId, "orders.csv", fileGroupCode, workflowCode, "TPL_ORDER"),
        candidate(customerFileId, "customers.csv", fileGroupCode, workflowCode, "TPL_CUSTOMER"));
    String requestId =
        bundleRequestId(TENANT, seed.workflowDefinitionId(), fileGroupCode, BIZ_DATE);

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<BundleArrivalLauncher.LaunchOutcome>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        results.add(pool.submit(() -> {
          ready.countDown();
          if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent start gate timed out");
          }
          return launcher.launchIfBundle(TENANT, fileGroupCode, candidates);
        }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS))
          .as("both callers reached the start gate")
          .isTrue();
      start.countDown();
      assertThat(results.get(0).get(60, TimeUnit.SECONDS))
          .isEqualTo(BundleArrivalLauncher.LaunchOutcome.LAUNCHED);
      assertThat(results.get(1).get(60, TimeUnit.SECONDS))
          .isEqualTo(BundleArrivalLauncher.LaunchOutcome.LAUNCHED);

      Integer triggerRows = jdbcTemplate.queryForObject(
          selectCountSql,
          Integer.class,
          TENANT,
          requestId);
      Long workflowDefinitionId = jdbcTemplate.queryForObject(
          selectWorkflowDefinitionIdSql,
          Long.class,
          TENANT,
          requestId);
      Integer instanceRows =
      jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, TENANT, requestId);
      Integer partitionRows =
      jdbcTemplate.queryForObject(selectCountSql3, Integer.class, TENANT, TENANT, requestId);
      Integer dispatchEvents = jdbcTemplate.queryForObject(
          selectCountSql4, Integer.class, TENANT, TENANT, TENANT, TENANT, requestId);
      Integer expectedWorkRows = jdbcTemplate.queryForObject(
          selectCountSql5,
          Integer.class,
          TENANT,
          workflowDefinitionId,
          fileGroupCode,
          BIZ_DATE,
          requestId);

      assertThat(triggerRows).isEqualTo(1);
      Integer workflowRootRows = jdbcTemplate.queryForObject(selectCountSql6, Integer.class, TENANT, requestId);
      assertThat(workflowRootRows).as("文件束请求由一个显式 Workflow 根运行接纳").isEqualTo(1);
      assertThat(instanceRows).as("同租户与确定性请求键只创建一个作业实例").isEqualTo(1);
      assertThat(partitionRows).as("唯一实例包含本束声明的两个文件分区").isEqualTo(2);
      assertThat(dispatchEvents).as("唯一实例的每个任务恰有一条 dispatch outbox 事件").isEqualTo(2);
      assertThat(expectedWorkRows).as("重复扫描应以同一来源身份登记一次，并在请求持久化后结清预期项").isEqualTo(1);
    } finally {
      start.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("同一文件组编码在不同业务日形成独立请求和预期项")
  void shouldKeepSameFileGroupIndependent_whenReusedAcrossBusinessDates() {
  final String selectBizDateSql = """
      select biz_date from batch.trigger_request where tenant_id = ? and request_id in (?, ?) order by biz_date\
      """;
  final String selectBizDateSql2 = """
      select run.biz_date from batch.workflow_run run\s\
      join batch.trigger_request request on request.tenant_id = run.tenant_id\s\
      and request.id = run.trigger_request_id where run.tenant_id = ? and request.request_id in (?, ?)\s\
      order by run.biz_date\
      """;
  final String selectBizDateSql3 = """
      select biz_date from batch.expected_work_occurrence where tenant_id = ? and calendar_code = 'IT_CALENDAR'\s\
      and workflow_definition_id = ? and source_type = 'FILE_GROUP' and source_key = ?\s\
      and occurrence_status = 'SATISFIED' order by biz_date\
      """;
    final String selectCountSql = """
        select count(*) from batch.expected_work_occurrence where tenant_id = ?\s\
        and calendar_code = 'IT_CALENDAR' and workflow_definition_id = ? and source_type = 'FILE_GROUP'\s\
        and source_key = ?\
        """;

    String tenantId = uniqueTenant();
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareBundleLaunchWithWorker(
        jdbcTemplate, tenantId, "IMPORT", "IMPORT");
    String fileGroupCode = "reused-across-days-" + Long.toUnsignedString(System.nanoTime());
    String workflowCode = "WF-" + seed.jobCode();
    LocalDate firstDate = BIZ_DATE.plusDays(1);
    LocalDate secondDate = BIZ_DATE.plusDays(2);
    String firstRequestId =
        bundleRequestId(tenantId, seed.workflowDefinitionId(), fileGroupCode, firstDate);
    String secondRequestId =
        bundleRequestId(tenantId, seed.workflowDefinitionId(), fileGroupCode, secondDate);
    List<FileGovernanceArrivalViews.ArrivalCandidateView> firstDayFiles = List.of(
        candidate(
            840101L, "orders.csv", fileGroupCode, workflowCode, "TPL_ORDER", tenantId, firstDate),
        candidate(
            840102L,
            "customers.csv",
            fileGroupCode,
            workflowCode,
            "TPL_CUSTOMER",
            tenantId,
            firstDate));
    List<FileGovernanceArrivalViews.ArrivalCandidateView> secondDayFiles = List.of(
        candidate(
            840201L, "orders.csv", fileGroupCode, workflowCode, "TPL_ORDER", tenantId, secondDate),
        candidate(
            840202L,
            "customers.csv",
            fileGroupCode,
            workflowCode,
            "TPL_CUSTOMER",
            tenantId,
            secondDate));

    assertThat(launcher.launchIfBundle(tenantId, fileGroupCode, firstDayFiles))
        .isEqualTo(BundleArrivalLauncher.LaunchOutcome.LAUNCHED);
    assertThat(launcher.launchIfBundle(tenantId, fileGroupCode, secondDayFiles))
        .isEqualTo(BundleArrivalLauncher.LaunchOutcome.LAUNCHED);

    assertThat(jdbcTemplate.queryForList(
            selectBizDateSql,
            LocalDate.class,
            tenantId,
            firstRequestId,
            secondRequestId))
        .containsExactly(firstDate, secondDate);
    assertThat(jdbcTemplate.queryForList(
            selectBizDateSql2,
            LocalDate.class,
            tenantId,
            firstRequestId,
            secondRequestId))
        .containsExactly(firstDate, secondDate);
    assertThat(jdbcTemplate.queryForList(
            selectBizDateSql3,
            LocalDate.class,
            tenantId,
            seed.workflowDefinitionId(),
            fileGroupCode))
        .containsExactly(firstDate, secondDate);
    assertThat(jdbcTemplate.queryForObject(
            selectCountSql,
            Integer.class,
            tenantId,
            seed.workflowDefinitionId(),
            fileGroupCode))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("同租户组编码和业务日关联不同目标 Workflow 时分别幂等接纳")
  void shouldKeepSameFileGroupIndependent_whenTargetWorkflowDiffers() {
    final String selectRequestIdSql = """
        select request_id, workflow_definition_id from batch.trigger_request where tenant_id = ?\s\
        and biz_date = ? and workflow_definition_id in (?, ?) order by workflow_definition_id\
        """;
    final String selectWorkflowDefinitionIdSql = """
        select workflow_definition_id from batch.expected_work_occurrence where tenant_id = ?\s\
        and calendar_code = 'IT_CALENDAR' and biz_date = ? and source_type = 'FILE_GROUP' and source_key = ?\s\
        and occurrence_status = 'SATISFIED' order by workflow_definition_id\
        """;

    String tenantId = uniqueTenant();
    WorkflowLaunchSeed firstTarget = LaunchIntegrationFixture.prepareBundleLaunchWithWorker(
        jdbcTemplate, tenantId, "IMPORT", "IMPORT");
    WorkflowLaunchSeed secondTarget = LaunchIntegrationFixture.prepareBundleLaunchWithWorker(
        jdbcTemplate, tenantId, "IMPORT", "IMPORT");
    String fileGroupCode = "target-aware-" + "x".repeat(80);
    String firstWorkflowCode = "WF-" + firstTarget.jobCode();
    String secondWorkflowCode = "WF-" + secondTarget.jobCode();
    LocalDate bizDate = BIZ_DATE.plusDays(3);
    List<FileGovernanceArrivalViews.ArrivalCandidateView> firstFiles = List.of(
        candidate(
            841001L,
            "orders.csv",
            fileGroupCode,
            firstWorkflowCode,
            "TPL_ORDER",
            tenantId,
            bizDate),
        candidate(
            841002L,
            "customers.csv",
            fileGroupCode,
            firstWorkflowCode,
            "TPL_CUSTOMER",
            tenantId,
            bizDate));
    List<FileGovernanceArrivalViews.ArrivalCandidateView> secondFiles = List.of(
        candidate(
            841003L,
            "orders.csv",
            fileGroupCode,
            secondWorkflowCode,
            "TPL_ORDER",
            tenantId,
            bizDate),
        candidate(
            841004L,
            "customers.csv",
            fileGroupCode,
            secondWorkflowCode,
            "TPL_CUSTOMER",
            tenantId,
            bizDate));

    assertThat(launcher.launchIfBundle(tenantId, fileGroupCode, firstFiles))
        .isEqualTo(BundleArrivalLauncher.LaunchOutcome.LAUNCHED);
    assertThat(launcher.launchIfBundle(tenantId, fileGroupCode, secondFiles))
        .isEqualTo(BundleArrivalLauncher.LaunchOutcome.LAUNCHED);

    List<Map<String, Object>> requests = jdbcTemplate.queryForList(
        selectRequestIdSql,
        tenantId,
        bizDate,
        firstTarget.workflowDefinitionId(),
        secondTarget.workflowDefinitionId());
    assertThat(requests).hasSize(2);
    assertThat(requests.stream().map(row -> row.get("workflow_definition_id")))
        .containsExactly(firstTarget.workflowDefinitionId(), secondTarget.workflowDefinitionId());
    assertThat(requests.stream().map(row -> row.get("request_id")).distinct()).hasSize(2);
    assertThat(jdbcTemplate.queryForList(
            selectWorkflowDefinitionIdSql,
            Long.class,
            tenantId,
            bizDate,
            fileGroupCode))
        .containsExactly(firstTarget.workflowDefinitionId(), secondTarget.workflowDefinitionId());
  }

  @Test
  @DisplayName("文件束启动暂时失败后保留可重试状态并由后续扫描恢复")
  void shouldRetryBundleAfterTargetWorkflowBecomesAvailable() {
  final String selectCountSql = """
      select count(*) from batch.trigger_request where tenant_id = ? and request_id = ?
  """;
  final String selectMetadataJsonSql = """
      select metadata_json->>'arrivalState' as arrival_state from batch.file_record where tenant_id = ?\s\
      and metadata_json->>'fileGroupCode' = ?\
      """;
  final String updateFileRecordSql = """
      update batch.file_record set metadata_json = metadata_json || jsonb_build_object('bundleWorkflowCode', ?)\s\
      where tenant_id = ? and id in (?, ?)\
      """;
  final String selectCountSql2 = """
      select count(*) from batch.expected_work_occurrence where tenant_id = ? and calendar_code = 'IT_CALENDAR'\s\
      and biz_date = ? and source_type = 'FILE_GROUP' and source_key = ? and occurrence_status = 'SATISFIED'\
      """;

    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareBundleLaunchWithWorker(
        jdbcTemplate, TENANT, "IMPORT", "IMPORT");
    String fileGroupCode = "retry-after-launch-failure-" + Long.toUnsignedString(System.nanoTime());
    String missingWorkflowCode = "MISSING-WORKFLOW-" + Long.toUnsignedString(System.nanoTime());
    String requestId =
        bundleRequestId(TENANT, seed.workflowDefinitionId(), fileGroupCode, BIZ_DATE);
    long firstFileId =
        insertArrivalFile(fileGroupCode, "orders.csv", missingWorkflowCode, "TPL_ORDER");
    long secondFileId =
        insertArrivalFile(fileGroupCode, "customers.csv", missingWorkflowCode, "TPL_CUSTOMER");
    double launchFailuresBefore =
        meterRegistry.counter("batch.file.arrival.bundle.launch.failed").count();

    fileGovernanceScheduler.manageFileArrivalGroups();

    assertThat(jdbcTemplate.queryForObject(
            selectCountSql,
            Integer.class,
            TENANT,
            requestId))
        .isZero();
    assertThat(jdbcTemplate.queryForList(
            selectMetadataJsonSql,
            TENANT,
            fileGroupCode))
        .allSatisfy(row -> assertThat(row.get("arrival_state")).isNull());
    assertThat(meterRegistry.counter("batch.file.arrival.bundle.launch.failed").count())
        .isEqualTo(launchFailuresBefore + 1.0);

    String availableWorkflowCode = "WF-" + seed.jobCode();
    jdbcTemplate.update(
        updateFileRecordSql,
        availableWorkflowCode,
        TENANT,
        firstFileId,
        secondFileId);

    fileGovernanceScheduler.manageFileArrivalGroups();

    assertThat(jdbcTemplate.queryForObject(
            selectCountSql,
            Integer.class,
            TENANT,
            requestId))
        .isOne();
    assertThat(jdbcTemplate.queryForObject(
            selectCountSql2,
            Integer.class,
            TENANT,
            BIZ_DATE,
            fileGroupCode))
        .isOne();
    assertThat(jdbcTemplate.queryForList(
            selectMetadataJsonSql,
            TENANT,
            fileGroupCode))
        .hasSize(2)
        .allSatisfy(row -> assertThat(row.get("arrival_state")).isEqualTo("TRIGGERED"));
  }

  @Test
  @DisplayName("业务日结算先完成时拒绝迟到文件束且不留下 occurrence")
  void shouldRejectBundleArrivalAfterBatchDaySettlement() {
  final String updateTriggerRequestSql = """
      update batch.trigger_request set request_status = 'REJECTED' where tenant_id = ? and request_id = ?
  """;
  final String selectCountSql = """
      select count(*) from batch.trigger_request where tenant_id = ? and request_id = ?
  """;
  final String selectCountSql2 = """
      select count(*) from batch.expected_work_occurrence where tenant_id = ? and calendar_code = 'IT_CALENDAR'\s\
      and biz_date = ? and source_key = ?\
      """;

    String tenantId = uniqueTenant();
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareBundleLaunchWithWorker(
        jdbcTemplate, tenantId, "IMPORT", "IMPORT");
    String fileGroupCode = "late-arrival-" + Long.toUnsignedString(System.nanoTime());
    String workflowCode = "WF-" + seed.jobCode();
    String originalRequestId =
        bundleRequestId(tenantId, seed.workflowDefinitionId(), fileGroupCode, BIZ_DATE);
    List<FileGovernanceArrivalViews.ArrivalCandidateView> candidates = List.of(
        candidate(
            820001L, "orders.csv", fileGroupCode, workflowCode, "TPL_ORDER", tenantId, BIZ_DATE),
        candidate(
            820002L,
            "customers.csv",
            fileGroupCode,
            workflowCode,
            "TPL_CUSTOMER",
            tenantId,
            BIZ_DATE));

    insertCutoffDay(tenantId, BIZ_DATE);
    jdbcTemplate.update(
        updateTriggerRequestSql,
        tenantId,
        seed.requestId());
    batchDaySettleScheduler.settle();
    assertThat(dayStatus(tenantId, BIZ_DATE)).isEqualTo("SETTLED");

    assertThat(launcher.launchIfBundle(tenantId, fileGroupCode, candidates))
        .isEqualTo(BundleArrivalLauncher.LaunchOutcome.REJECTED);
    assertThat(jdbcTemplate.queryForObject(
            selectCountSql,
            Integer.class,
            tenantId,
            originalRequestId))
        .isZero();
    assertThat(jdbcTemplate.queryForObject(
            selectCountSql2,
            Integer.class,
            tenantId,
            BIZ_DATE,
            fileGroupCode))
        .isZero();
  }

  @ParameterizedTest(name = "{0} 先等待业务日围栏时到达与结算按序收敛")
  @ValueSource(strings = {"ARRIVAL_FIRST", "SETTLEMENT_FIRST"})
  @DisplayName("文件束到达与空日结算按两种围栏顺序竞争")
  void shouldSerializeBundleArrivalWithSettlement(String firstWaiter) {
  final String updateTriggerRequestSql = """
      update batch.trigger_request set request_status = 'REJECTED' where tenant_id = ? and request_id = ?
  """;
  final String selectCountSql = """
      select count(*) from batch.workflow_run where tenant_id = ? and workflow_definition_id = ?\s\
      and biz_date = ? and run_status in ('CREATED', 'RUNNING')\
      """;
  final String selectCountSql2 = """
      select count(*) from batch.trigger_request where tenant_id = ? and request_id = ?
  """;
  final String selectCountSql3 = """
      select count(*) from batch.expected_work_occurrence where tenant_id = ? and calendar_code = 'IT_CALENDAR'\s\
      and biz_date = ? and source_type = 'FILE_GROUP' and source_key = ?\
      """;

    String tenantId = uniqueTenant();
    LocalDate bizDate = BIZ_DATE.plusDays(1);
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareBundleLaunchWithWorker(
        jdbcTemplate, tenantId, "IMPORT", "IMPORT");
    String fileGroupCode = "admitted-arrival-" + Long.toUnsignedString(System.nanoTime());
    String workflowCode = "WF-" + seed.jobCode();
    String requestId =
        bundleRequestId(tenantId, seed.workflowDefinitionId(), fileGroupCode, bizDate);
    insertCutoffDay(tenantId, bizDate);
    jdbcTemplate.update(
        updateTriggerRequestSql,
        tenantId,
        seed.requestId());
    List<FileGovernanceArrivalViews.ArrivalCandidateView> candidates = List.of(
        candidate(
            830001L, "orders.csv", fileGroupCode, workflowCode, "TPL_ORDER", tenantId, bizDate),
        candidate(
            830002L,
            "customers.csv",
            fileGroupCode,
            workflowCode,
            "TPL_CUSTOMER",
            tenantId,
            bizDate));

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try (Connection fence = jdbcTemplate.getDataSource().getConnection()) {
      fence.setAutoCommit(false);
      acquireSettlementFence(fence, tenantId, bizDate);
      int fencePid = backendPid(fence);
      Future<?> settlement;
      Future<BundleArrivalLauncher.LaunchOutcome> arrival;
      if ("ARRIVAL_FIRST".equals(firstWaiter)) {
        arrival =
            executor.submit(() -> launcher.launchIfBundle(tenantId, fileGroupCode, candidates));
        awaitBlockedBy(fencePid);
        settlement = executor.submit(batchDaySettleScheduler::settle);
      } else {
        settlement = executor.submit(batchDaySettleScheduler::settle);
        awaitBlockedBy(fencePid);
        arrival =
            executor.submit(() -> launcher.launchIfBundle(tenantId, fileGroupCode, candidates));
      }
      awaitBlockedByCount(fencePid, 2);
      fence.commit();
      settlement.get(10, TimeUnit.SECONDS);
      BundleArrivalLauncher.LaunchOutcome outcome = arrival.get(60, TimeUnit.SECONDS);
      if ("ARRIVAL_FIRST".equals(firstWaiter)) {
        assertThat(outcome).isEqualTo(BundleArrivalLauncher.LaunchOutcome.LAUNCHED);
      } else {
        assertThat(outcome).isEqualTo(BundleArrivalLauncher.LaunchOutcome.REJECTED);
      }
    } catch (Exception failure) {
      throw new AssertionError(
          "file bundle arrival and settlement should converge after fence release", failure);
    } finally {
      executor.shutdownNow();
    }

    if ("ARRIVAL_FIRST".equals(firstWaiter)) {
      assertThat(dayStatus(tenantId, bizDate)).isEqualTo("IN_FLIGHT");
      assertThat(jdbcTemplate.queryForObject(
              selectCountSql,
              Integer.class,
              tenantId,
              seed.workflowDefinitionId(),
              bizDate))
          .isOne();
      assertThat(jdbcTemplate.queryForObject(
              selectCountSql2,
              Integer.class,
              tenantId,
              requestId))
          .isOne();
    } else {
      assertThat(dayStatus(tenantId, bizDate)).isEqualTo("SETTLED");
      assertThat(jdbcTemplate.queryForObject(
              selectCountSql2,
              Integer.class,
              tenantId,
              requestId))
          .isZero();
      assertThat(jdbcTemplate.queryForObject(
              selectCountSql3,
              Integer.class,
              tenantId,
              bizDate,
              fileGroupCode))
          .isZero();
    }
  }

  private String uniqueTenant() {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')
    """;

    String tenantId = "bundle-it-" + Long.toUnsignedString(System.nanoTime());
    jdbcTemplate.update(
        insertTenantSql,
        tenantId,
        tenantId);
    return tenantId;
  }

  private long insertArrivalFile(
      String fileGroupCode, String fileName, String workflowCode, String templateCode) {
    final String insertFileRecordSql = """
    insert into batch.file_record (
    tenant_id, file_category, file_name, original_file_name, file_ext, file_format_type,
    charset, mime_type, file_size_bytes, checksum_type, storage_type, storage_path,
    file_version, file_generation_no, is_latest, source_type, source_ref, file_status,
    biz_date, trace_id, metadata_json, created_at, updated_at
    ) values (
    ?, 'INPUT', ?, ?, 'csv', 'DELIMITED', 'UTF-8', 'text/csv', 1, 'NONE', 'LOCAL',
    ?, 'v1', 1, true, 'SYSTEM', 'bundle-retry-it', 'RECEIVED', ?, ?, ?::jsonb,
    current_timestamp, current_timestamp
    ) returning id
    """;

    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("fileGroupCode", fileGroupCode);
    metadata.put("waitFileGroupMode", "ALL_OF");
    metadata.put("requiredFileSet", "orders.csv,customers.csv");
    metadata.put("arrivalTimeoutAction", "MANUAL_CONFIRM");
    metadata.put("triggerOnComplete", true);
    metadata.put("bundleWorkflowCode", workflowCode);
    metadata.put("bundleTemplateCode", templateCode);
    return jdbcTemplate.queryForObject(
        insertFileRecordSql,
        Long.class,
        TENANT,
        fileName,
        fileName,
        "incoming/" + fileGroupCode + "/" + fileName,
        BIZ_DATE,
        "bundle-retry-" + Long.toUnsignedString(System.nanoTime()),
        JsonUtils.toJson(metadata));
  }

  private void insertCutoffDay(String tenantId, LocalDate bizDate) {
    final String insertBatchDayInstanceSql = """
        insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at,\s\
        late_count, catchup_count, timezone_snapshot, dst_policy_snapshot, frozen, version, created_at,\s\
        updated_at, dry_run) values (?, 'IT_CALENDAR', ?, 'CUTOFF', current_timestamp, 0, 0, 'Asia/Shanghai',\s\
        'gap=RUN_AT_NEXT_VALID_TIME;overlap=RUN_ONCE_EARLIER_OFFSET', false, 0, current_timestamp,\s\
        current_timestamp, false)\
        """;

    jdbcTemplate.update(
        insertBatchDayInstanceSql,
        tenantId,
        bizDate);
  }

  private String dayStatus(String tenantId, LocalDate bizDate) {
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = 'IT_CALENDAR'\s\
        and biz_date = ?\
        """;

    return jdbcTemplate.queryForObject(
        selectDayStatusSql,
        String.class,
        tenantId,
        bizDate);
  }

  private void acquireSettlementFence(Connection connection, String tenantId, LocalDate bizDate)
      throws SQLException {
    final String selectAcquireBatchDaySettlementFenceSql = """
        select batch.acquire_batch_day_settlement_fence(?, 'IT_CALENDAR', ?)
    """;

    try (var statement = connection.prepareStatement(
        selectAcquireBatchDaySettlementFenceSql)) {
      statement.setString(1, tenantId);
      statement.setObject(2, bizDate);
      statement.executeQuery().close();
    }
  }

  private int backendPid(Connection connection) throws SQLException {
    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;

    try (var statement = connection.createStatement();
        var result = statement.executeQuery(selectPgBackendPidSql)) {
      result.next();
      return result.getInt(1);
    }
  }

  private void awaitBlockedBy(int blockerPid) throws Exception {
    final String selectCountSql = """
        select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))
    """;

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Integer waiting = jdbcTemplate.queryForObject(
          selectCountSql,
          Integer.class,
          blockerPid);
      if (waiting != null && waiting > 0) return;
      Thread.sleep(10);
    }
    throw new AssertionError("bundle arrival did not wait on the PostgreSQL batch-day fence");
  }

  private void awaitBlockedByCount(int blockerPid, int expectedCount) throws Exception {
    final String selectCountSql = """
        select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))
    """;

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Integer waiting = jdbcTemplate.queryForObject(
          selectCountSql,
          Integer.class,
          blockerPid);
      if (waiting != null && waiting >= expectedCount) return;
      Thread.sleep(10);
    }
    throw new AssertionError(
        "expected " + expectedCount + " bundle/settlement transactions to wait on the fence");
  }

  private static String bundleRequestId(
      String tenantId, Long workflowDefinitionId, String fileGroupCode, LocalDate bizDate) {
    return "bundle-arrival-" + tenantId + "-" + workflowDefinitionId + "-" + fileGroupCode + "-"
        + bizDate;
  }

  private static FileGovernanceArrivalViews.ArrivalCandidateView candidate(
      long fileId, String fileName, String groupCode, String workflowCode, String templateCode) {
    return candidate(fileId, fileName, groupCode, workflowCode, templateCode, TENANT, BIZ_DATE);
  }

  private static FileGovernanceArrivalViews.ArrivalCandidateView candidate(
      long fileId,
      String fileName,
      String groupCode,
      String workflowCode,
      String templateCode,
      String tenantId,
      LocalDate bizDate) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("bundleWorkflowCode", workflowCode);
    metadata.put("bundleTemplateCode", templateCode);
    metadata.put("integrityState", "VERIFIED");
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", fileId);
    row.put("tenant_id", tenantId);
    row.put("file_name", fileName);
    row.put("biz_date", bizDate.toString());
    row.put("file_group_code", groupCode);
    row.put("wait_file_group_mode", "ALL_OF");
    row.put("required_file_set", "orders.csv,customers.csv");
    row.put("arrival_timeout_action", "MANUAL_CONFIRM");
    row.put("arrival_state", "WAITING_ARRIVAL");
    row.put("arrival_reason", "WAITING_REQUIRED_FILES");
    row.put("trigger_on_complete", true);
    row.put("checksum_type", "SHA-256");
    row.put("metadata_json", JsonUtils.toJson(metadata));
    return FileGovernanceArrivalViews.arrivalCandidate(row);
  }
}
