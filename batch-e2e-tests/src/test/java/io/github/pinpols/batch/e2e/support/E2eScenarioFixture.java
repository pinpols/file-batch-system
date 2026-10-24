package io.github.pinpols.batch.e2e.support;

import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 为 Job、其受管单作业 Workflow 和以 Workflow 为根的请求写入夹具，使运行时能够
 * 针对 Worker 循环注册的 Worker 调度（同租户、同 Worker 组）。
 *
 * <p>Pipeline 阶段从系统目录解析。此夹具不得创建租户自有 Pipeline 定义，也不得将 Job
 * 用作顶层触发目标。
 */
public final class E2eScenarioFixture {

  private E2eScenarioFixture() {}

  public record LaunchSeed(
      String jobCode, Long workflowDefinitionId, String requestId, String dedupKey) {}

  public static final class LaunchPreparationSpec {
    private final JdbcTemplate jdbc;
    private final String tenantId;
    private final String jobType;
    private final String workerGroup;
    private final TriggerType triggerType;
    private String retryPolicy = "NONE";
    private int retryMaxCount = 0;
    private String shardStrategy = "NONE";
    private boolean dryRun;
    private LocalDate bizDate = LocalDate.of(2026, 1, 15);

    public LaunchPreparationSpec(
        JdbcTemplate jdbc,
        String tenantId,
        String jobType,
        String workerGroup,
        TriggerType triggerType) {
      this.jdbc = jdbc;
      this.tenantId = tenantId;
      this.jobType = jobType;
      this.workerGroup = workerGroup;
      this.triggerType = triggerType;
    }

    public LaunchPreparationSpec retryPolicy(String retryPolicy) {
      this.retryPolicy = retryPolicy;
      return this;
    }

    public LaunchPreparationSpec retryMaxCount(int retryMaxCount) {
      this.retryMaxCount = retryMaxCount;
      return this;
    }

    public LaunchPreparationSpec shardStrategy(String shardStrategy) {
      this.shardStrategy = shardStrategy;
      return this;
    }

    public LaunchPreparationSpec dryRun(boolean dryRun) {
      this.dryRun = dryRun;
      return this;
    }

    public LaunchPreparationSpec bizDate(LocalDate bizDate) {
      this.bizDate = bizDate;
      return this;
    }

    private JdbcTemplate jdbc() {
      return jdbc;
    }

    private String tenantId() {
      return tenantId;
    }

    private String jobType() {
      return jobType;
    }

    private String workerGroup() {
      return workerGroup;
    }

    private TriggerType triggerType() {
      return triggerType;
    }

    private String retryPolicy() {
      return retryPolicy;
    }

    private int retryMaxCount() {
      return retryMaxCount;
    }

    private String shardStrategy() {
      return shardStrategy;
    }

    private boolean dryRun() {
      return dryRun;
    }

    private LocalDate bizDate() {
      return bizDate;
    }
  }

  /**
   * 写入 Job、单作业 Workflow、Workflow 节点和 Trigger。不会写入 {@code worker_registry} 记录，
   * Worker 进程会在启动时自行注册。
   */
  public static LaunchSeed prepareLaunchWithoutPreSeededWorker(
      JdbcTemplate jdbc,
      String tenantId,
      String jobType,
      String workerGroup,
      TriggerType triggerType) {
    return prepareLaunchWithoutPreSeededWorker(
        new LaunchPreparationSpec(jdbc, tenantId, jobType, workerGroup, triggerType));
  }

  public static LaunchSeed prepareLaunchWithoutPreSeededWorker(
      JdbcTemplate jdbc,
      String tenantId,
      String jobType,
      String workerGroup,
      TriggerType triggerType,
      LocalDate bizDate) {
    return prepareLaunchWithoutPreSeededWorker(
        new LaunchPreparationSpec(jdbc, tenantId, jobType, workerGroup, triggerType)
            .bizDate(bizDate));
  }

  public static LaunchSeed prepareBundleLaunchWithoutPreSeededWorker(
      JdbcTemplate jdbc, String tenantId, String bundleJobType, String workerGroup) {
    return prepareLaunchWithoutPreSeededWorker(
        new LaunchPreparationSpec(jdbc, tenantId, bundleJobType, workerGroup, TriggerType.EVENT)
            .shardStrategy("DYNAMIC"));
  }

  /**
   * Same as {@link #prepareLaunchWithoutPreSeededWorker(JdbcTemplate, String, String, String,
   * TriggerType)} but allows orchestrator-level retry policy (FIXED / EXPONENTIAL) for failure-path
   * E2E.
   */
  public static LaunchSeed prepareLaunchWithoutPreSeededWorker(LaunchPreparationSpec spec) {
    final String insertJobDefinitionSql = """
                insert into batch.job_definition (
                    tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
                priority, queue_code, worker_group, trigger_mode, dag_enabled, shard_strategy,
                retry_policy, retry_max_count, timeout_seconds, enabled, version
            ) values (?, ?, ?, ?, ?, 'MANUAL', 'UTC',
                5, 'e2e-q', ?, 'API', false, ?,
                ?, ?, 0, true, 1)
            returning id
            """;
    final String insertBusinessCalendarSql = """
            insert into batch.business_calendar (
                tenant_id, calendar_code, calendar_name, timezone, enabled
            ) values (?, 'e2e_calendar', 'E2E business calendar', 'UTC', true)
            on conflict (tenant_id, calendar_code) do nothing
            """;
    final String insertWorkflowDefinitionSql = """
            insert into batch.workflow_definition (
                tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
                calendar_code, single_job_definition_id
            ) values (?, ?, 'e2e wf', 'DAG', 1, true, 'e2e_calendar', ?)
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
            ) values (?, ?, 'START', 'JOB', 'ALWAYS', true),
                     (?, ?, 'JOB', 'END', 'SUCCESS', true)
            """;
    final String insertTriggerRequestSql = """
            insert into batch.trigger_request (
                tenant_id, request_id, trigger_type, workflow_definition_id, biz_date,
                dedup_key, request_status, trace_id, dry_run
            ) values (?, ?, ?, ?, ?, ?, 'ACCEPTED', 'e2e-trace', ?)
            """;

    String suffix = Long.toUnsignedString(System.nanoTime());
    String jobCode = "E2E_" + spec.jobType() + "_" + suffix;
    String requestId = "e2e-req-" + suffix;
    String dedupKey = "e2e-dedup-" + suffix;

    Long jobDefinitionId = spec.jdbc()
        .queryForObject(
            insertJobDefinitionSql,
            Long.class,
            spec.tenantId(),
            jobCode,
            "e2e " + jobCode,
            spec.jobType(),
            "E2E",
            CodeNormalizer.toUpperOrNull(spec.workerGroup()),
            spec.shardStrategy(),
            spec.retryPolicy(),
            spec.retryMaxCount());

    spec.jdbc().update(insertBusinessCalendarSql, spec.tenantId());

    Long workflowDefinitionId =
        spec.jdbc().queryForObject(insertWorkflowDefinitionSql, Long.class, spec.tenantId(), jobCode, jobDefinitionId);

    spec.jdbc()
        .update(
            insertWorkflowNodeSql,
            spec.tenantId(),
            workflowDefinitionId,
            spec.tenantId(),
            workflowDefinitionId,
            jobCode,
            spec.tenantId(),
            workflowDefinitionId);
    spec.jdbc()
        .update(insertWorkflowEdgeSql, spec.tenantId(), workflowDefinitionId, spec.tenantId(), workflowDefinitionId);

    spec.jdbc()
        .update(
            insertTriggerRequestSql,
            spec.tenantId(),
            requestId,
            spec.triggerType().code(),
            workflowDefinitionId,
            spec.bizDate(),
            dedupKey,
            spec.dryRun());

    return new LaunchSeed(jobCode, workflowDefinitionId, requestId, dedupKey);
  }
}
