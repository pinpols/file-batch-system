package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.view.JobDefinitionView;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowDefinitionVersionUpdateParam;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowScheduleUpdateParam;
import io.github.pinpols.batch.console.infrastructure.config.TenantConfigPackageExcelApplyService;
import io.github.pinpols.batch.console.infrastructure.config.TenantConfigPackageExcelApplyService.ApplyContext;
import io.github.pinpols.batch.console.infrastructure.config.TenantConfigPackageExcelApplyService.ApplyCounts;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.PackageValidationResult;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.SheetResult;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 真实 SQL 固定字段映射、租户范围及版本竞争，防止参数重构漏掉日历或放开 CAS。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("Workflow 定义写入的字段、租户与版本比较 PostgreSQL 契约")
class WorkflowDefinitionWriteContractIntegrationTest extends AbstractIntegrationTest {

  private final WorkflowDefinitionMapper mapper;
  private final JdbcTemplate jdbc;
  private final TenantConfigPackageExcelApplyService excelApply;
  private final JobDefinitionMapper jobMapper;

  @Autowired
  WorkflowDefinitionWriteContractIntegrationTest(
      WorkflowDefinitionMapper mapper,
      JdbcTemplate jdbc,
      TenantConfigPackageExcelApplyService excelApply,
      JobDefinitionMapper jobMapper) {
    this.mapper = mapper;
    this.jdbc = jdbc;
    this.excelApply = excelApply;
    this.jobMapper = jobMapper;
  }

  private String tenant;
  private Long id;
  private Long managedJobId;

  @BeforeEach
  void seedDefinition() {
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, enabled)
        values (?, 'CAL_A', 'Calendar A', 'UTC', true),
               (?, 'CAL_B', 'Calendar B', 'Asia/Shanghai', true)
        """;
    final String insertJobDefinitionSql = """
        insert into batch.job_definition
          (tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
           priority, queue_code, worker_group, trigger_mode, retry_policy, retry_max_count,
           timeout_seconds, enabled, version)
        values (?, 'DOWNSTREAM', 'Downstream', 'PROCESS', 'TEST', 'MANUAL', 'UTC',
                5, 'default', 'PROCESS', 'MANUAL', 'NONE', 0, 0, true, 1)
        returning id
        """;

    tenant = "wf-write-" + UUID.randomUUID();
    jdbc.update(insertBusinessCalendarSql, tenant, tenant);
    managedJobId = jdbc.queryForObject(insertJobDefinitionSql, Long.class, tenant);
    WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity();
    entity.setTenantId(tenant);
    entity.setWorkflowCode("WF_WRITE");
    entity.setWorkflowName("Original name");
    entity.setWorkflowType("DAG");
    entity.setVersion(1);
    entity.setEnabled(true);
    entity.setScheduleEnabled(false);
    entity.setScheduleType("MANUAL");
    entity.setTimezone("UTC");
    entity.setTriggerMode("MANUAL");
    entity.setCalendarCode("CAL_A");
    entity.setSingleJobDefinitionId(managedJobId);
    assertThat(mapper.insert(entity)).isEqualTo(1);
    id = entity.getId();
    WorkflowDefinitionEntity saved = mapper.selectById(tenant, id);
    assertThat(saved.getScheduleEnabled()).isFalse();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(booleans = {true, false})
  @DisplayName("插入工作流保留显式调度开关,未给开关时保持原启用默认")
  void shouldPersistScheduleEnabled_whenInsertingDefinition(Boolean scheduleEnabled) {
    WorkflowDefinitionEntity entity = mapper.selectById(tenant, id);
    entity.setId(null);
    entity.setWorkflowCode("WF_INSERT_SCHEDULE");
    entity.setSingleJobDefinitionId(null);
    entity.setScheduleEnabled(scheduleEnabled);

    int inserted = mapper.insert(entity);
    WorkflowDefinitionEntity saved = mapper.selectById(tenant, entity.getId());

    assertThat(inserted).isOne();
    boolean expectedScheduleEnabled = scheduleEnabled == null || scheduleEnabled;
    assertThat(saved.getScheduleEnabled()).isEqualTo(expectedScheduleEnabled);
  }

  @Test
  @DisplayName("Excel 新建定时作业时受管工作流继承实际数据库时区且调度默认关闭")
  void shouldUsePersistedJobDefaults_whenImportingCronJob() {
    String insertCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, enabled)
        values (?, 'default_calendar', 'Default calendar', 'UTC', true)
        """;
    jdbc.update(insertCalendarSql, tenant);
    Map<String, String> row = Map.of(
        "job_code", "IMPORTED_CRON",
        "job_name", "Imported cron",
        "job_type", "PROCESS",
        "biz_type", "TEST",
        "schedule_type", "CRON",
        "schedule_expr", "0 0 2 * * ?");
    SheetResult empty = new SheetResult("empty", 0, List.of(), List.of());
    SheetResult jobs = new SheetResult("job_definition", 1, List.of(row), List.of());
    PackageValidationResult input = new PackageValidationResult(
        empty, empty, empty, jobs, empty, empty, empty, empty, empty, empty, empty, empty,
        List.of());
    ApplyContext context = new ApplyContext(tenant, "tester", "test", "trace-cron-defaults");

    ApplyCounts result = excelApply.applyAll(input, context);
    JobDefinitionView job = jobMapper.selectByUniqueKey(tenant, "IMPORTED_CRON");
    WorkflowDefinitionEntity workflow = mapper.selectBySingleJobDefinitionId(tenant, job.getId());

    assertThat(result.jobInserted()).isOne();
    assertThat(job.getTimezone()).isEqualTo("Asia/Shanghai");
    assertThat(workflow.getTimezone()).isEqualTo("Asia/Shanghai");
    assertThat(workflow.getScheduleType()).isEqualTo("CRON");
    assertThat(workflow.getScheduleExpr()).isEqualTo("0 0 2 * * ?");
    assertThat(workflow.getTriggerMode()).isEqualTo("SCHEDULED");
    assertThat(workflow.getScheduleEnabled()).isFalse();
    assertThat(workflow.getEnabled()).isTrue();
  }

  @Test
  @DisplayName("配置包更新已有工作流时说明字段与版本一同落库")
  void shouldPersistDescription_whenApplyingExistingWorkflow() {
    Map<String, String> row = Map.of(
        "workflow_code",
        "WF_WRITE",
        "workflow_name",
        "Original name",
        "workflow_type",
        "DAG",
        "version",
        "1",
        "description",
        "更新后的专业说明");
    SheetResult empty = new SheetResult("empty", 0, List.of(), List.of());
    SheetResult workflows = new SheetResult("workflow_definition", 1, List.of(row), List.of());
    PackageValidationResult input = new PackageValidationResult(
        empty, empty, empty, empty, empty, empty, empty, empty, empty, workflows, empty, empty,
        List.of());
    ApplyContext context = new ApplyContext(tenant, "tester", "test", "trace-description");

    ApplyCounts result = excelApply.applyAll(input, context);
    WorkflowDefinitionEntity saved = mapper.selectById(tenant, id);

    assertThat(saved.getDescription()).isEqualTo("更新后的专业说明");
    assertThat(saved.getVersion()).isEqualTo(2);
    assertThat(result.workflowUpdated()).isEqualTo(1);
  }

  @Test
  @DisplayName("全量定义更新只在原版本及租户匹配时递增一次,过期请求不改字段")
  void shouldCompareVersionAndTenant_whenReplacingDefinition() {
    assertThat(mapper.updateAndBumpVersion(new WorkflowDefinitionVersionUpdateParam(
            tenant,
            id,
            1,
            "Version two",
            "DAG",
            false,
            true,
            "CRON",
            "0 30 3 * * ?",
            "Asia/Shanghai",
            "SCHEDULED",
            "CAL_B",
            "UPSTREAM_B",
            null)))
        .isEqualTo(1);
    WorkflowDefinitionEntity updated = mapper.selectById(tenant, id);
    assertThat(updated.getVersion()).isEqualTo(2);
    assertThat(updated.getWorkflowName()).isEqualTo("Version two");
    assertThat(updated.getEnabled()).isFalse();
    assertThat(updated.getScheduleEnabled()).isTrue();
    assertThat(updated.getScheduleType()).isEqualTo("CRON");
    assertThat(updated.getScheduleExpr()).isEqualTo("0 30 3 * * ?");
    assertThat(updated.getTimezone()).isEqualTo("Asia/Shanghai");
    assertThat(updated.getTriggerMode()).isEqualTo("SCHEDULED");
    assertThat(updated.getCalendarCode()).isEqualTo("CAL_B");
    assertThat(updated.getDependsOnJobCode()).isEqualTo("UPSTREAM_B");
    assertThat(updated.getSingleJobDefinitionId()).isEqualTo(managedJobId);

    assertThat(mapper.updateAndBumpVersion(new WorkflowDefinitionVersionUpdateParam(
            tenant, id, 1, "Stale", "DAG", true, false, "MANUAL", null, "UTC", "MANUAL", null, null,
            null)))
        .isZero();
    assertThat(mapper.updateAndBumpVersion(new WorkflowDefinitionVersionUpdateParam(
            "other-tenant",
            id,
            2,
            "Forbidden",
            "DAG",
            true,
            false,
            "MANUAL",
            null,
            "UTC",
            "MANUAL",
            null,
            null,
            null)))
        .isZero();
    assertThat(mapper.selectById(tenant, id)).usingRecursiveComparison().isEqualTo(updated);
  }

  @Test
  @DisplayName("全量更新缺少预期版本时拒绝,不能退化为无条件覆盖")
  void shouldRejectMissingExpectedVersion_beforeWritingDefinition() {
    WorkflowDefinitionEntity original = mapper.selectById(tenant, id);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> mapper.updateAndBumpVersion(new WorkflowDefinitionVersionUpdateParam(
            tenant,
            id,
            null,
            "Unconditional overwrite",
            "DAG",
            true,
            false,
            "MANUAL",
            null,
            "UTC",
            "MANUAL",
            null,
            null,
            null)));

    assertThat(mapper.selectById(tenant, id)).usingRecursiveComparison().isEqualTo(original);
  }

  @Test
  @DisplayName("同一版本并发修改日程只有一个赢家,随后可明确清空可选日程字段")
  void shouldAllowOneConcurrentScheduleUpdate_andPreserveDefinitionFields() throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    List<String> expressions = List.of("0 1 2 * * ?", "0 2 2 * * ?");
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      List<Future<Integer>> results = expressions.stream()
          .map(expression -> executor.submit(() -> {
            ready.countDown();
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return mapper.updateScheduleConfiguration(new WorkflowScheduleUpdateParam(
                tenant,
                id,
                1,
                "CRON",
                expression,
                "Asia/Shanghai",
                "SCHEDULED",
                "CAL_B",
                "UPSTREAM_B"));
          }))
          .toList();
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(
              results.get(0).get(10, TimeUnit.SECONDS) + results.get(1).get(10, TimeUnit.SECONDS))
          .isEqualTo(1);
    }
    WorkflowDefinitionEntity updated = mapper.selectById(tenant, id);
    assertThat(updated.getVersion()).isEqualTo(2);
    assertThat(updated.getScheduleExpr()).isIn(expressions);
    assertThat(updated.getWorkflowName()).isEqualTo("Original name");
    assertThat(updated.getEnabled()).isTrue();
    assertThat(updated.getScheduleEnabled()).isFalse();
    assertThat(updated.getSingleJobDefinitionId()).isEqualTo(managedJobId);

    assertThat(mapper.updateScheduleConfiguration(new WorkflowScheduleUpdateParam(
            "other-tenant", id, 2, "MANUAL", null, "UTC", "MANUAL", null, null)))
        .isZero();
    assertThat(mapper.updateScheduleConfiguration(new WorkflowScheduleUpdateParam(
            tenant, id, 2, "MANUAL", null, "UTC", "MANUAL", null, null)))
        .isEqualTo(1);
    WorkflowDefinitionEntity manual = mapper.selectById(tenant, id);
    assertThat(manual.getVersion()).isEqualTo(3);
    assertThat(manual.getScheduleType()).isEqualTo("MANUAL");
    assertThat(manual.getScheduleExpr()).isNull();
    assertThat(manual.getCalendarCode()).isNull();
    assertThat(manual.getDependsOnJobCode()).isNull();
    assertThat(manual.getWorkflowName()).isEqualTo("Original name");
  }
}
