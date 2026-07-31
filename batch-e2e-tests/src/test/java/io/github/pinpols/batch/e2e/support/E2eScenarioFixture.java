package io.github.pinpols.batch.e2e.support;

import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seeds the configured job/pipeline/workflow/request records so {@code LaunchService#launch} runs
 * scheduling against workers registered at runtime by worker loops (same tenant / worker group).
 *
 * <p>The pipeline definition is deliberately provisioned by the fixture, representing the
 * Console/admin configuration step. Worker execution must remain read-only for platform
 * definitions.
 */
public final class E2eScenarioFixture {

  private E2eScenarioFixture() {}

  public record LaunchSeed(String jobCode, String requestId, String dedupKey) {}

  public static final class LaunchPreparationSpec {
    private final JdbcTemplate jdbc;
    private final String tenantId;
    private final String jobType;
    private final String workerGroup;
    private final TriggerType triggerType;
    private String retryPolicy = "NONE";
    private int retryMaxCount = 0;
    private String shardStrategy = "NONE";

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
  }

  /**
   * Inserts job + pipeline definition + workflow + trigger. No {@code worker_registry} row — the
   * worker process registers on startup.
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
    String suffix = Long.toUnsignedString(System.nanoTime());
    String jobCode = "E2E_" + spec.jobType() + "_" + suffix;
    String requestId = "e2e-req-" + suffix;
    String dedupKey = "e2e-dedup-" + suffix;

    spec.jdbc()
        .update(
            """
                insert into batch.job_definition (
                    tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
                priority, queue_code, worker_group, trigger_mode, dag_enabled, shard_strategy,
                retry_policy, retry_max_count, timeout_seconds, enabled, version
            ) values (?, ?, ?, ?, ?, 'MANUAL', 'UTC',
                5, 'e2e-q', ?, 'API', false, ?,
                ?, ?, 0, true, 1)
            """,
            spec.tenantId(),
            jobCode,
            "e2e " + jobCode,
            spec.jobType(),
            "E2E",
            CodeNormalizer.toUpperOrNull(spec.workerGroup()),
            spec.shardStrategy(),
            spec.retryPolicy(),
            spec.retryMaxCount());

    provisionPipelineDefinition(spec.jdbc(), spec.tenantId(), jobCode, spec.workerGroup());

    spec.jdbc().update("""
            insert into batch.workflow_definition (
                tenant_id, workflow_code, workflow_name, workflow_type, version, enabled
            ) values (?, ?, 'e2e wf', 'DAG', 1, true)
            """, spec.tenantId(), jobCode);

    spec.jdbc()
        .update("""
            insert into batch.trigger_request (
                tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key, request_status, trace_id
            ) values (?, ?, ?, ?, date '2026-01-15', ?, 'ACCEPTED', 'e2e-trace')
            """, spec.tenantId(), requestId, spec.triggerType().code(), jobCode, dedupKey);

    return new LaunchSeed(jobCode, requestId, dedupKey);
  }

  /**
   * Provisions the pipeline definition owned by the Console/admin configuration path. Custom E2E
   * scenarios can reuse this when they seed their own job/workflow records instead of calling the
   * standard launch fixture.
   */
  public static void provisionPipelineDefinition(
      JdbcTemplate jdbc, String tenantId, String jobCode, String workerGroup) {
    String pipelineType = CodeNormalizer.toUpperOrNull(workerGroup);
    if (pipelineType == null || !List.of("IMPORT", "EXPORT", "DISPATCH").contains(pipelineType)) {
      // PROCESS tests provide custom step definitions because their compute plugin parameters are
      // part of the scenario. ATOMIC has no pipeline stage definition.
      return;
    }

    Long pipelineDefinitionId = jdbc.queryForObject(
        """
            insert into batch.pipeline_definition (
                tenant_id, job_code, pipeline_name, pipeline_type, biz_type, worker_group,
                version, enabled
            ) values (?, ?, ?, ?, 'E2E', ?, 1, true)
            returning id
            """,
        Long.class,
        tenantId,
        jobCode,
        "e2e " + pipelineType.toLowerCase(Locale.ROOT) + " pipeline",
        pipelineType,
        pipelineType);

    List<PipelineStepSeed> steps =
        switch (pipelineType) {
          case "IMPORT" ->
            List.of(
                step("IMPORT_RECEIVE", "RECEIVE", "{}"),
                step("IMPORT_PREPROCESS", "PREPROCESS", "{}"),
                step("IMPORT_PARSE", "PARSE", "{}"),
                step("IMPORT_VALIDATE", "VALIDATE", "{}"),
                step("IMPORT_LOAD", "LOAD", "{}"),
                step("IMPORT_FEEDBACK", "FEEDBACK", "{}"));
          case "EXPORT" ->
            List.of(
                step("EXPORT_PREPARE", "PREPARE", "{}"),
                step("EXPORT_GENERATE", "GENERATE", "{}"),
                step("EXPORT_STORE", "STORE", "{}"),
                step("EXPORT_REGISTER", "REGISTER", "{}"),
                step("EXPORT_COMPLETE", "COMPLETE", "{}"));
          case "DISPATCH" ->
            List.of(
                step("DISPATCH_PREPARE", "PREPARE", "{}"),
                step("DISPATCH_DISPATCH", "DISPATCH", "{}"),
                step("DISPATCH_ACK", "ACK", "{\"onSuccessNextStageCode\":\"COMPLETE\"}"),
                step("DISPATCH_RETRY", "RETRY", "{\"onFailureNextStageCode\":\"COMPENSATE\"}"),
                step("DISPATCH_COMPENSATE", "COMPENSATE", "{\"terminalOnSuccess\":true}"),
                step("DISPATCH_COMPLETE", "COMPLETE", "{\"terminalOnSuccess\":true}"));
          default -> List.of();
        };
    for (int i = 0; i < steps.size(); i++) {
      PipelineStepSeed step = steps.get(i);
      jdbc.update(
          """
          insert into batch.pipeline_step_definition (
              pipeline_definition_id, step_code, step_name, stage_code, step_order,
              impl_code, step_params, timeout_seconds, retry_policy, retry_max_count, enabled
          ) values (?, ?, ?, ?, ?, ?, ?::jsonb, 0, 'NONE', 0, true)
          """,
          pipelineDefinitionId,
          step.stepCode(),
          step.stepCode(),
          step.stageCode(),
          i + 1,
          step.stepCode(),
          step.stepParamsJson());
    }
  }

  private static PipelineStepSeed step(String stepCode, String stageCode, String stepParamsJson) {
    return new PipelineStepSeed(stepCode, stageCode, stepParamsJson);
  }

  private record PipelineStepSeed(String stepCode, String stageCode, String stepParamsJson) {}
}
