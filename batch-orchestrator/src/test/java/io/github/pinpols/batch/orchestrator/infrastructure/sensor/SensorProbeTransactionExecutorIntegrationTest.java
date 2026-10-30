package io.github.pinpols.batch.orchestrator.infrastructure.sensor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.http.OutboundHttpResponse;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.sensor.HttpPollSensorPolicy;
import io.github.pinpols.batch.orchestrator.application.service.sensor.SensorContext;
import io.github.pinpols.batch.orchestrator.application.service.sensor.SensorPolicyRegistry;
import io.github.pinpols.batch.orchestrator.application.service.sensor.SensorProbeStatus;
import io.github.pinpols.batch.orchestrator.application.service.sensor.SensorStateMachine;
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskOutcomeService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowDagService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowNodeDispatchService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowNodeTerminalFinalizer;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.config.SensorProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.BatchDaySettleScheduler;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 验证事务边界，确保失败的 WAIT 转换可由下一轮轮询继续处理。 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    properties = "batch.sensor.enabled=false",
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("Sensor WAIT 探测事务:下游派发失败回滚并保留下一轮扫描资格")
class SensorProbeTransactionExecutorIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";

  private final SensorProbeTransactionExecutor transactionExecutor;

  private final WorkflowNodeRunMapper nodeRunMapper;

  private final JdbcTemplate jdbcTemplate;

  private final PlatformTransactionManager transactionManager;

  private final SensorProperties sensorProperties;

  private final OrchestratorGracefulShutdown gracefulShutdown;

  private final WorkflowRunMapper workflowRunMapper;

  private final WorkflowNodeTerminalFinalizer workflowNodeTerminalFinalizer;

  private final WorkflowNodeMapper workflowNodeMapper;

  private final TaskOutcomeService taskOutcomeService;

  private final ObjectMapper objectMapper;

  private final OrchestratorJobMappers jobMappers;

  private final OrchestratorWorkflowMappers workflowMappers;

  private final WorkflowDagService workflowDagService;

  private final ObjectProvider<WorkflowNodeDispatchService> dispatchServiceProvider;

  private final SensorPolicyRegistry sensorPolicyRegistry;

  private final WorkflowRunLaunchService workflowRunLaunchService;

  private final BatchDaySettleScheduler batchDaySettleScheduler;

  @MockitoBean
  private SensorStateMachine stateMachine;

  @Autowired
  SensorProbeTransactionExecutorIntegrationTest(
      SensorProbeTransactionExecutor transactionExecutor,
      WorkflowNodeRunMapper nodeRunMapper,
      JdbcTemplate jdbcTemplate,
      PlatformTransactionManager transactionManager,
      SensorProperties sensorProperties,
      OrchestratorGracefulShutdown gracefulShutdown,
      WorkflowRunMapper workflowRunMapper,
      WorkflowNodeTerminalFinalizer workflowNodeTerminalFinalizer,
      WorkflowNodeMapper workflowNodeMapper,
      TaskOutcomeService taskOutcomeService,
      ObjectMapper objectMapper,
      OrchestratorJobMappers jobMappers,
      OrchestratorWorkflowMappers workflowMappers,
      WorkflowDagService workflowDagService,
      ObjectProvider<WorkflowNodeDispatchService> dispatchServiceProvider,
      SensorPolicyRegistry sensorPolicyRegistry,
      WorkflowRunLaunchService workflowRunLaunchService,
      BatchDaySettleScheduler batchDaySettleScheduler) {
    this.transactionExecutor = transactionExecutor;
    this.nodeRunMapper = nodeRunMapper;
    this.jdbcTemplate = jdbcTemplate;
    this.transactionManager = transactionManager;
    this.sensorProperties = sensorProperties;
    this.gracefulShutdown = gracefulShutdown;
    this.workflowRunMapper = workflowRunMapper;
    this.workflowNodeTerminalFinalizer = workflowNodeTerminalFinalizer;
    this.workflowNodeMapper = workflowNodeMapper;
    this.taskOutcomeService = taskOutcomeService;
    this.objectMapper = objectMapper;
    this.jobMappers = jobMappers;
    this.workflowMappers = workflowMappers;
    this.workflowDagService = workflowDagService;
    this.dispatchServiceProvider = dispatchServiceProvider;
    this.sensorPolicyRegistry = sensorPolicyRegistry;
    this.workflowRunLaunchService = workflowRunLaunchService;
    this.batchDaySettleScheduler = batchDaySettleScheduler;
  }

  @Test
  @DisplayName("派发异常回滚后,新轮询器实例从数据库重新探测该 WAIT 节点")
  void shouldRetryAfterSchedulerRecreatedWhenDownstreamDispatchFails() {
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type, version,\s\
        enabled) values (?, ?, ?, 'DAG', 1, true) returning id\
        """;
    final String insertWorkflowRunSql = """
      insert into batch.workflow_run (tenant_id, workflow_definition_id, biz_date, run_status, trace_id)\s\
      values (?, ?, ?, 'RUNNING', ?) returning id\
      """;
    final String insertWorkflowNodeRunSql = """
      insert into batch.workflow_node_run (workflow_run_id, node_code, node_type, node_status)\s\
      values (?, 'WAIT_FILE', 'WAIT', 'RUNNING') returning id\
      """;
    final String updateWorkflowNodeRunSql = """
      update batch.workflow_node_run set node_status = 'SUCCESS', finished_at = current_timestamp where id = ?\
      """;
    final String updateWorkflowNodeRunSql2 = """
      update batch.workflow_node_run set sensor_next_probe_at = ? where id = ?
  """;
    final String selectNodeStatusSql = """
      select node_status, finished_at from batch.workflow_node_run where id = ?
  """;
    final String selectNodeStatusSql2 = """
      select node_status, finished_at, sensor_next_probe_at from batch.workflow_node_run where id = ?\
      """;

    long suffix = System.nanoTime();
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql,
        Long.class,
        TENANT,
        "WF-SENSOR-TX-" + suffix,
        "sensor tx test");
    Long workflowRunId = jdbcTemplate.queryForObject(
        insertWorkflowRunSql,
        Long.class,
        TENANT,
        workflowDefinitionId,
        LocalDate.of(2026, 5, 7),
        "trace-sensor-tx-" + suffix);
    Long nodeRunId =
        jdbcTemplate.queryForObject(insertWorkflowNodeRunSql, Long.class, workflowRunId);
    WorkflowNodeRunEntity nodeRun = new WorkflowNodeRunEntity();
    nodeRun.setId(nodeRunId);
    nodeRun.setWorkflowRunId(workflowRunId);
    nodeRun.setNodeCode("WAIT_FILE");
    nodeRun.setNodeType("WAIT");
    nodeRun.setNodeStatus("RUNNING");

    AtomicInteger attempts = new AtomicInteger();
    doAnswer(invocation -> {
          if (attempts.incrementAndGet() == 1) {
            jdbcTemplate.update(updateWorkflowNodeRunSql, nodeRunId);
            throw new IllegalStateException("downstream outbox unavailable");
          }
          jdbcTemplate.update(
              updateWorkflowNodeRunSql2, Timestamp.from(Instant.now().plusSeconds(60)), nodeRunId);
          return null;
        })
        .when(stateMachine)
        .probeAndAdvance(any(WorkflowNodeRunEntity.class), any(Instant.class));

    newSensorPollScheduler().scan();

    var persisted = jdbcTemplate.queryForMap(selectNodeStatusSql, nodeRunId);
    assertThat(persisted.get("node_status")).isEqualTo("RUNNING");
    assertThat(persisted.get("finished_at")).isNull();

    List<WorkflowNodeRunEntity> due = RlsTenantContextHolder.runWithTenant(
        TENANT, () -> nodeRunMapper.selectDueWaitNodes(Instant.now(), 10));
    assertThat(due).extracting(WorkflowNodeRunEntity::getId).contains(nodeRunId);

    // 重新创建轮询组件，确保恢复依赖持久化记录，而不是当前进程内存。
    newSensorPollScheduler().scan();

    assertThat(attempts).hasValue(2);
    var recovered = jdbcTemplate.queryForMap(selectNodeStatusSql2, nodeRunId);
    assertThat(recovered.get("node_status")).isEqualTo("RUNNING");
    assertThat(recovered.get("finished_at")).isNull();
    List<WorkflowNodeRunEntity> noLongerDue = RlsTenantContextHolder.runWithTenant(
        TENANT, () -> nodeRunMapper.selectDueWaitNodes(Instant.now(), 10));
    assertThat(noLongerDue).extracting(WorkflowNodeRunEntity::getId).doesNotContain(nodeRunId);
  }

  @Test
  @DisplayName("全局 WAIT 扫描按每个节点所属 Workflow 分别绑定租户上下文")
  void shouldProbeDueWaitNodesWithinTheirOwnTenantContext() {
    long suffix = System.nanoTime();
    Long tenantAWorkflowRunId = insertDueWaitNode("tenant-a", "SENSOR_A_" + suffix);
    Long tenantBWorkflowRunId = insertDueWaitNode("tenant-b", "SENSOR_B_" + suffix);
    Map<Long, String> probedTenantByWorkflowRun = new HashMap<>();
    doAnswer(invocation -> {
          WorkflowNodeRunEntity nodeRun = invocation.getArgument(0);
          probedTenantByWorkflowRun.put(nodeRun.getWorkflowRunId(), RlsTenantContextHolder.get());
          return null;
        })
        .when(stateMachine)
        .probeAndAdvance(any(WorkflowNodeRunEntity.class), any(Instant.class));

    newSensorPollScheduler().scan();

    assertThat(probedTenantByWorkflowRun)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(tenantAWorkflowRunId, "tenant-a", tenantBWorkflowRunId, "tenant-b"));
    assertThat(RlsTenantContextHolder.get()).isNull();
  }

  private Long insertDueWaitNode(String tenantId, String workflowCode) {
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type, version,\s\
        enabled) values (?, ?, ?, 'DAG', 1, true) returning id\
        """;
    final String insertWorkflowRunSql = """
        insert into batch.workflow_run (tenant_id, workflow_definition_id, biz_date, run_status, trace_id)\s\
        values (?, ?, ?, 'RUNNING', ?) returning id\
        """;
    final String insertWorkflowNodeRunSql = """
        insert into batch.workflow_node_run (workflow_run_id, node_code, node_type, node_status)\s\
        values (?, 'WAIT_FILE', 'WAIT', 'RUNNING')\
        """;

    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql,
        Long.class,
        tenantId,
        workflowCode,
        "sensor tenant isolation test");
    Long workflowRunId = jdbcTemplate.queryForObject(
        insertWorkflowRunSql,
        Long.class,
        tenantId,
        workflowDefinitionId,
        LocalDate.of(2026, 10, 17),
        "trace-" + workflowCode);
    jdbcTemplate.update(insertWorkflowNodeRunSql, workflowRunId);
    return workflowRunId;
  }

  private SensorPollScheduler newSensorPollScheduler() {
    return new SensorPollScheduler(
        transactionExecutor, sensorProperties, gracefulShutdown, workflowRunMapper);
  }

  @Test
  @DisplayName("真实 FILE_ARRIVAL 命中后推进 WAIT 到 END 并持久化节点输出")
  void shouldAdvanceWorkflowToEndWhenFileArrivalSensorMatches() {
    final String insertWorkflowDefinitionSql = """
          insert into batch.workflow_definition (
              tenant_id, workflow_code, workflow_name, workflow_type, version, enabled, calendar_code
          ) values (?, ?, ?, 'DAG', 1, true, 'JOB_CALENDAR') returning id
          """;
    final String insertWorkflowNodeSql = """
          insert into batch.workflow_node (
              tenant_id, workflow_definition_id, node_code, node_name, node_type, node_order,
              retry_policy, retry_max_count, timeout_seconds, enabled
          ) values (?, ?, 'START', 'start', 'START', 0, 'NONE', 0, 0, true)
          """;
    final String insertWorkflowNodeSql2 = """
        insert into batch.workflow_node (
            tenant_id, workflow_definition_id, node_code, node_name, node_type, node_order,
            node_params, retry_policy, retry_max_count, timeout_seconds, enabled
        ) values (?, ?, 'WAIT_FILE', 'wait for file', 'WAIT', 1, ?::jsonb, 'NONE', 0, 3600, true)
        """;
    final String insertWorkflowNodeSql3 = """
        insert into batch.workflow_node (
            tenant_id, workflow_definition_id, node_code, node_name, node_type, node_order,
            retry_policy, retry_max_count, timeout_seconds, enabled
        ) values (?, ?, 'END', 'end', 'END', 2, 'NONE', 0, 0, true)
        """;
    final String insertWorkflowEdgeSql = """
          insert into batch.workflow_edge (
              tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
          ) values (?, ?, 'START', 'WAIT_FILE', 'ALWAYS', true),
                   (?, ?, 'WAIT_FILE', 'END', 'SUCCESS', true)
          """;
    final String insertTriggerRequestSql = """
          insert into batch.trigger_request (
              tenant_id, request_id, trigger_type, workflow_definition_id, biz_date, dedup_key,
              request_status, trace_id
          ) values (?, ?, 'API', ?, ?, ?, 'ACCEPTED', ?)
          """;
    final String selectIdSql = """
        select id from batch.workflow_run where tenant_id = ? and trigger_request_id = (select id\s\
        from batch.trigger_request where tenant_id = ? and request_id = ?)\
        """;
    final String selectRelatedJobInstanceIdSql = """
        select related_job_instance_id from batch.workflow_run where tenant_id = ? and id = ?
    """;
    final String insertFileRecordSql = """
          insert into batch.file_record (
              tenant_id, file_category, file_name, file_format_type, storage_type, storage_path,
              source_type, file_status, biz_date, metadata_json, created_at
          ) values (?, 'INPUT', ?, 'DELIMITED', 'S3', ?, 'SFTP', 'RECEIVED', ?,
              jsonb_build_object('fileGroupCode', 'SENSOR_E2E', 'bundleWorkflowCode', ?,
                  'arrivalState', 'TRIGGERED'), now())
          """;
    final String updateFileRecordSql = """
        update batch.file_record set metadata_json = jsonb_set(metadata_json, '{bundleWorkflowCode}',\s\
        to_jsonb(?::text), true) where tenant_id = ? and file_name = ?\
        """;
    final String updateWorkflowNodeRunSql = """
        update batch.workflow_node_run set sensor_next_probe_at = current_timestamp - interval '1 second'\s\
        where id = ?\
        """;

    ensureBusinessCalendar();
    long suffix = System.nanoTime();
    String jobCode = "WF_SENSOR_E2E_" + suffix;
    String requestId = "req-sensor-e2e-" + suffix;
    String dedupKey = "dedup-sensor-e2e-" + suffix;
    LocalDate bizDate = LocalDate.of(2026, 10, 13);

    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, TENANT, jobCode, "sensor e2e workflow");
    String nodeParams = """
        {"sensor_type":"FILE_ARRIVAL",
         "sensor_spec":{"pattern":"sensor-e2e-*.csv","maxAgeSeconds":3600,
           "bindWorkflowBizDate":true,"fileGroupCode":"SENSOR_E2E","requireCompleteGroup":true},
         "timeout_seconds":3600,"poll_interval_seconds":30,"on_timeout":"FAIL"}
        """;
    jdbcTemplate.update(insertWorkflowNodeSql, TENANT, workflowDefinitionId);
    jdbcTemplate.update(insertWorkflowNodeSql2, TENANT, workflowDefinitionId, nodeParams);
    jdbcTemplate.update(insertWorkflowNodeSql3, TENANT, workflowDefinitionId);
    jdbcTemplate.update(
        insertWorkflowEdgeSql, TENANT, workflowDefinitionId, TENANT, workflowDefinitionId);
    jdbcTemplate.update(
        insertTriggerRequestSql,
        TENANT,
        requestId,
        workflowDefinitionId,
        bizDate,
        dedupKey,
        "trace-sensor-e2e-" + suffix);

    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(workflowDefinitionId)
        .bizDate(bizDate)
        .triggerType(TriggerType.API)
        .requestId(requestId)
        .traceId("trace-sensor-e2e-" + suffix)
        .params(Map.of())
        .build();
    workflowRunLaunchService.launch(launchRequest);
    Long workflowRunId =
        jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, TENANT, requestId);
    Long selectedRelatedJobInstanceId = jdbcTemplate.queryForObject(
        selectRelatedJobInstanceIdSql, Long.class, TENANT, workflowRunId);
    assertThat(selectedRelatedJobInstanceId).isNull();
    WorkflowNodeRunEntity waitRun =
        nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, "WAIT_FILE");
    assertThat(waitRun).isNotNull();
    assertThat(waitRun.getNodeStatus()).isEqualTo("RUNNING");

    String fileName = "sensor-e2e-" + suffix + ".csv";
    jdbcTemplate.update(
        insertFileRecordSql,
        TENANT,
        fileName,
        "s3://test/" + fileName,
        bizDate,
        jobCode + "_OTHER");

    SensorStateMachine realStateMachine = new SensorStateMachine(
        sensorPolicyRegistry,
        nodeRunMapper,
        workflowNodeMapper,
        workflowRunMapper,
        taskOutcomeService,
        objectMapper,
        jobMappers,
        workflowDagService,
        dispatchServiceProvider,
        workflowNodeTerminalFinalizer,
        Mockito.mock(SecretPayloadProtector.class));
    SensorProbeTransactionExecutor realExecutor =
        new SensorProbeTransactionExecutor(nodeRunMapper, realStateMachine);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    RlsTenantContextHolder.runWithTenant(
        TENANT,
        () -> transaction.executeWithoutResult(
            status -> realExecutor.probeOne(waitRun, Instant.now())));

    assertThat(nodeRunMapper
            .selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, "WAIT_FILE")
            .getNodeStatus())
        .isEqualTo("RUNNING");
    jdbcTemplate.update(updateFileRecordSql, jobCode, TENANT, fileName);
    jdbcTemplate.update(updateWorkflowNodeRunSql, waitRun.getId());
    RlsTenantContextHolder.runWithTenant(
        TENANT,
        () -> transaction.executeWithoutResult(
            status -> realExecutor.probeOne(waitRun, Instant.now())));

    assertThat(nodeRunMapper
            .selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, "WAIT_FILE")
            .getNodeStatus())
        .isEqualTo("SUCCESS");
    assertThat(nodeRunMapper
            .selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, "WAIT_FILE")
            .getOutput())
        .contains(fileName);
    assertThat(nodeRunMapper
            .selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, "END")
            .getNodeStatus())
        .isEqualTo("SUCCESS");
  }

  @Test
  @DisplayName("真实下游 outbox 写入失败时 WAIT 与任务派发整体回滚并可由后续扫描恢复")
  void shouldRetryRealTaskDispatchWhenOutboxInsertFails() {
    final String insertJobDefinitionSql = """
          insert into batch.job_definition (
              tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
              priority, queue_code, worker_group, trigger_mode, dag_enabled, shard_strategy,
              retry_policy, retry_max_count, timeout_seconds, enabled, version
          ) values (?, ?, ?, 'GENERAL', 'IT', 'MANUAL', 'UTC',
              5, 'q-it', ?, 'API', false, 'NONE', 'NONE', 0, 0, true, 1)
          """;
    final String insertWorkerRegistrySql = """
          insert into batch.worker_registry (
              tenant_id, worker_code, worker_group, capability_tags, status, heartbeat_at, current_load
          ) values (?, ?, ?, '[]'::jsonb, 'ONLINE', now(), 0)
          """;
    final String insertWorkflowDefinitionSql = """
          insert into batch.workflow_definition (
              tenant_id, workflow_code, workflow_name, workflow_type, version, enabled, calendar_code
          ) values (?, ?, ?, 'DAG', 1, true, 'JOB_CALENDAR') returning id
          """;
    final String insertWorkflowNodeSql = """
          insert into batch.workflow_node (
              tenant_id, workflow_definition_id, node_code, node_name, node_type, node_order,
              retry_policy, retry_max_count, timeout_seconds, enabled
          ) values (?, ?, 'START', 'start', 'START', 0, 'NONE', 0, 0, true)
          """;
    final String insertWorkflowNodeSql2 = """
        insert into batch.workflow_node (
            tenant_id, workflow_definition_id, node_code, node_name, node_type, node_order,
            node_params, retry_policy, retry_max_count, timeout_seconds, enabled
        ) values (?, ?, 'WAIT_FILE', 'wait for file', 'WAIT', 1, ?::jsonb, 'NONE', 0, 3600, true)
        """;
    final String insertWorkflowNodeSql3 = """
        insert into batch.workflow_node (
            tenant_id, workflow_definition_id, node_code, node_name, node_type,
            related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled
        ) values (?, ?, 'IMPORT_TASK', 'import task', 'JOB', ?, 2, 'NONE', 0, 3600, true)
        """;
    final String insertWorkflowEdgeSql = """
          insert into batch.workflow_edge (
              tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
          ) values (?, ?, 'START', 'WAIT_FILE', 'ALWAYS', true),
                   (?, ?, 'WAIT_FILE', 'IMPORT_TASK', 'SUCCESS', true)
          """;
    final String insertTriggerRequestSql = """
          insert into batch.trigger_request (
              tenant_id, request_id, trigger_type, workflow_definition_id, biz_date, dedup_key,
              request_status, trace_id
          ) values (?, ?, 'API', ?, ?, ?, 'ACCEPTED', ?)
          """;
    final String selectIdSql = """
      select id from batch.workflow_run where tenant_id = ? and trigger_request_id = (select id\s\
      from batch.trigger_request where tenant_id = ? and request_id = ?)\
      """;
    final String insertFileRecordSql = """
          insert into batch.file_record (
              tenant_id, file_category, file_name, file_format_type, storage_type, storage_path,
              source_type, file_status, biz_date, metadata_json, created_at
          ) values (?, 'INPUT', ?, 'DELIMITED', 'S3', ?, 'SFTP', 'RECEIVED', ?,
              jsonb_build_object('fileGroupCode', 'SENSOR_OUTBOX', 'arrivalState', 'TRIGGERED'), now())
          """;
    final String selectCountSql = """
              select count(*) from batch.job_task jt
              join batch.job_instance ji on ji.id = jt.job_instance_id
              where ji.tenant_id = ? and ji.job_code = ?
              """;
    final String selectCountSql2 = """
      select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
  """;
    final String selectCountSql3 = """
            select count(*) from batch.outbox_event oe
            join batch.job_task jt on oe.aggregate_type = 'JOB_TASK' and oe.aggregate_id = jt.id
            join batch.job_instance ji on ji.id = jt.job_instance_id
            where ji.tenant_id = ? and ji.job_code = ?
            """;
    final String selectIdSql2 = """
      select id from batch.job_instance where tenant_id = ? and job_code = ?
  """;
    final String selectCountSql4 = """
      select count(*) from batch.job_task where tenant_id = ? and job_instance_id = ?
  """;
    final String selectCountSql5 = """
      select count(*) from batch.job_partition where tenant_id = ? and job_instance_id = ?
  """;
    final String selectCountSql6 = """
            select count(*) from batch.outbox_event oe
            join batch.job_task jt on oe.aggregate_type = 'JOB_TASK' and oe.aggregate_id = jt.id
            where jt.tenant_id = ? and jt.job_instance_id = ?
            """;

    ensureBusinessCalendar();
    long suffix = System.nanoTime();
    String rootJobCode = "WF_SENSOR_OUTBOX_" + suffix;
    String taskJobCode = "TASK_SENSOR_OUTBOX_" + suffix;
    String requestId = "req-sensor-outbox-" + suffix;
    String dedupKey = "dedup-sensor-outbox-" + suffix;
    String workerGroup = "WG_SENSOR_OUTBOX_" + suffix;
    String triggerName = "sensor_outbox_fail_" + suffix;
    String functionName = "sensor_outbox_fail_fn_" + suffix;
    LocalDate bizDate = LocalDate.of(2026, 10, 13);

    jdbcTemplate.update(insertJobDefinitionSql, TENANT, taskJobCode, taskJobCode, workerGroup);
    jdbcTemplate.update(insertWorkerRegistrySql, TENANT, "wk-sensor-outbox-" + suffix, workerGroup);
    Long definitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, TENANT, rootJobCode, rootJobCode);
    String sensorParams = """
        {"sensor_type":"FILE_ARRIVAL",
         "sensor_spec":{"pattern":"sensor-outbox-*.csv","maxAgeSeconds":3600,
           "bindWorkflowBizDate":true,"fileGroupCode":"SENSOR_OUTBOX","requireCompleteGroup":true},
         "timeout_seconds":3600,"poll_interval_seconds":30,"on_timeout":"FAIL"}
        """;
    jdbcTemplate.update(insertWorkflowNodeSql, TENANT, definitionId);
    jdbcTemplate.update(insertWorkflowNodeSql2, TENANT, definitionId, sensorParams);
    jdbcTemplate.update(insertWorkflowNodeSql3, TENANT, definitionId, taskJobCode);
    jdbcTemplate.update(insertWorkflowEdgeSql, TENANT, definitionId, TENANT, definitionId);
    jdbcTemplate.update(
        insertTriggerRequestSql, TENANT, requestId, definitionId, bizDate, dedupKey, requestId);
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(definitionId)
        .bizDate(bizDate)
        .triggerType(TriggerType.API)
        .requestId(requestId)
        .traceId(requestId)
        .params(Map.of())
        .build();
    workflowRunLaunchService.launch(launchRequest);

    Long workflowRunId =
        jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, TENANT, requestId);
    WorkflowNodeRunEntity waitRun =
        nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, "WAIT_FILE");
    assertThat(waitRun.getNodeStatus()).isEqualTo("RUNNING");
    String fileName = "sensor-outbox-" + suffix + ".csv";
    jdbcTemplate.update(insertFileRecordSql, TENANT, fileName, "s3://test/" + fileName, bizDate);
    final String createFunctionSql = """
        create function batch.%s() returns trigger language plpgsql as $$
        begin
          if new.tenant_id = '%s' then
            raise exception 'injected workflow task outbox failure';
          end if;
          return new;
        end
        $$
        """.formatted(functionName, TENANT);
    jdbcTemplate.execute(createFunctionSql);
    final String createTriggerSql = """
        create trigger %s before insert on batch.outbox_event
        for each row execute function batch.%s()
        """.formatted(triggerName, functionName);
    jdbcTemplate.execute(createTriggerSql);
    SensorStateMachine realStateMachine = new SensorStateMachine(
        sensorPolicyRegistry,
        nodeRunMapper,
        workflowNodeMapper,
        workflowRunMapper,
        taskOutcomeService,
        objectMapper,
        jobMappers,
        workflowDagService,
        dispatchServiceProvider,
        workflowNodeTerminalFinalizer,
        Mockito.mock(SecretPayloadProtector.class));
    SensorProbeTransactionExecutor realExecutor =
        new SensorProbeTransactionExecutor(nodeRunMapper, realStateMachine);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    try {
      assertThatThrownBy(() -> RlsTenantContextHolder.runWithTenant(
              TENANT,
              () -> transaction.executeWithoutResult(
                  status -> realExecutor.probeOne(waitRun, Instant.now()))))
          .hasMessageContaining("injected workflow task outbox failure");
    } finally {
      final String dropTriggerSql = """
          drop trigger if exists %s on batch.outbox_event\
          """.formatted(triggerName);
      jdbcTemplate.execute(dropTriggerSql);
      final String dropFunctionSql = """
          drop function if exists batch.%s()\
          """.formatted(functionName);
      jdbcTemplate.execute(dropFunctionSql);
    }

    assertThat(nodeRunMapper
            .selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, "WAIT_FILE")
            .getNodeStatus())
        .isEqualTo("RUNNING");
    assertThat(nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, "IMPORT_TASK"))
        .isNull();
    Long selectedJobTaskCount =
        jdbcTemplate.queryForObject(selectCountSql, Long.class, TENANT, taskJobCode);
    assertThat(selectedJobTaskCount).isZero();
    Long selectedJobInstanceCount =
        jdbcTemplate.queryForObject(selectCountSql2, Long.class, TENANT, taskJobCode);
    assertThat(selectedJobInstanceCount).isZero();
    Long selectedOutboxEventCount =
        jdbcTemplate.queryForObject(selectCountSql3, Long.class, TENANT, taskJobCode);
    assertThat(selectedOutboxEventCount).isZero();

    RlsTenantContextHolder.runWithTenant(
        TENANT,
        () -> transaction.executeWithoutResult(
            status -> realExecutor.probeOne(waitRun, Instant.now())));

    assertThat(nodeRunMapper
            .selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, "WAIT_FILE")
            .getNodeStatus())
        .isEqualTo("SUCCESS");
    assertThat(nodeRunMapper
            .selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, "IMPORT_TASK")
            .getNodeStatus())
        .isEqualTo("READY");
    Long jobInstanceId = jdbcTemplate.queryForObject(selectIdSql2, Long.class, TENANT, taskJobCode);
    Long selectedJobTaskCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql4, Long.class, TENANT, jobInstanceId);
    assertThat(selectedJobTaskCountRead2).isEqualTo(1L);
    Long selectedJobPartitionCount =
        jdbcTemplate.queryForObject(selectCountSql5, Long.class, TENANT, jobInstanceId);
    assertThat(selectedJobPartitionCount).isEqualTo(1L);
    Long selectedOutboxEventCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql6, Long.class, TENANT, jobInstanceId);
    assertThat(selectedOutboxEventCountRead2).isEqualTo(1L);
  }

  @ParameterizedTest(name = "sensor={0}, first waiter={1}")
  @CsvSource({
    "FILE_ARRIVAL, SETTLEMENT_FIRST",
    "FILE_ARRIVAL, WAIT_RELEASE_FIRST",
    "HTTP_POLL, SETTLEMENT_FIRST",
    "HTTP_POLL, WAIT_RELEASE_FIRST",
    "KAFKA_OFFSET, SETTLEMENT_FIRST",
    "KAFKA_OFFSET, WAIT_RELEASE_FIRST"
  })
  @DisplayName("FILE_ARRIVAL、HTTP_POLL 与 KAFKA_OFFSET WAIT 释放 JOB 均受结算围栏保护")
  void shouldKeepCutoffInFlightWhenWaitReleasesJobDuringSettlement(
      String sensorType, String firstLockWaiter) throws Exception {
    final String selectAcquireBatchDaySettlementFenceSql = """
            select batch.acquire_batch_day_settlement_fence(?, ?, ?)
        """;
    final String selectPgBackendPidSql = """
            select pg_backend_pid()
        """;

    final String insertBusinessCalendarSql = """
            insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, enabled)\s\
            values (?, ?, ?, 'UTC', true)\
            """;
    final String insertJobDefinitionSql = """
    insert into batch.job_definition (
    tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
    priority, queue_code, worker_group, trigger_mode, dag_enabled, shard_strategy,
    retry_policy, retry_max_count, timeout_seconds, enabled, version, calendar_code
    ) values (?, ?, ?, 'GENERAL', 'IT', 'MANUAL', 'UTC', 5, 'q-it', ?, 'API', false,
    'NONE', 'NONE', 0, 0, true, 1, ?)
    """;
    final String insertWorkerRegistrySql = """
    insert into batch.worker_registry (
    tenant_id, worker_code, worker_group, capability_tags, status, heartbeat_at, current_load
    ) values (?, ?, ?, '[]'::jsonb, 'ONLINE', now(), 0)
    """;
    final String insertWorkflowDefinitionSql = """
    insert into batch.workflow_definition (
    tenant_id, workflow_code, workflow_name, workflow_type, version, enabled, calendar_code
    ) values (?, ?, ?, 'DAG', 1, true, ?) returning id
    """;
    final String insertWorkflowNodeSql = """
    insert into batch.workflow_node (
    tenant_id, workflow_definition_id, node_code, node_name, node_type, node_order,
    retry_policy, retry_max_count, timeout_seconds, enabled
    ) values (?, ?, 'START', 'start', 'START', 0, 'NONE', 0, 0, true)
    """;
    final String insertWorkflowNodeSql2 = """
        insert into batch.workflow_node (
            tenant_id, workflow_definition_id, node_code, node_name, node_type, node_order,
            node_params, retry_policy, retry_max_count, timeout_seconds, enabled
        ) values (?, ?, 'WAIT_FILE', 'wait for file', 'WAIT', 1, ?::jsonb, 'NONE', 0, 3600, true)
        """;
    final String insertWorkflowNodeSql3 = """
        insert into batch.workflow_node (
            tenant_id, workflow_definition_id, node_code, node_name, node_type,
            related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled
        ) values (?, ?, 'IMPORT_TASK', 'import task', 'JOB', ?, 2, 'NONE', 0, 3600, true)
        """;
    final String insertWorkflowNodeSql4 = """
        insert into batch.workflow_node (
            tenant_id, workflow_definition_id, node_code, node_name, node_type, node_order,
            retry_policy, retry_max_count, timeout_seconds, enabled
        ) values (?, ?, 'END', 'end', 'END', 3, 'NONE', 0, 0, true)
        """;
    final String insertWorkflowEdgeSql = """
    insert into batch.workflow_edge (
    tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
    ) values (?, ?, 'START', 'WAIT_FILE', 'ALWAYS', true),
    (?, ?, 'WAIT_FILE', 'IMPORT_TASK', 'SUCCESS', true),
    (?, ?, 'IMPORT_TASK', 'END', 'SUCCESS', true)
    """;
    final String insertBatchDayInstanceSql = """
    insert into batch.batch_day_instance (
    tenant_id, calendar_code, biz_date, day_status, open_at, cutoff_at,
    timezone_snapshot, dst_policy_snapshot, frozen, version, created_at, updated_at, dry_run
    ) values (?, ?, ?, 'OPEN', current_timestamp, current_timestamp + interval '1 hour',
    'UTC', 'gap=RUN_AT_NEXT_VALID_TIME;overlap=RUN_ONCE_EARLIER_OFFSET', false, 0,
    current_timestamp, current_timestamp, false)
    """;
    final String insertTriggerRequestSql = """
    insert into batch.trigger_request (
    tenant_id, request_id, trigger_type, workflow_definition_id, biz_date, dedup_key,
    request_status, trace_id
    ) values (?, ?, 'API', ?, ?, ?, 'ACCEPTED', ?)
    """;
    final String selectIdSql = """
    select id from batch.workflow_run where tenant_id = ? and trigger_request_id =
    (select id from batch.trigger_request where tenant_id = ? and request_id = ?)
    """;
    final String insertFileRecordSql = """
    insert into batch.file_record (
    tenant_id, file_category, file_name, file_format_type, storage_type, storage_path,
    source_type, file_status, biz_date, metadata_json, created_at
    ) values (?, 'INPUT', ?, 'DELIMITED', 'S3', ?, 'SFTP', 'RECEIVED', ?,
    jsonb_build_object('fileGroupCode', 'SENSOR_R11', 'arrivalState', 'TRIGGERED'), now())
    """;
    final String updateBatchDayInstanceSql = """
            update batch.batch_day_instance\s\
            set day_status = 'CUTOFF', cutoff_at = current_timestamp - interval '1 second' where tenant_id = ?\s\
            and calendar_code = ? and biz_date = ?\
            """;
    final String selectDayStatusSql = """
            select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = ?\s\
            and biz_date = ?\
            """;
    final String selectRunStatusSql = """
            select run_status from batch.workflow_run where tenant_id = ? and id = ?
        """;
    final String selectStatementSql = """
            select (output ->> 'currentOffset')::bigint from batch.workflow_node_run where id = ?
        """;
    final String selectCountSql = """
    select count(*) from batch.trigger_request where tenant_id = ?
    and parent_workflow_run_id = ? and parent_node_code = 'IMPORT_TASK'
    """;
    final String selectCountSql2 = """
            select count(*) from batch.workflow_node_run where workflow_run_id = ?
              and node_code = 'IMPORT_TASK' and node_status in ('READY', 'RUNNING')
              and job_instance_id is not null
            """;

    KafkaOffsetSensorPolicy kafkaOffsetPolicy = null;
    ensureBusinessCalendar();
    long suffix = System.nanoTime();
    String calendarCode = "SENSOR_R11_" + suffix;
    String jobCode = "TASK_SENSOR_R11_" + suffix;
    String workerGroup = "WG_SENSOR_R11_" + suffix;
    LocalDate bizDate = LocalDate.of(2026, 10, 17);
    String workflowCode = "WF_SENSOR_R11_" + suffix;

    jdbcTemplate.update(insertBusinessCalendarSql, TENANT, calendarCode, calendarCode);
    jdbcTemplate.update(
        insertJobDefinitionSql, TENANT, jobCode, jobCode, workerGroup, calendarCode);
    jdbcTemplate.update(insertWorkerRegistrySql, TENANT, "wk-sensor-r11-" + suffix, workerGroup);
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, TENANT, workflowCode, workflowCode, calendarCode);
    String sensorParams =
        switch (sensorType) {
          case "FILE_ARRIVAL" -> """
          {"sensor_type":"FILE_ARRIVAL",
           "sensor_spec":{"pattern":"sensor-r11-%s.csv","maxAgeSeconds":3600,
             "bindWorkflowBizDate":true,"fileGroupCode":"SENSOR_R11","requireCompleteGroup":true},
           "timeout_seconds":3600,"poll_interval_seconds":30,"on_timeout":"FAIL"}
          """.formatted(suffix);
          case "HTTP_POLL" -> """
          {"sensor_type":"HTTP_POLL",
           "sensor_spec":{"url":"https://sensor-r11.invalid/status",
             "matchExpr":"$.status==READY"},
           "timeout_seconds":3600,"poll_interval_seconds":30,"on_timeout":"FAIL"}
          """;
          case "KAFKA_OFFSET" -> {
            String topic = "sensor-r11-" + suffix;
            KafkaAdmin testKafkaAdmin = new KafkaAdmin(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()));
            publishKafkaOffset(testKafkaAdmin, topic);
            kafkaOffsetPolicy = new KafkaOffsetSensorPolicy(testKafkaAdmin, sensorProperties);
            yield """
            {"sensor_type":"KAFKA_OFFSET",
             "sensor_spec":{"topic":"%s","partition":0,"minOffset":1},
             "timeout_seconds":3600,"poll_interval_seconds":30,"on_timeout":"FAIL"}
            """.formatted(topic);
          }
          default -> throw new IllegalArgumentException("Unsupported test sensor: " + sensorType);
        };
    jdbcTemplate.update(insertWorkflowNodeSql, TENANT, workflowDefinitionId);
    jdbcTemplate.update(insertWorkflowNodeSql2, TENANT, workflowDefinitionId, sensorParams);
    jdbcTemplate.update(insertWorkflowNodeSql3, TENANT, workflowDefinitionId, jobCode);
    jdbcTemplate.update(insertWorkflowNodeSql4, TENANT, workflowDefinitionId);
    jdbcTemplate.update(
        insertWorkflowEdgeSql,
        TENANT,
        workflowDefinitionId,
        TENANT,
        workflowDefinitionId,
        TENANT,
        workflowDefinitionId);
    jdbcTemplate.update(insertBatchDayInstanceSql, TENANT, calendarCode, bizDate);

    String requestId = "req-sensor-r11-" + suffix;
    jdbcTemplate.update(
        insertTriggerRequestSql,
        TENANT,
        requestId,
        workflowDefinitionId,
        bizDate,
        "dedup-" + suffix,
        requestId);
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(workflowDefinitionId)
        .bizDate(bizDate)
        .triggerType(TriggerType.API)
        .requestId(requestId)
        .traceId(requestId)
        .params(Map.of())
        .build();
    workflowRunLaunchService.launch(launchRequest);
    Long workflowRunId =
        jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, TENANT, requestId);
    WorkflowNodeRunEntity waitRun =
        nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, "WAIT_FILE");
    assertThat(waitRun).isNotNull();
    assertThat(waitRun.getNodeStatus()).isEqualTo("RUNNING");
    if ("FILE_ARRIVAL".equals(sensorType)) {
      String fileName = "sensor-r11-" + suffix + ".csv";
      jdbcTemplate.update(insertFileRecordSql, TENANT, fileName, "s3://test/" + fileName, bizDate);
    }

    SensorPolicyRegistry racePolicyRegistry =
        switch (sensorType) {
          case "HTTP_POLL" ->
            new SensorPolicyRegistry(List.of(new HttpPollSensorPolicy(
                sensorProperties,
                objectMapper,
                request -> new OutboundHttpResponse(200, "{\"status\":\"READY\"}"))));
          case "KAFKA_OFFSET" -> new SensorPolicyRegistry(List.of(kafkaOffsetPolicy));
          default -> sensorPolicyRegistry;
        };
    SensorStateMachine realStateMachine = new SensorStateMachine(
        racePolicyRegistry,
        nodeRunMapper,
        workflowNodeMapper,
        workflowRunMapper,
        taskOutcomeService,
        objectMapper,
        jobMappers,
        workflowDagService,
        dispatchServiceProvider,
        workflowNodeTerminalFinalizer,
        Mockito.mock(SecretPayloadProtector.class));
    SensorProbeTransactionExecutor realExecutor =
        new SensorProbeTransactionExecutor(nodeRunMapper, realStateMachine);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    jdbcTemplate.update(updateBatchDayInstanceSql, TENANT, calendarCode, bizDate);
    try (Connection fence = jdbcTemplate.getDataSource().getConnection()) {
      fence.setAutoCommit(false);
      try (PreparedStatement statement =
          fence.prepareStatement(selectAcquireBatchDaySettlementFenceSql)) {
        statement.setString(1, TENANT);
        statement.setString(2, calendarCode);
        statement.setObject(3, bizDate);
        statement.executeQuery().close();
      }
      int blockerPid;
      try (var statement = fence.createStatement();
          var result = statement.executeQuery(selectPgBackendPidSql)) {
        result.next();
        blockerPid = result.getInt(1);
      }
      Future<?> settlement;
      Future<?> sensorRelease;
      if ("SETTLEMENT_FIRST".equals(firstLockWaiter)) {
        settlement = executor.submit(batchDaySettleScheduler::settle);
        awaitBlockedBy(blockerPid, 1);
        sensorRelease = submitWaitRelease(executor, transaction, realExecutor, waitRun);
      } else {
        sensorRelease = submitWaitRelease(executor, transaction, realExecutor, waitRun);
        awaitBlockedBy(blockerPid, 1);
        settlement = executor.submit(batchDaySettleScheduler::settle);
      }
      awaitBlockedBy(blockerPid, 2);
      fence.commit();
      settlement.get(10, TimeUnit.SECONDS);
      sensorRelease.get(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
      if (kafkaOffsetPolicy != null) {
        kafkaOffsetPolicy.closeAdminClient();
      }
    }

    String selectedDayStatus = jdbcTemplate.queryForObject(
        selectDayStatusSql, String.class, TENANT, calendarCode, bizDate);
    assertThat(selectedDayStatus).isEqualTo("IN_FLIGHT");
    String selectedRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, workflowRunId);
    assertThat(selectedRunStatus).isEqualTo("RUNNING");
    assertThat(nodeRunMapper
            .selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, "WAIT_FILE")
            .getNodeStatus())
        .isEqualTo("SUCCESS");
    if ("KAFKA_OFFSET".equals(sensorType)) {
      Long selectedStatement =
          jdbcTemplate.queryForObject(selectStatementSql, Long.class, waitRun.getId());
      assertThat(selectedStatement).isEqualTo(1L);
    }
    Integer selectedTriggerRequestCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, workflowRunId);
    assertThat(selectedTriggerRequestCount).isEqualTo(1);
    Integer selectedWorkflowNodeRunCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCount).isEqualTo(1);
  }

  @Test
  @DisplayName("超出整数范围的 Kafka 分区不能被截断为现有分区")
  void shouldRejectOverflowPartitionWithoutMatchingRealKafkaPartition() throws Exception {
    String topic = "sensor-partition-overflow-" + System.nanoTime();
    KafkaAdmin admin = new KafkaAdmin(
        Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()));
    publishKafkaOffset(admin, topic);
    KafkaOffsetSensorPolicy policy = new KafkaOffsetSensorPolicy(admin, new SensorProperties());
    try {
      SensorContext invalid = new SensorContext(
          TENANT,
          1L,
          Map.of("topic", topic, "partition", 4_294_967_296L, "minOffset", 1L),
          Map.of(),
          Duration.ofSeconds(30),
          null);
      var result = policy.probe(invalid);
      assertThat(result.status()).isEqualTo(SensorProbeStatus.ERROR);
      assertThat(result.errorKey()).isEqualTo("error.workflow.sensor_spec_invalid");
      SensorContext valid = new SensorContext(
          TENANT,
          1L,
          Map.of("topic", topic, "partition", 0, "minOffset", 1L),
          Map.of(),
          Duration.ofSeconds(30),
          null);
      assertThat(policy.probe(valid).status()).isEqualTo(SensorProbeStatus.MATCHED);
    } finally {
      policy.closeAdminClient();
    }
  }

  private void publishKafkaOffset(KafkaAdmin kafkaAdmin, String topic) throws Exception {
    kafkaAdmin.createOrModifyTopics(new NewTopic(topic, 1, (short) 1));
    Properties producerProperties = new Properties();
    producerProperties.putAll(kafkaAdmin.getConfigurationProperties());
    producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties)) {
      producer
          .send(new ProducerRecord<>(topic, 0, "sensor-r11", "ready"))
          .get(10, TimeUnit.SECONDS);
    }
  }

  private Future<?> submitWaitRelease(
      ExecutorService executor,
      TransactionTemplate transaction,
      SensorProbeTransactionExecutor realExecutor,
      WorkflowNodeRunEntity waitRun) {
    return executor.submit(() -> RlsTenantContextHolder.runWithTenant(
        TENANT,
        () -> transaction.executeWithoutResult(
            status -> realExecutor.probeOne(waitRun, Instant.now()))));
  }

  @Test
  @DisplayName("两个扫描者并发探测同一候选时只有先认领者推进 WAIT 节点")
  void shouldAllowOnlyOneConcurrentProbeForSameDueNode() throws Exception {
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type, version,\s\
        enabled) values (?, ?, ?, 'DAG', 1, true) returning id\
        """;
    final String insertWorkflowRunSql = """
        insert into batch.workflow_run (tenant_id, workflow_definition_id, biz_date, run_status, trace_id)\s\
        values (?, ?, ?, 'RUNNING', ?) returning id\
        """;
    final String insertWorkflowNodeRunSql = """
        insert into batch.workflow_node_run (workflow_run_id, node_code, node_type, node_status)\s\
        values (?, 'WAIT_FILE', 'WAIT', 'RUNNING') returning id\
        """;
    final String updateWorkflowNodeRunSql = """
        update batch.workflow_node_run set sensor_next_probe_at = ? where id = ?
    """;
    final String selectSensorNextProbeAtSql = """
        select sensor_next_probe_at from batch.workflow_node_run where id = ?
    """;

    long suffix = System.nanoTime();
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql,
        Long.class,
        TENANT,
        "WF-SENSOR-CLAIM-" + suffix,
        "sensor claim test");
    Long workflowRunId = jdbcTemplate.queryForObject(
        insertWorkflowRunSql,
        Long.class,
        TENANT,
        workflowDefinitionId,
        LocalDate.of(2026, 5, 7),
        "trace-sensor-claim-" + suffix);
    Long nodeRunId =
        jdbcTemplate.queryForObject(insertWorkflowNodeRunSql, Long.class, workflowRunId);
    WorkflowNodeRunEntity nodeRun = new WorkflowNodeRunEntity();
    nodeRun.setId(nodeRunId);
    nodeRun.setWorkflowRunId(workflowRunId);
    nodeRun.setNodeCode("WAIT_FILE");
    nodeRun.setNodeType("WAIT");
    nodeRun.setNodeStatus("RUNNING");

    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    CountDownLatch firstProbeEntered = new CountDownLatch(1);
    CountDownLatch allowFirstProbeToCommit = new CountDownLatch(1);
    CountDownLatch secondProbeStarted = new CountDownLatch(1);
    AtomicInteger probeCalls = new AtomicInteger();
    doAnswer(invocation -> {
          probeCalls.incrementAndGet();
          jdbcTemplate.update(
              updateWorkflowNodeRunSql, Timestamp.from(now.plusSeconds(60)), nodeRunId);
          firstProbeEntered.countDown();
          if (!allowFirstProbeToCommit.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("timed out waiting to release first probe");
          }
          return null;
        })
        .when(stateMachine)
        .probeAndAdvance(any(WorkflowNodeRunEntity.class), any(Instant.class));

    CompletableFuture<Void> first =
        CompletableFuture.runAsync(() -> transactionExecutor.probeOne(nodeRun, now));
    boolean firstEntered = firstProbeEntered.await(5, TimeUnit.SECONDS);
    if (!firstEntered && first.isCompletedExceptionally()) {
      first.join();
    }
    assertThat(firstEntered).isTrue();
    CompletableFuture<Void> second = CompletableFuture.runAsync(() -> {
      secondProbeStarted.countDown();
      transactionExecutor.probeOne(nodeRun, now);
    });
    assertThat(secondProbeStarted.await(5, TimeUnit.SECONDS)).isTrue();
    allowFirstProbeToCommit.countDown();
    CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);

    assertThat(probeCalls).hasValue(1);
    Timestamp selectedSensorNextProbeAt =
        jdbcTemplate.queryForObject(selectSensorNextProbeAtSql, Timestamp.class, nodeRunId);
    assertThat(selectedSensorNextProbeAt.toInstant()).isEqualTo(now.plusSeconds(60));
  }

  private void ensureBusinessCalendar() {
    final String insertBusinessCalendarSql = """
          insert into batch.business_calendar (
              tenant_id, calendar_code, calendar_name, timezone, enabled
          ) values (?, 'JOB_CALENDAR', 'Integration Job Calendar', 'UTC', true)
          on conflict (tenant_id, calendar_code) do nothing
          """;

    jdbcTemplate.update(insertBusinessCalendarSql, TENANT);
  }

  private void awaitBlockedBy(int blockerPid, int expectedWaiters) throws Exception {
    final String selectCountSql = """
        select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))
    """;

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Integer waiting = jdbcTemplate.queryForObject(selectCountSql, Integer.class, blockerPid);
      if (waiting != null && waiting >= expectedWaiters) return;
      Thread.sleep(10);
    }
    throw new AssertionError(
        "Expected " + expectedWaiters + " PostgreSQL transactions blocked by " + blockerPid);
  }
}
