package io.github.pinpols.batch.orchestrator.integration.support;

import io.github.pinpols.batch.common.enums.TriggerType;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 初始化最小的平台数据行，使 {@link io.github.pinpols.batch.orchestrator.service.LaunchService#launch}
 * 能走调度计划路径（无从 START 出发的 DAG 边 → {@link
 * io.github.pinpols.batch.orchestrator.application.plan.SchedulePlanBuilder}）。
 */
public final class LaunchIntegrationFixture {

  private LaunchIntegrationFixture() {}

  public record LaunchSeed(String jobCode, String requestId, String dedupKey, String workerCode) {}

  public record WorkflowLaunchSeed(
      String jobCode,
      Long workflowDefinitionId,
      String requestId,
      String dedupKey,
      String workerCode) {}

  public record WorkflowChildTriggerSeed(Long workflowRunId, Long triggerRequestId) {}

  /** 安全的运维投影，不会公开已持久化的启动载荷。 */
  public static WorkflowChildTriggerSeed prepareWorkflowChildTriggerRequestForDefinition(
      JdbcTemplate jdbc,
      String tenantId,
      Long jobDefinitionId,
      String jobCode,
      String suffix,
      LocalDate bizDate,
      TriggerType rootTriggerType) {
        final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (
            tenant_id, calendar_code, calendar_name, timezone, enabled
        ) values (?, 'JOB_CALENDAR', 'Integration Job Calendar', 'UTC', true)
        on conflict (tenant_id, calendar_code) do nothing
        """;
        final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (
            tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
            calendar_code
        ) values (?, ?, ?, 'DAG', 1, true, 'JOB_CALENDAR')
        returning id
        """;
        final String insertWorkflowNodeSql = """
        insert into batch.workflow_node (
            tenant_id, workflow_definition_id, node_code, node_name, node_type,
            related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled
        ) values
            (?, ?, 'START', 'Start', 'START', null, 0, 'NONE', 0, 0, true),
            (?, ?, 'JOB', 'Job', 'JOB', ?, 1, 'NONE', 0, 0, true),
            (?, ?, 'END', 'End', 'END', null, 2, 'NONE', 0, 0, true)
        """;
        final String insertWorkflowEdgeSql = """
        insert into batch.workflow_edge (
            tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
        ) values
            (?, ?, 'START', 'JOB', 'ALWAYS', true),
            (?, ?, 'JOB', 'END', 'SUCCESS', true)
        """;
        final String insertTriggerRequestSql = """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, workflow_definition_id, biz_date,
            dedup_key, request_status
        ) values (?, ?, ?, ?, ?, ?, 'LAUNCHED') returning id
        """;
        final String insertWorkflowRunSql = """
        insert into batch.workflow_run (
            tenant_id, workflow_definition_id, trigger_request_id, biz_date,
            run_status, current_node_code, workflow_version_snapshot
        ) values (?, ?, ?, ?, 'RUNNING', 'JOB', 1) returning id
        """;
        final String insertTriggerRequestSql2 = """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key,
            request_status, parent_workflow_run_id, parent_node_code
        ) values (?, ?, 'EVENT', ?, ?, ?, 'LAUNCHED', ?, 'JOB') returning id
        """;

    jdbc.update(insertBusinessCalendarSql, tenantId);
    Long workflowDefinitionId =
        jdbc.queryForObject(insertWorkflowDefinitionSql, Long.class, tenantId, "WF_" + suffix, "integration " + suffix);
    jdbc.update(
        insertWorkflowNodeSql,
        tenantId,
        workflowDefinitionId,
        tenantId,
        workflowDefinitionId,
        jobCode,
        tenantId,
        workflowDefinitionId);
    jdbc.update(insertWorkflowEdgeSql, tenantId, workflowDefinitionId, tenantId, workflowDefinitionId);
    String rootRequestId = "req-" + suffix;
    Long rootTriggerRequestId = jdbc.queryForObject(
        insertTriggerRequestSql,
        Long.class,
        tenantId,
        rootRequestId,
        rootTriggerType.code(),
        workflowDefinitionId,
        bizDate,
        "dedup-" + suffix);
    Long workflowRunId = jdbc.queryForObject(
        insertWorkflowRunSql, Long.class, tenantId, workflowDefinitionId, rootTriggerRequestId, bizDate);
    Long childTriggerRequestId = jdbc.queryForObject(
        insertTriggerRequestSql2,
        Long.class,
        tenantId,
        rootRequestId + "-child",
        jobCode,
        bizDate,
        "dedup-" + suffix + "-child",
        workflowRunId);
    return new WorkflowChildTriggerSeed(workflowRunId, childTriggerRequestId);
  }

  /** 已接受 Trigger 请求数量及近期启动延迟的低基数汇总。 */
  public static WorkflowChildTriggerSeed prepareWorkflowChildTriggerRequest(
      JdbcTemplate jdbc,
      String tenantId,
      WorkflowLaunchSeed workflow,
      LocalDate bizDate,
      TriggerType rootTriggerType) {
        final String updateTriggerRequestSql = """
        update batch.trigger_request
           set request_status = 'LAUNCHED', trigger_type = ?, biz_date = ?, updated_at = current_timestamp
         where tenant_id = ? and request_id = ? and workflow_definition_id = ?
        """;
    final String selectIdSql = """
        select id from batch.trigger_request where tenant_id = ? and request_id = ?
    """;
    final String insertWorkflowRunSql = """
        insert into batch.workflow_run (
            tenant_id, workflow_definition_id, trigger_request_id, biz_date,
            run_status, current_node_code, workflow_version_snapshot
        ) values (?, ?, ?, ?, 'RUNNING', 'JOB', 1)
        returning id
        """;
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key,
            request_status, parent_workflow_run_id, parent_node_code
        ) values (?, ?, 'EVENT', ?, ?, ?, 'LAUNCHED', ?, 'JOB') returning id
        """;

    jdbc.update(
        updateTriggerRequestSql,
        rootTriggerType.code(),
        bizDate,
        tenantId,
        workflow.requestId(),
        workflow.workflowDefinitionId());
    Long rootTriggerRequestId = jdbc.queryForObject(
        selectIdSql,
        Long.class,
        tenantId,
        workflow.requestId());
    Long workflowRunId = jdbc.queryForObject(
        insertWorkflowRunSql, Long.class, tenantId, workflow.workflowDefinitionId(), rootTriggerRequestId, bizDate);
    Long childTriggerRequestId = jdbc.queryForObject(
        insertTriggerRequestSql,
        Long.class,
        tenantId,
        workflow.requestId() + "-child",
        workflow.jobCode(),
        bizDate,
        workflow.dedupKey() + "-child",
        workflowRunId);
    return new WorkflowChildTriggerSeed(workflowRunId, childTriggerRequestId);
  }

  /**
   * 已领取的持久化 Workflow 启动意图，此时根运行尚未准入。
   * resetForRetry 会清除已持久化的分配信息。让下方用于
   */
  public static WorkflowLaunchSeed prepareWorkflowLaunchWithWorker(
      JdbcTemplate jdbc,
      String tenantId,
      String jobType,
      String workerGroup,
      TriggerType triggerType) {
    return prepareWorkflowLaunchWithWorker(
        jdbc, tenantId, jobType, workerGroup, triggerType, LocalDate.of(2026, 1, 15));
  }

  public static WorkflowLaunchSeed prepareWorkflowLaunchWithWorker(
      JdbcTemplate jdbc,
      String tenantId,
      String jobType,
      String workerGroup,
      TriggerType triggerType,
      LocalDate bizDate) {
        final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (
            tenant_id, calendar_code, calendar_name, timezone, enabled
        ) values (?, 'JOB_CALENDAR', 'Integration Job Calendar', 'UTC', true)
        on conflict (tenant_id, calendar_code) do nothing
        """;
        final String insertJobDefinitionSql = """
        insert into batch.job_definition (
            tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
            calendar_code, priority, queue_code, worker_group, trigger_mode, dag_enabled,
            shard_strategy, retry_policy, retry_max_count, timeout_seconds, enabled, version
        ) values (?, ?, ?, ?, 'IT', 'MANUAL', 'UTC', 'JOB_CALENDAR',
            5, 'q-it', ?, 'API', false, 'NONE', 'NONE', 0, 0, true, 1)
        returning id
        """;
        final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (
            tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
            calendar_code, single_job_definition_id
        ) values (?, ?, 'integration single-job workflow', 'DAG', 1, true,
            'JOB_CALENDAR', ?)
        returning id
        """;
        final String insertWorkflowNodeSql = """
        insert into batch.workflow_node (
            tenant_id, workflow_definition_id, node_code, node_name, node_type,
            related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled
        ) values
            (?, ?, 'START', 'Start', 'START', null, 0, 'NONE', 0, 0, true),
            (?, ?, 'JOB', 'Job', 'JOB', ?, 1, 'NONE', 0, 0, true),
            (?, ?, 'END', 'End', 'END', null, 2, 'NONE', 0, 0, true)
        """;
    final String insertWorkflowEdgeSql = """
        insert into batch.workflow_edge (
            tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
        ) values
            (?, ?, 'START', 'JOB', 'ALWAYS', true),
            (?, ?, 'JOB', 'END', 'SUCCESS', true)
        """;
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, workflow_definition_id, biz_date,
            dedup_key, request_status, trace_id
        ) values (?, ?, ?, ?, ?, ?, 'ACCEPTED', 'trace-it')
        """;
    final String insertWorkerRegistrySql = """
        insert into batch.worker_registry (
            tenant_id, worker_code, worker_group, capability_tags, status, heartbeat_at, current_load
        ) values (?, ?, ?, '[]'::jsonb, 'ONLINE', now(), 0)
        """;

    String suffix = Long.toUnsignedString(System.nanoTime());
    String jobCode = "IT_" + jobType + "_" + suffix;
    String workflowCode = "WF_" + jobCode;
    String requestId = "req-" + suffix;
    String dedupKey = "dedup-" + suffix;
    String workerCode = "wk-" + suffix;
    jdbc.update(insertBusinessCalendarSql, tenantId);
    Long jobDefinitionId = jdbc.queryForObject(
        insertJobDefinitionSql, Long.class, tenantId, jobCode, "integration " + jobCode, jobType, workerGroup);
    Long workflowDefinitionId =
        jdbc.queryForObject(insertWorkflowDefinitionSql, Long.class, tenantId, workflowCode, jobDefinitionId);
    jdbc.update(
        insertWorkflowNodeSql,
        tenantId,
        workflowDefinitionId,
        tenantId,
        workflowDefinitionId,
        jobCode,
        tenantId,
        workflowDefinitionId);
    jdbc.update(insertWorkflowEdgeSql, tenantId, workflowDefinitionId, tenantId, workflowDefinitionId);
    jdbc.update(
        insertTriggerRequestSql, tenantId, requestId, triggerType.code(), workflowDefinitionId, bizDate, dedupKey);
    jdbc.update(insertWorkerRegistrySql, tenantId, workerCode, workerGroup);
    return new WorkflowLaunchSeed(jobCode, workflowDefinitionId, requestId, dedupKey, workerCode);
  }

  public static WorkflowLaunchSeed prepareWorkflowLaunchWithoutWorker(
      JdbcTemplate jdbc,
      String tenantId,
      String jobType,
      String workerGroup,
      TriggerType triggerType) {
    return prepareWorkflowLaunchWithoutWorker(
        jdbc, tenantId, jobType, workerGroup, triggerType, LocalDate.of(2026, 1, 15));
  }

  public static WorkflowLaunchSeed prepareWorkflowLaunchWithoutWorker(
      JdbcTemplate jdbc,
      String tenantId,
      String jobType,
      String workerGroup,
      TriggerType triggerType,
      LocalDate bizDate) {
    final String deleteWorkerRegistrySql = """
        delete from batch.worker_registry where tenant_id = ? and worker_code = ?
    """;

    WorkflowLaunchSeed seed =
        prepareWorkflowLaunchWithWorker(jdbc, tenantId, jobType, workerGroup, triggerType, bizDate);
    jdbc.update(
        deleteWorkerRegistrySql,
        tenantId,
        seed.workerCode());
    return seed;
  }

  /**
   * 插入 job + workflow + trigger + 一个 ONLINE 状态的 Worker（在 {@code workerGroup} 中，匹配
   * job_definition.worker_group）。
   */
  public static LaunchSeed prepareLaunchWithWorker(
      JdbcTemplate jdbc,
      String tenantId,
      String jobType,
      String workerGroup,
      TriggerType triggerType) {
        final String insertJobDefinitionSql = """
        insert into batch.job_definition (
            tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
            priority, queue_code, worker_group, trigger_mode, dag_enabled, shard_strategy,
            retry_policy, retry_max_count, timeout_seconds, enabled, version
        ) values (?, ?, ?, ?, ?, 'MANUAL', 'UTC',
            5, 'q-it', ?, 'API', false, 'NONE',
            'NONE', 0, 0, true, 1)
        """;
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (
            tenant_id, workflow_code, workflow_name, workflow_type, version, enabled
        ) values (?, ?, 'integration wf', 'DAG', 1, true)
        """;
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key, request_status, trace_id
        ) values (?, ?, ?, ?, date '2026-01-15', ?, 'ACCEPTED', 'trace-it')
        """;
    final String insertWorkerRegistrySql = """
        insert into batch.worker_registry (
            tenant_id, worker_code, worker_group, capability_tags, status, heartbeat_at, current_load
        ) values (?, ?, ?, '[]'::jsonb, 'ONLINE', now(), 0)
        """;

    String suffix = Long.toUnsignedString(System.nanoTime());
    String jobCode = "IT_" + jobType + "_" + suffix;
    String requestId = "req-" + suffix;
    String dedupKey = "dedup-" + suffix;
    String workerCode = "wk-" + suffix;

    jdbc.update(insertJobDefinitionSql, tenantId, jobCode, "integration " + jobCode, jobType, "IT", workerGroup);

    jdbc.update(insertWorkflowDefinitionSql, tenantId, jobCode);

    jdbc.update(insertTriggerRequestSql, tenantId, requestId, triggerType.code(), jobCode, dedupKey);

    jdbc.update(insertWorkerRegistrySql, tenantId, workerCode, workerGroup);

    return new LaunchSeed(jobCode, requestId, dedupKey, workerCode);
  }

  /**
   * ADR-046 文件束:seed 一个 {@code shard_strategy=DYNAMIC} 的束作业(BUNDLE_IMPORT/EXPORT/DISPATCH)+ 对应
   * worker_group + trigger_request。束须 DYNAMIC 让 {@code BundlePartitionCountResolver} 按 {@code
   * params.bundleFiles} 数展开异构 partition。triggerType 固定 {@link TriggerType#EVENT}(束由文件到达事件触发)。
   */
  public static WorkflowLaunchSeed prepareBundleLaunchWithWorker(
      JdbcTemplate jdbc, String tenantId, String bundleJobType, String workerGroup) {
        final String insertJobDefinitionSql = """
        insert into batch.job_definition (
            tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone, calendar_code,
            priority, queue_code, worker_group, trigger_mode, dag_enabled, shard_strategy,
            retry_policy, retry_max_count, timeout_seconds, enabled, version
        ) values (?, ?, ?, ?, ?, 'MANUAL', 'UTC', 'IT_CALENDAR',
            5, 'q-it', ?, 'API', false, 'DYNAMIC',
            'NONE', 0, 0, true, 1)
        """;
        final String selectIdSql = """
            select id from batch.job_definition where tenant_id = ? and job_code = ?
        """;
        final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (
            tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
            calendar_code, single_job_definition_id
        ) values (?, ?, 'integration bundle wf', 'DAG', 1, true, 'IT_CALENDAR', ?)
        returning id
        """;
        final String insertWorkflowNodeSql = """
        insert into batch.workflow_node (
            tenant_id, workflow_definition_id, node_code, node_name, node_type,
            related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled
        ) values
            (?, ?, 'START', 'Start', 'START', null, 0, 'NONE', 0, 0, true),
            (?, ?, 'BUNDLE_JOB', 'Bundle Job', 'JOB', ?, 1, 'NONE', 0, 0, true),
            (?, ?, 'END', 'End', 'END', null, 2, 'NONE', 0, 0, true)
        """;
    final String insertWorkflowEdgeSql = """
        insert into batch.workflow_edge (
            tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
        ) values
            (?, ?, 'START', 'BUNDLE_JOB', 'ALWAYS', true),
            (?, ?, 'BUNDLE_JOB', 'END', 'SUCCESS', true)
        """;
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, workflow_definition_id, biz_date,
            dedup_key, request_status, trace_id
        ) values (?, ?, 'EVENT', ?, date '2026-01-15', ?, 'ACCEPTED', 'trace-it')
        """;
    final String insertWorkerRegistrySql = """
        insert into batch.worker_registry (
            tenant_id, worker_code, worker_group, capability_tags, status, heartbeat_at, current_load
        ) values (?, ?, ?, '[]'::jsonb, 'ONLINE', now(), 0)
        """;

    String suffix = Long.toUnsignedString(System.nanoTime());
    String jobCode = "IT_" + bundleJobType + "_" + suffix;
    String requestId = "req-" + suffix;
    String dedupKey = "dedup-" + suffix;
    String workerCode = "wk-" + suffix;

    jdbc.update(insertJobDefinitionSql, tenantId, jobCode, "integration " + jobCode, bundleJobType, "IT", workerGroup);

    Long jobDefinitionId = jdbc.queryForObject(
        selectIdSql,
        Long.class,
        tenantId,
        jobCode);
    Long workflowDefinitionId =
        jdbc.queryForObject(insertWorkflowDefinitionSql, Long.class, tenantId, "WF-" + jobCode, jobDefinitionId);
    jdbc.update(
        insertWorkflowNodeSql,
        tenantId,
        workflowDefinitionId,
        tenantId,
        workflowDefinitionId,
        jobCode,
        tenantId,
        workflowDefinitionId);
    jdbc.update(insertWorkflowEdgeSql, tenantId, workflowDefinitionId, tenantId, workflowDefinitionId);

    jdbc.update(insertTriggerRequestSql, tenantId, requestId, workflowDefinitionId, dedupKey);

    jdbc.update(insertWorkerRegistrySql, tenantId, workerCode, workerGroup);

    return new WorkflowLaunchSeed(jobCode, workflowDefinitionId, requestId, dedupKey, workerCode);
  }

  /**
   * 长 IT 套件单 JVM 中，后台调度可能把仅 seed 一次的 worker_registry 误判为 OFFLINE。 对租户下仍在参与任务认领的 ONLINE/OFFLINE
   * worker 复位心跳与时间戳对齐 DB，收敛跨用例串扰。（不触碰 DRAINING/DECOMMISSIONED）
   */
  public static void refreshAssignableWorkersForTenant(JdbcTemplate jdbc, String tenantId) {
    final String updateWorkerRegistrySql = """
        update batch.worker_registry
        set heartbeat_at = current_timestamp,
            status = 'ONLINE'
        where tenant_id = ?
          and status in ('ONLINE', 'OFFLINE')
        """;

    jdbc.update(updateWorkerRegistrySql, tenantId);
  }

  /** 与 {@link #prepareLaunchWithWorker} 相同但不插入 Worker 行 —— 用于”无可用容量”的调度场景。 */
  public static LaunchSeed prepareLaunchWithoutWorker(
      JdbcTemplate jdbc,
      String tenantId,
      String jobType,
      String orphanWorkerGroup,
      TriggerType triggerType) {
    final String insertJobDefinitionSql = """
        insert into batch.job_definition (
            tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
            priority, queue_code, worker_group, trigger_mode, dag_enabled, shard_strategy,
            retry_policy, retry_max_count, timeout_seconds, enabled, version
        ) values (?, ?, ?, ?, ?, 'MANUAL', 'UTC',
            5, 'q-it', ?, 'API', false, 'NONE',
            'NONE', 0, 0, true, 1)
        """;
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (
            tenant_id, workflow_code, workflow_name, workflow_type, version, enabled
        ) values (?, ?, 'integration wf', 'DAG', 1, true)
        """;
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key, request_status, trace_id
        ) values (?, ?, ?, ?, date '2026-01-15', ?, 'ACCEPTED', 'trace-it')
        """;

    String suffix = Long.toUnsignedString(System.nanoTime());
    String jobCode = "IT_" + jobType + "_" + suffix;
    String requestId = "req-" + suffix;
    String dedupKey = "dedup-" + suffix;

    jdbc.update(insertJobDefinitionSql, tenantId, jobCode, "integration " + jobCode, jobType, "IT", orphanWorkerGroup);

    jdbc.update(insertWorkflowDefinitionSql, tenantId, jobCode);

    jdbc.update(insertTriggerRequestSql, tenantId, requestId, triggerType.code(), jobCode, dedupKey);

    return new LaunchSeed(jobCode, requestId, dedupKey, null);
  }

  public static long countOutboxByEventType(JdbcTemplate jdbc, String tenantId, String eventType) {
    final String selectCountSql = """
        select count(*) from batch.outbox_event where tenant_id = ? and event_type = ?
    """;

    Long n = jdbc.queryForObject(
        selectCountSql,
        Long.class,
        tenantId,
        eventType);
    return n == null ? 0L : n;
  }

  public static Long selectDispatchedJobInstanceId(
      JdbcTemplate jdbc, String tenantId, String workflowRunId) {
    final String selectJobInstanceIdSql = """
        select node_run.job_instance_id
        from batch.workflow_node_run node_run
        join batch.workflow_run workflow_run on workflow_run.id = node_run.workflow_run_id
        where workflow_run.tenant_id = ?
          and workflow_run.id = ?
          and node_run.node_type = 'JOB'
          and node_run.job_instance_id is not null
        """;

    return jdbc.queryForObject(selectJobInstanceIdSql, Long.class, tenantId, Long.valueOf(workflowRunId));
  }

  public static long countOutboxForJobInstance(
      JdbcTemplate jdbc, String tenantId, long jobInstanceId) {
    final String selectCountSql = """
        select count(*) from batch.outbox_event oe join batch.job_task jt on oe.aggregate_type = 'JOB_TASK'\s\
        and oe.aggregate_id = jt.id where oe.tenant_id = ? and jt.tenant_id = ? and jt.job_instance_id = ?\
        """;

    Long n = jdbc.queryForObject(
        selectCountSql,
        Long.class,
        tenantId,
        tenantId,
        jobInstanceId);
    return n == null ? 0L : n;
  }
}
