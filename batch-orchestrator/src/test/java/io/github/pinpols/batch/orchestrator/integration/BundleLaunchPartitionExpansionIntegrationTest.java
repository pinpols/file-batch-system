package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.math.BigDecimal;
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

    return jdbcTemplate.queryForList(selectPartitionNoSql, TENANT, instance.getId());
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
  @DisplayName("文件束小数文件 ID 不得截断并绑定到其他真实文件")
  void bundleImport_rejectsFractionalFileIdentity() {
    final String countPartitionsSql = """
        select count(*) from batch.job_partition where tenant_id = ? and source_file_id = ?
        """;
    final String selectRunSql = """
        select wr.run_status from batch.workflow_run wr
        join batch.trigger_request tr on tr.id = wr.trigger_request_id and tr.tenant_id = wr.tenant_id
        where tr.tenant_id = ? and tr.request_id = ?
        """;
    insertFileRecord(TENANT, 997001L);
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareBundleLaunchWithWorker(
        jdbcTemplate, TENANT, "BUNDLE_IMPORT", "IMPORT");
    LaunchRequest request = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.EVENT)
        .requestId(seed.requestId())
        .traceId("trace-fractional-file-id")
        .params(Map.of(
            "bundleFiles",
            List.of(
                Map.of("sourceFileId", new BigDecimal("997001.9"), "templateCode", "TPL_ORDER"))))
        .build();

    assertThat(workflowRunLaunchService.launch(request).instanceNo()).isNotBlank();
    assertThat(jdbcTemplate.queryForObject(selectRunSql, String.class, TENANT, seed.requestId()))
        .isEqualTo("FAILED");
    assertThat(jdbcTemplate.queryForObject(countPartitionsSql, Integer.class, TENANT, 997001L))
        .isZero();
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
    jdbcTemplate.update(updateWorkflowDefinitionSql, TENANT, seed.workflowDefinitionId());
    jdbcTemplate.update(updateWorkflowNodeSql, TENANT, seed.workflowDefinitionId());
    jdbcTemplate.update(insertWorkflowNodeSql, TENANT, seed.workflowDefinitionId(), seed.jobCode());
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
        selectIdSql, Long.class, TENANT, seed.workflowDefinitionId(), TENANT, seed.requestId());
    assertThat(response.instanceNo()).isNotBlank();
    List<Map<String, Object>> nodeInstances =
        jdbcTemplate.queryForList(selectNodeCodeSql, workflowRunId);
    assertThat(nodeInstances).hasSize(2);
    Long firstInstanceId = (Long) nodeInstances.get(0).get("job_instance_id");
    Long secondInstanceId = (Long) nodeInstances.get(1).get("job_instance_id");
    assertThat(firstInstanceId).isNotNull().isNotEqualTo(secondInstanceId);

    for (Long instanceId : List.of(firstInstanceId, secondInstanceId)) {
      Integer selectedExpectedPartitionCount = jdbcTemplate.queryForObject(
          selectExpectedPartitionCountSql, Integer.class, TENANT, instanceId);
      assertThat(selectedExpectedPartitionCount).isEqualTo(2);
      List<Map<String, Object>> partitions =
          jdbcTemplate.queryForList(selectSourceFileIdSql, TENANT, instanceId);
      assertThat(partitions)
          .containsExactly(
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
    final String insertChannelsSql = """
        insert into batch.file_channel_config
          (tenant_id, channel_code, channel_name, channel_type, config_json)
        values (?, 'CH_SFTP_A', 'SFTP', 'SFTP', '{}'::jsonb),
               (?, 'CH_OSS_B', 'OSS', 'OSS', '{}'::jsonb)
        """;
    jdbcTemplate.update(insertChannelsSql, TENANT, TENANT);
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
  @DisplayName("文件束分发的真实实例按两个渠道保存冻结配置与密钥版本")
  void bundleDispatch_persistsChannelAndSecretSnapshots() {
    final String insertSecretSql = """
        insert into batch.secret_version
          (tenant_id, secret_ref, secret_name, version_no, secret_status, current_version)
        values (?, ?, ?, 1, 'PUBLISHED', true) returning id
        """;
    final String insertChannelSql = """
        insert into batch.file_channel_config
          (tenant_id, channel_code, channel_name, channel_type, config_json)
        values (?, ?, ?, 'SFTP', jsonb_build_object('secret_ref', ?::text))
        """;
    final String selectSnapshotSql = """
        select ji.params_snapshot from batch.job_instance ji
        join batch.job_partition p on p.tenant_id = ji.tenant_id and p.job_instance_id = ji.id
        where ji.tenant_id = ? and p.source_file_id = ?
        """;
    final String selectTaskPayloadSql = """
        select jt.task_payload from batch.job_task jt
        join batch.job_partition p on p.tenant_id = jt.tenant_id and p.id = jt.job_partition_id
        where jt.tenant_id = ? and p.source_file_id = ?
        """;
    Long firstSecretId =
        jdbcTemplate.queryForObject(insertSecretSql, Long.class, TENANT, "bundle/key-one", "first");
    Long secondSecretId = jdbcTemplate.queryForObject(
        insertSecretSql, Long.class, TENANT, "bundle/key-two", "second");
    jdbcTemplate.update(insertChannelSql, TENANT, "BUNDLE_CH_1", "first", "bundle/key-one");
    jdbcTemplate.update(insertChannelSql, TENANT, "BUNDLE_CH_2", "second", "bundle/key-two");

    launchBundleAndReadPartitions(
        "BUNDLE_DISPATCH",
        "DISPATCH",
        List.of(
            Map.of("sourceFileId", 991001, "targetRef", "BUNDLE_CH_1"),
            Map.of("sourceFileId", 991002, "targetRef", "BUNDLE_CH_2")),
        Map.of("metadata", Map.of("targetRef", "not-a-channel")));

    String snapshot = jdbcTemplate.queryForObject(selectSnapshotSql, String.class, TENANT, 991001L);
    var effectiveParams = JsonUtils.fromJson(snapshot, JsonNode.class).path("effectiveParams");
    assertThat(effectiveParams
            .path("_dispatchSecretBindings")
            .path("BUNDLE_CH_1")
            .path("secretVersionId")
            .asLong())
        .isEqualTo(firstSecretId);
    assertThat(effectiveParams
            .path("_dispatchSecretBindings")
            .path("BUNDLE_CH_2")
            .path("secretVersionId")
            .asLong())
        .isEqualTo(secondSecretId);
    assertThat(effectiveParams
            .path("_frozenDispatchChannelsProtected")
            .path("BUNDLE_CH_1")
            .asText())
        .isNotBlank();
    assertThat(effectiveParams
            .path("_frozenDispatchChannelsProtected")
            .path("BUNDLE_CH_2")
            .asText())
        .isNotBlank();
    for (long fileId : List.of(991001L, 991002L)) {
      String taskPayload =
          jdbcTemplate.queryForObject(selectTaskPayloadSql, String.class, TENANT, fileId);
      var task = JsonUtils.fromJson(taskPayload, JsonNode.class);
      String expectedChannel = fileId == 991001L ? "BUNDLE_CH_1" : "BUNDLE_CH_2";
      Long expectedSecretId = fileId == 991001L ? firstSecretId : secondSecretId;
      assertThat(task.path("bundleSourceFileId").asLong()).isEqualTo(fileId);
      assertThat(task.path("bundleTargetRef").asText()).isEqualTo(expectedChannel);
      assertThat(task.path("_dispatchSecretBindings")
              .path(expectedChannel)
              .path("secretVersionId")
              .asLong())
          .isEqualTo(expectedSecretId);
      assertThat(task.path("_frozenDispatchChannelsProtected")
              .path(expectedChannel)
              .asText())
          .isNotBlank();
    }
  }

  @Test
  @DisplayName("普通分发不能通过启动参数伪造文件束文件与渠道")
  void normalDispatch_ignoresCallerBundleBindings() {
    final String insertChannelsSql = """
        insert into batch.file_channel_config
          (tenant_id, channel_code, channel_name, channel_type, config_json)
        values (?, 'BIND_EXPECTED', 'expected', 'LOCAL', '{}'::jsonb),
               (?, 'BIND_OTHER', 'other', 'LOCAL', '{}'::jsonb)
        """;
    final String selectTaskPayloadSql = """
        select jt.task_payload from batch.job_task jt
        join batch.job_instance ji on ji.tenant_id = jt.tenant_id and ji.id = jt.job_instance_id
        where ji.tenant_id = ? and ji.job_code = ?
        """;
    jdbcTemplate.update(insertChannelsSql, TENANT, TENANT);
    insertFileRecord(TENANT, 994001L);
    insertFileRecord(TENANT, 994002L);
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareBundleLaunchWithWorker(
        jdbcTemplate, TENANT, "DISPATCH", "DISPATCH");
    LaunchRequest request = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.EVENT)
        .requestId(seed.requestId())
        .traceId("trace-forged-bundle-binding")
        .params(Map.of(
            "fileId",
            994001,
            "channelCode",
            "BIND_EXPECTED",
            "bundleSourceFileId",
            994002,
            "bundleTargetRef",
            "BIND_OTHER"))
        .build();

    assertThat(workflowRunLaunchService.launch(request).instanceNo()).isNotBlank();
    List<String> taskPayloads =
        jdbcTemplate.queryForList(selectTaskPayloadSql, String.class, TENANT, seed.jobCode());
    assertThat(taskPayloads).isNotEmpty();
    for (String taskPayload : taskPayloads) {
      var task = JsonUtils.fromJson(taskPayload, JsonNode.class);
      assertThat(task.path("fileId").asLong()).isEqualTo(994001L);
      assertThat(task.path("channelCode").asText()).isEqualTo("BIND_EXPECTED");
      assertThat(task.has("bundleSourceFileId")).isFalse();
      assertThat(task.has("bundleTargetRef")).isFalse();
      assertThat(task.path("_frozenDispatchChannelsProtected").has("BIND_EXPECTED"))
          .isTrue();
      assertThat(task.path("_frozenDispatchChannelsProtected").has("BIND_OTHER"))
          .isFalse();
    }
  }

  @Test
  @DisplayName("普通分发目标渠道缺失时拒绝子作业，后续创建渠道不会改变已失败实例")
  void normalDispatch_rejectsMissingExplicitChannel() {
    final String insertChannelSql = """
        insert into batch.file_channel_config
          (tenant_id, channel_code, channel_name, channel_type, config_json)
        values (?, 'LATE_CHANNEL', 'late', 'LOCAL', '{}'::jsonb)
        """;
    final String selectWorkflowSql = """
        select wr.id, wr.run_status from batch.workflow_run wr
        join batch.trigger_request tr on tr.id = wr.trigger_request_id and tr.tenant_id = wr.tenant_id
        where tr.tenant_id = ? and tr.request_id = ?
        """;
    final String selectNodeSql = """
        select node_status, error_key from batch.workflow_node_run
        where workflow_run_id = ? and node_code = 'BUNDLE_JOB'
        """;
    final String selectChildStatusSql = """
        select request_status from batch.trigger_request
        where tenant_id = ? and parent_workflow_run_id = ? and parent_node_code = 'BUNDLE_JOB'
        """;
    final String countJobSql = """
        select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
        """;
    insertFileRecord(TENANT, 995001L);
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareBundleLaunchWithWorker(
        jdbcTemplate, TENANT, "DISPATCH", "DISPATCH");
    LaunchRequest request = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.EVENT)
        .requestId(seed.requestId())
        .traceId("trace-late-channel")
        .params(Map.of("fileId", 995001, "channelCode", "LATE_CHANNEL"))
        .build();

    assertThat(workflowRunLaunchService.launch(request).instanceNo()).isNotBlank();
    Map<String, Object> workflow =
        jdbcTemplate.queryForMap(selectWorkflowSql, TENANT, seed.requestId());
    assertThat(workflow).containsEntry("run_status", "FAILED");
    Long workflowId = ((Number) workflow.get("id")).longValue();
    assertThat(jdbcTemplate.queryForMap(selectNodeSql, workflowId))
        .containsEntry("node_status", "FAILED")
        .containsEntry("error_key", "error.file_channel.not_found");
    assertThat(jdbcTemplate.queryForObject(selectChildStatusSql, String.class, TENANT, workflowId))
        .isEqualTo("REJECTED");
    jdbcTemplate.update(insertChannelSql, TENANT);
    assertThat(jdbcTemplate.queryForObject(countJobSql, Integer.class, TENANT, seed.jobCode()))
        .isZero();
  }

  @Test
  @DisplayName("普通分发不因元数据中无关渠道的密钥缺失而拒绝有效目标")
  void normalDispatch_ignoresUnavailableUnselectedSecret() {
    final String insertSecretSql = """
        insert into batch.secret_version
          (tenant_id, secret_ref, secret_name, version_no, secret_status, current_version)
        values (?, 'selected/key', 'selected', 1, 'PUBLISHED', true) returning id
        """;
    final String insertChannelsSql = """
        insert into batch.file_channel_config
          (tenant_id, channel_code, channel_name, channel_type, config_json)
        values (?, 'SELECTED_CHANNEL', 'selected', 'SFTP', '{"secret_ref":"selected/key"}'::jsonb),
               (?, 'OTHER_CHANNEL', 'other', 'SFTP', '{"secret_ref":"other/no-version"}'::jsonb)
        """;
    final String selectTaskPayloadSql = """
        select jt.task_payload from batch.job_task jt
        join batch.job_instance ji on ji.tenant_id = jt.tenant_id and ji.id = jt.job_instance_id
        where ji.tenant_id = ? and ji.job_code = ?
        """;
    Long secretId = jdbcTemplate.queryForObject(insertSecretSql, Long.class, TENANT);
    jdbcTemplate.update(insertChannelsSql, TENANT, TENANT);
    insertFileRecord(TENANT, 996001L);
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareBundleLaunchWithWorker(
        jdbcTemplate, TENANT, "DISPATCH", "DISPATCH");
    LaunchRequest request = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.EVENT)
        .requestId(seed.requestId())
        .traceId("trace-unrelated-secret")
        .params(Map.of(
            "fileId",
            996001,
            "channelCode",
            "SELECTED_CHANNEL",
            "metadata",
            Map.of("targetRef", "OTHER_CHANNEL")))
        .build();

    assertThat(workflowRunLaunchService.launch(request).instanceNo()).isNotBlank();
    List<String> payloads =
        jdbcTemplate.queryForList(selectTaskPayloadSql, String.class, TENANT, seed.jobCode());
    assertThat(payloads).isNotEmpty();
    for (String payload : payloads) {
      var task = JsonUtils.fromJson(payload, JsonNode.class);
      assertThat(task.path("channelCode").asText()).isEqualTo("SELECTED_CHANNEL");
      var bindings = task.path("_dispatchSecretBindings");
      assertThat(bindings.path("SELECTED_CHANNEL").path("secretVersionId").asLong())
          .isEqualTo(secretId);
      assertThat(bindings.has("OTHER_CHANNEL")).isFalse();
    }
  }

  @Test
  @DisplayName("文件束目标渠道缺失时不生成部分冻结的作业实例")
  void bundleDispatch_rejectsMissingTargetWithoutJobInstance() {
    final String insertChannelSql = """
        insert into batch.file_channel_config
          (tenant_id, channel_code, channel_name, channel_type, config_json)
        values (?, 'BUNDLE_PRESENT', 'present', 'LOCAL', '{}'::jsonb)
        """;
    final String countJobSql = """
        select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
        """;
    final String selectWorkflowSql = """
        select wr.id, wr.run_status from batch.workflow_run wr
        join batch.trigger_request tr on tr.id = wr.trigger_request_id and tr.tenant_id = wr.tenant_id
        where tr.tenant_id = ? and tr.request_id = ?
        """;
    final String selectNodeSql = """
        select node_status, error_key from batch.workflow_node_run
        where workflow_run_id = ? and node_code = 'BUNDLE_JOB'
        """;
    final String selectChildStatusSql = """
        select request_status from batch.trigger_request
        where tenant_id = ? and parent_workflow_run_id = ? and parent_node_code = 'BUNDLE_JOB'
        """;
    jdbcTemplate.update(insertChannelSql, TENANT);
    insertFileRecord(TENANT, 992001L);
    insertFileRecord(TENANT, 992002L);
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareBundleLaunchWithWorker(
        jdbcTemplate, TENANT, "BUNDLE_DISPATCH", "DISPATCH");
    LaunchRequest request = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.EVENT)
        .requestId(seed.requestId())
        .traceId("trace-missing-channel")
        .params(Map.of(
            "bundleFiles",
            List.of(
                Map.of("sourceFileId", 992001, "targetRef", "BUNDLE_PRESENT"),
                Map.of("sourceFileId", 992002, "targetRef", "BUNDLE_MISSING"))))
        .build();

    var response = workflowRunLaunchService.launch(request);
    assertThat(response.instanceNo()).isNotBlank();
    Map<String, Object> workflow =
        jdbcTemplate.queryForMap(selectWorkflowSql, TENANT, seed.requestId());
    assertThat(workflow).containsEntry("run_status", "FAILED");
    Long workflowId = ((Number) workflow.get("id")).longValue();
    assertThat(jdbcTemplate.queryForMap(selectNodeSql, workflowId))
        .containsEntry("node_status", "FAILED")
        .containsEntry("error_key", "error.file_channel.not_found");
    assertThat(jdbcTemplate.queryForObject(selectChildStatusSql, String.class, TENANT, workflowId))
        .isEqualTo("REJECTED");
    assertThat(jdbcTemplate.queryForObject(countJobSql, Integer.class, TENANT, seed.jobCode()))
        .isZero();
  }

  @Test
  @DisplayName("文件束渠道的密钥版本缺失时子节点和 Workflow 失败收敛")
  void bundleDispatch_rejectsMissingSecretAndFinalizesWorkflow() {
    final String insertChannelSql = """
        insert into batch.file_channel_config
          (tenant_id, channel_code, channel_name, channel_type, config_json)
        values (?, 'BUNDLE_SECRET_MISSING', 'secure', 'SFTP',
                '{"secret_ref":"bundle/no-version"}'::jsonb)
        """;
    final String selectWorkflowSql = """
        select wr.id, wr.run_status from batch.workflow_run wr
        join batch.trigger_request tr on tr.id = wr.trigger_request_id and tr.tenant_id = wr.tenant_id
        where tr.tenant_id = ? and tr.request_id = ?
        """;
    final String selectNodeSql = """
        select node_status, error_code from batch.workflow_node_run
        where workflow_run_id = ? and node_code = 'BUNDLE_JOB'
        """;
    final String selectChildStatusSql = """
        select request_status from batch.trigger_request
        where tenant_id = ? and parent_workflow_run_id = ? and parent_node_code = 'BUNDLE_JOB'
        """;
    final String countJobSql = """
        select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
        """;
    jdbcTemplate.update(insertChannelSql, TENANT);
    insertFileRecord(TENANT, 993001L);
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareBundleLaunchWithWorker(
        jdbcTemplate, TENANT, "BUNDLE_DISPATCH", "DISPATCH");
    LaunchRequest request = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.EVENT)
        .requestId(seed.requestId())
        .traceId("trace-missing-secret")
        .params(Map.of(
            "bundleFiles",
            List.of(Map.of("sourceFileId", 993001, "targetRef", "BUNDLE_SECRET_MISSING"))))
        .build();

    var response = workflowRunLaunchService.launch(request);
    assertThat(response.instanceNo()).isNotBlank();
    Map<String, Object> workflow =
        jdbcTemplate.queryForMap(selectWorkflowSql, TENANT, seed.requestId());
    assertThat(workflow).containsEntry("run_status", "FAILED");
    Long workflowId = ((Number) workflow.get("id")).longValue();
    assertThat(jdbcTemplate.queryForMap(selectNodeSql, workflowId))
        .containsEntry("node_status", "FAILED")
        .containsEntry("error_code", "CREDENTIAL_REF_UNRESOLVED");
    assertThat(jdbcTemplate.queryForObject(selectChildStatusSql, String.class, TENANT, workflowId))
        .isEqualTo("REJECTED");
    assertThat(jdbcTemplate.queryForObject(countJobSql, Integer.class, TENANT, seed.jobCode()))
        .isZero();
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
        updateWorkflowNodeSql, JsonUtils.toJson(nodeParams), seed.workflowDefinitionId());
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
    Long triggerRequestId =
        jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, seed.requestId());
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
    jdbcTemplate.update(updateWorkflowEdgeSql, seed.workflowDefinitionId());
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

    Long rootRequestId =
        jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, seed.requestId());
    Long workflowRunId = jdbcTemplate.queryForObject(selectIdSql2, Long.class, rootRequestId);
    String selectedRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, workflowRunId);
    assertThat(selectedRunStatus).isEqualTo("FAILED");
    String selectedNodeStatus =
        jdbcTemplate.queryForObject(selectNodeStatusSql, String.class, workflowRunId);
    assertThat(selectedNodeStatus).isEqualTo("FAILED");
    String selectedNodeStatusRead2 =
        jdbcTemplate.queryForObject(selectNodeStatusSql2, String.class, workflowRunId);
    assertThat(selectedNodeStatusRead2).isEqualTo("SUCCESS");
    Long childRequestId = jdbcTemplate.queryForObject(selectIdSql3, Long.class, workflowRunId);
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
