package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowLaunchSeed;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR-046 文件束:控制面集成测试——验证 Workflow 根运行对束作业按 {@code params.bundleFiles} 展开成 N 个
 * <b>异构、各自绑定</b>的 {@code job_partition}(真 PG)。这是 unit 测(mock configCache 的
 * DefaultSchedulePlanBuilderTest)触不到的那段:真实 DB 上的 resolver 链 + partition 落库 + 绑定列写入。
 *
 * <p>三类束的绑定 profile 各异:import=源文件+模板、export=模板(无源文件)、dispatch=源文件+下游渠道。 不起 worker(worker 执行侧已被
 * ReceiveStep/DispatchPayload/ExportPayload 单测覆盖;全链真跑见 sim bundle stage)。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("文件束启动的分区展开集成:验证真实数据库与真实服务下导入/导出/分发三类束按束文件展开为各自绑定源文件 + 模板 + 下游渠道的分区记录")
class BundleLaunchPartitionExpansionIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, Month.JANUARY, 15);

  @Autowired
  private WorkflowRunLaunchService workflowRunLaunchService;

  @Autowired
  private JobInstanceMapper jobInstanceMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  private List<Map<String, Object>> launchBundleAndReadPartitions(
      String bundleJobType, String workerGroup, List<Map<String, Object>> bundleFiles) {
    return launchBundleAndReadPartitions(bundleJobType, workerGroup, bundleFiles, Map.of());
  }

  private List<Map<String, Object>> launchBundleAndReadPartitions(
      String bundleJobType,
      String workerGroup,
      List<Map<String, Object>> bundleFiles,
      Map<String, Object> nodeParams) {
    final String selectPartitionNoSql = """
        select partition_no, source_file_id, template_code, target_ref from batch.job_partition\s\
        where tenant_id = ? and job_instance_id = ? order by partition_no\
        """;

    bundleFiles.stream()
        .map(file -> ((Number) file.getOrDefault("sourceFileId", 0)).longValue())
        .filter(fileId -> fileId > 0)
        .forEach(fileId -> insertFileRecord(TENANT, fileId));
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareBundleLaunchWithWorker(
        jdbcTemplate, TENANT, bundleJobType, workerGroup);
    setJobNodeParams(seed, nodeParams);

    LaunchRequest request = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.EVENT)
        .requestId(seed.requestId())
        .traceId("trace-bundle-" + seed.requestId())
        .params(Map.of("bundleFiles", bundleFiles))
        .build();
    var response = workflowRunLaunchService.launch(request);
    assertThat(response.instanceNo()).isNotBlank();

    Long jobInstanceId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbcTemplate, TENANT, response.instanceNo());
    JobInstanceEntity instance = jobInstanceMapper.selectById(TENANT, jobInstanceId);
    assertThat(instance).isNotNull();

    return jdbcTemplate.queryForList(
        selectPartitionNoSql,
        TENANT,
        instance.getId());
  }

  private void insertFileRecord(String tenantId, Long fileId) {
    final String insertFileRecordSql = """
        insert into batch.file_record (id, tenant_id, file_category, file_name, file_format_type, storage_type,\s\
        storage_path, source_type, file_status)\s\
        values (?, ?, 'INPUT', ?, 'DELIMITED', 'LOCAL', ?, 'UPLOAD', 'RECEIVED')\
        """;

    jdbcTemplate.update(
        insertFileRecordSql,
        fileId,
        tenantId,
        "bundle-source-" + tenantId + "-" + fileId,
        "/bundle-source/" + tenantId + "/" + fileId);
  }

  @Test
  @DisplayName("导入束按束文件数量展开分区,每个分区各自绑定源文件与模板")
  void bundleImport_expandsIntoBoundHeterogeneousPartitions() {
    List<Map<String, Object>> partitions = launchBundleAndReadPartitions(
        "BUNDLE_IMPORT",
        "IMPORT",
        List.of(
            Map.of("sourceFileId", 1001, "templateCode", "TPL_ORDER"),
            Map.of("sourceFileId", 1002, "templateCode", "TPL_CUST")));

    assertThat(partitions).hasSize(2);
    assertThat(partitions.get(0)).containsEntry("source_file_id", 1001L);
    assertThat(partitions.get(0)).containsEntry("template_code", "TPL_ORDER");
    assertThat(partitions.get(1)).containsEntry("source_file_id", 1002L);
    assertThat(partitions.get(1)).containsEntry("template_code", "TPL_CUST");
  }

  @Test
  @DisplayName("同一动态束作业被两个 Workflow 节点引用时各自展开并隔离分区")
  void sameDynamicJobInTwoWorkflowNodes_createsIndependentFanOutInstances() {
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set single_job_definition_id = null where tenant_id = ? and id = ?\
        """;
    final String updateWorkflowNodeSql = """
        update batch.workflow_node set node_order = 3 where tenant_id = ? and workflow_definition_id = ?\s\
        and node_code = 'END'\
        """;
    final String insertWorkflowNodeSql = """
        insert into batch.workflow_node (tenant_id, workflow_definition_id, node_code, node_name, node_type,\s\
        related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled)\s\
        values (?, ?, 'BUNDLE_JOB_COPY', 'Second Bundle Job', 'JOB', ?, 2, 'NONE', 0, 0, true)\
        """;
    final String insertWorkflowEdgeSql = """
        insert into batch.workflow_edge (tenant_id, workflow_definition_id, from_node_code, to_node_code,\s\
        edge_type, enabled) values (?, ?, 'START', 'BUNDLE_JOB_COPY', 'ALWAYS', true), (?, ?, 'BUNDLE_JOB_COPY',\s\
        'END', 'SUCCESS', true)\
        """;
    final String selectIdSql = """
        select id from batch.workflow_run where tenant_id = ? and workflow_definition_id = ?\s\
        and trigger_request_id = (select id from batch.trigger_request where tenant_id = ? and request_id = ?)\
        """;
    final String selectNodeCodeSql = """
        select node_code, job_instance_id from batch.workflow_node_run where workflow_run_id = ?\s\
        and node_code in ('BUNDLE_JOB', 'BUNDLE_JOB_COPY') order by node_code\
        """;
    final String selectExpectedPartitionCountSql = """
        select expected_partition_count from batch.job_instance where tenant_id = ? and id = ?\
        """;
  final String selectSourceFileIdSql = """
      select source_file_id, template_code from batch.job_partition where tenant_id = ? and job_instance_id = ?\s\
      order by partition_no\
      """;
  final String selectCountSql = """
      select count(*) from batch.job_task where tenant_id = ? and job_instance_id = ?
  """;
  final String selectRunStatusSql = """
      select run_status from batch.workflow_run where id = ?
  """;
  final String selectCountSql2 = """
      select count(*) from batch.workflow_node_run where workflow_run_id = ? and node_code = 'END'\
      """;

    insertFileRecord(TENANT, 8101L);
    insertFileRecord(TENANT, 8102L);
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareBundleLaunchWithWorker(
        jdbcTemplate, TENANT, "BUNDLE_IMPORT", "IMPORT");
    // 该 fixture 默认模拟单作业受管 Workflow；本场景改为组合 DAG，两个节点才能有独立 dedup 身份。
    jdbcTemplate.update(
        updateWorkflowDefinitionSql,
        TENANT,
        seed.workflowDefinitionId());
    jdbcTemplate.update(
        updateWorkflowNodeSql,
        TENANT,
        seed.workflowDefinitionId());
    jdbcTemplate.update(
        insertWorkflowNodeSql,
        TENANT,
        seed.workflowDefinitionId(),
        seed.jobCode());
    jdbcTemplate.update(
        insertWorkflowEdgeSql,
        TENANT,
        seed.workflowDefinitionId(),
        TENANT,
        seed.workflowDefinitionId());

    LaunchRequest request = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.EVENT)
        .requestId(seed.requestId())
        .traceId("trace-shared-dynamic-job-" + seed.requestId())
        .params(Map.of(
            "bundleFiles",
            List.of(
                Map.of("sourceFileId", 8101, "templateCode", "TPL_A"),
                Map.of("sourceFileId", 8102, "templateCode", "TPL_B"))))
        .build();
    var response = workflowRunLaunchService.launch(request);

    Long workflowRunId = jdbcTemplate.queryForObject(
        selectIdSql,
        Long.class,
        TENANT,
        seed.workflowDefinitionId(),
        TENANT,
        seed.requestId());
    assertThat(response.instanceNo()).isNotBlank();
    List<Map<String, Object>> nodeInstances = jdbcTemplate.queryForList(
        selectNodeCodeSql,
        workflowRunId);
    assertThat(nodeInstances).hasSize(2);
    Long firstInstanceId = (Long) nodeInstances.get(0).get("job_instance_id");
    Long secondInstanceId = (Long) nodeInstances.get(1).get("job_instance_id");
    assertThat(firstInstanceId).isNotNull().isNotEqualTo(secondInstanceId);

    for (Long instanceId : List.of(firstInstanceId, secondInstanceId)) {
      Integer selectedExpectedPartitionCount = jdbcTemplate.queryForObject(
          selectExpectedPartitionCountSql, Integer.class, TENANT, instanceId);
      assertThat(selectedExpectedPartitionCount).isEqualTo(2);
      List<Map<String, Object>> partitions = jdbcTemplate.queryForList(
          selectSourceFileIdSql,
          TENANT,
          instanceId);
      assertThat(partitions).containsExactly(
          Map.of("source_file_id", 8101L, "template_code", "TPL_A"),
          Map.of("source_file_id", 8102L, "template_code", "TPL_B"));
      Integer selectedJobTaskCount =
          jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, instanceId);
      assertThat(selectedJobTaskCount).isEqualTo(2);
    }
    String selectedRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, workflowRunId);
    assertThat(selectedRunStatus).isEqualTo("RUNNING");
    Integer selectedWorkflowNodeRunCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCount).isZero();
  }

  @Test
  @DisplayName("导出束按模板数量展开分区,模板各自绑定且源文件为空")
  void bundleExport_expandsByTemplateWithoutSourceFile() {
    List<Map<String, Object>> partitions = launchBundleAndReadPartitions(
        "BUNDLE_EXPORT",
        "EXPORT",
        List.of(Map.of("templateCode", "EXP_RISK"), Map.of("templateCode", "EXP_TRADE")));

    assertThat(partitions).hasSize(2);
    assertThat(partitions.get(0).get("source_file_id")).isNull();
    assertThat(partitions.get(0)).containsEntry("template_code", "EXP_RISK");
    assertThat(partitions.get(1)).containsEntry("template_code", "EXP_TRADE");
  }

  @Test
  @DisplayName("分发束按源文件展开分区并各自绑定下游渠道,模板为空")
  void bundleDispatch_expandsByFileAndChannelWithoutTemplate() {
    List<Map<String, Object>> partitions = launchBundleAndReadPartitions(
        "BUNDLE_DISPATCH",
        "DISPATCH",
        List.of(
            Map.of("sourceFileId", 2001, "targetRef", "CH_SFTP_A"),
            Map.of("sourceFileId", 2002, "targetRef", "CH_OSS_B")));

    assertThat(partitions).hasSize(2);
    assertThat(partitions.get(0)).containsEntry("source_file_id", 2001L);
    assertThat(partitions.get(0)).containsEntry("target_ref", "CH_SFTP_A");
    assertThat(partitions.get(0).get("template_code")).isNull();
    assertThat(partitions.get(1)).containsEntry("source_file_id", 2002L);
    assertThat(partitions.get(1)).containsEntry("target_ref", "CH_OSS_B");
  }

  @Test
  @DisplayName("请求文件束覆盖节点默认值时校验并派发请求中的文件")
  void bundleImport_requestFilesOverrideNodeDefaults() {
    insertFileRecord("t2", 900_002L);
    List<Map<String, Object>> partitions = launchBundleAndReadPartitions(
        "BUNDLE_IMPORT",
        "IMPORT",
        List.of(Map.of("sourceFileId", 3001, "templateCode", "TPL_ORDER")),
        Map.of(
            "bundleFiles", List.of(Map.of("sourceFileId", 900_002, "templateCode", "TPL_CUST"))));

    assertThat(partitions).hasSize(1);
    assertThat(partitions.get(0))
        .containsEntry("source_file_id", 3001L)
        .containsEntry("template_code", "TPL_ORDER");
  }

  private void setJobNodeParams(WorkflowLaunchSeed seed, Map<String, Object> nodeParams) {
    final String updateWorkflowNodeSql = """
        update batch.workflow_node set node_params = ?::jsonb where workflow_definition_id = ?\s\
        and node_type = 'JOB'\
        """;

    jdbcTemplate.update(
        updateWorkflowNodeSql,
        JsonUtils.toJson(nodeParams),
        seed.workflowDefinitionId());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("拒绝把其他租户的文件绑定到文件束分区")
  void bundleImport_rejectsForeignTenantSourceFile(boolean maskWithNodeDefaults) {
  final String selectIdSql = """
      select id from batch.trigger_request where tenant_id = ? and request_id = ?
  """;
  final String selectRequestStatusSql = """
      select request_status from batch.trigger_request where id = ?
  """;
  final String selectCountSql = """
      select count(*) from batch.workflow_run where trigger_request_id = ?
  """;
  final String selectCountSql2 = """
      select count(*) from batch.workflow_node_run node_run\s\
      join batch.workflow_run workflow_run on workflow_run.id = node_run.workflow_run_id\s\
      where workflow_run.trigger_request_id = ?\
      """;
  final String selectCountSql3 = """
      select count(*) from batch.job_partition where tenant_id = ? and source_file_id = ?
  """;

    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareBundleLaunchWithWorker(
        jdbcTemplate, TENANT, "BUNDLE_IMPORT", "IMPORT");
    long foreignFileId = maskWithNodeDefaults ? 900_004L : 900_001L;
    insertFileRecord("t2", foreignFileId);
    if (maskWithNodeDefaults) {
      insertFileRecord(TENANT, 900_003L);
      setJobNodeParams(
          seed,
          Map.of(
              "bundleFiles", List.of(Map.of("sourceFileId", 900_003, "templateCode", "TPL_CUST"))));
    }
    LaunchRequest request = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.EVENT)
        .requestId(seed.requestId())
        .traceId("trace-bundle-foreign-" + seed.requestId())
        .params(Map.of(
            "bundleFiles",
            List.of(Map.of("sourceFileId", foreignFileId, "templateCode", "TPL_ORDER"))))
        .build();

    assertThatThrownBy(() -> workflowRunLaunchService.launch(request))
        .hasMessage("error.file.record_not_found");
    Long triggerRequestId = jdbcTemplate.queryForObject(
        selectIdSql,
        Long.class,
        TENANT,
        seed.requestId());
    String selectedRequestStatus =
        jdbcTemplate.queryForObject(selectRequestStatusSql, String.class, triggerRequestId);
    assertThat(selectedRequestStatus).isEqualTo("REJECTED");
    Integer selectedWorkflowRunCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, triggerRequestId);
    assertThat(selectedWorkflowRunCount).isZero();
    Integer selectedWorkflowNodeRunCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, triggerRequestId);
    assertThat(selectedWorkflowNodeRunCount).isZero();
    Integer selectedJobPartitionCount =
        jdbcTemplate.queryForObject(selectCountSql3, Integer.class, TENANT, foreignFileId);
    assertThat(selectedJobPartitionCount).isZero();
  }

  @Test
  @DisplayName("运行时 DSL 解析出的外租户文件拒绝子 Job 并使根 Workflow 失败收敛")
  void bundleImport_rejectsForeignTenantDslSourceFileAndFinalizesWorkflow() {
  final String updateWorkflowEdgeSql = """
      update batch.workflow_edge set edge_type = 'FAILURE' where workflow_definition_id = ?\s\
      and from_node_code = 'BUNDLE_JOB' and to_node_code = 'END'\
      """;
  final String selectIdSql = """
      select id from batch.trigger_request where tenant_id = ? and request_id = ?
  """;
  final String selectIdSql2 = """
      select id from batch.workflow_run where trigger_request_id = ?
  """;
  final String selectRunStatusSql = """
      select run_status from batch.workflow_run where id = ?
  """;
  final String selectNodeStatusSql = """
      select node_status from batch.workflow_node_run where workflow_run_id = ? and node_type = 'JOB'\
      """;
  final String selectNodeStatusSql2 = """
      select node_status from batch.workflow_node_run where workflow_run_id = ? and node_type = 'END'\
      """;
  final String selectIdSql3 = """
      select id from batch.trigger_request where parent_workflow_run_id = ? and parent_node_code = 'BUNDLE_JOB'\
      """;
  final String selectRequestStatusSql = """
      select request_status from batch.trigger_request where id = ?
  """;
  final String selectCountSql = """
      select count(*) from batch.job_instance where trigger_request_id = ?
  """;
  final String selectCountSql2 = """
      select count(*) from batch.job_partition where tenant_id = ? and source_file_id = ?
  """;
  final String selectCountSql3 = """
      select count(*) from batch.outbox_event where tenant_id = ? and aggregate_type = 'WORKFLOW_RUN'\s\
      and aggregate_id = ? and event_type = 'WORKFLOW_TERMINAL'\
      """;

    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareBundleLaunchWithWorker(
        jdbcTemplate, TENANT, "BUNDLE_IMPORT", "IMPORT");
    long foreignFileId = 900_005L;
    insertFileRecord("t2", foreignFileId);
    jdbcTemplate.update(
        updateWorkflowEdgeSql,
        seed.workflowDefinitionId());
    setJobNodeParams(
        seed,
        Map.of(
            "bundleFiles",
            List.of(Map.of("sourceFileId", "$.workflowRun.traceId", "templateCode", "TPL_ORDER"))));
    LaunchRequest request = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.EVENT)
        .requestId(seed.requestId())
        .traceId(Long.toString(foreignFileId))
        .params(Map.of())
        .build();

    assertThatCode(() -> workflowRunLaunchService.launch(request)).doesNotThrowAnyException();

    Long rootRequestId = jdbcTemplate.queryForObject(
        selectIdSql,
        Long.class,
        TENANT,
        seed.requestId());
    Long workflowRunId = jdbcTemplate.queryForObject(
        selectIdSql2,
        Long.class,
        rootRequestId);
    String selectedRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, workflowRunId);
    assertThat(selectedRunStatus).isEqualTo("FAILED");
    String selectedNodeStatus =
        jdbcTemplate.queryForObject(selectNodeStatusSql, String.class, workflowRunId);
    assertThat(selectedNodeStatus).isEqualTo("FAILED");
    String selectedNodeStatusRead2 =
        jdbcTemplate.queryForObject(selectNodeStatusSql2, String.class, workflowRunId);
    assertThat(selectedNodeStatusRead2).isEqualTo("SUCCESS");
    Long childRequestId = jdbcTemplate.queryForObject(
        selectIdSql3,
        Long.class,
        workflowRunId);
    String selectedRequestStatus =
        jdbcTemplate.queryForObject(selectRequestStatusSql, String.class, childRequestId);
    assertThat(selectedRequestStatus).isEqualTo("REJECTED");
    Integer selectedJobInstanceCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, childRequestId);
    assertThat(selectedJobInstanceCount).isZero();
    Integer selectedJobPartitionCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, foreignFileId);
    assertThat(selectedJobPartitionCount).isZero();
    Integer selectedOutboxEventCount =
        jdbcTemplate.queryForObject(selectCountSql3, Integer.class, TENANT, workflowRunId);
    assertThat(selectedOutboxEventCount).isEqualTo(1);
  }
}
