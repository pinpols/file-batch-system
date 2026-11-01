package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Nullables;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.JobDefinitionSpec;
import io.github.pinpols.batch.console.config.JobMonitoringDefaultsProperties;
import io.github.pinpols.batch.console.domain.job.application.contract.request.JobDependencyRuleRequest;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.param.JobDefinitionMaintenanceUpdateParam;
import io.github.pinpols.batch.console.domain.job.param.JobMonitoringPolicyUpsertParam;
import io.github.pinpols.batch.console.domain.job.view.JobDefinitionView;
import io.github.pinpols.batch.console.infrastructure.workflow.DefaultSingleJobWorkflowProvisioner;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 配置初始化中的 Job 定义规则：依赖顺序、依赖图、执行设置与监控策略。
 *
 * <p>由调用方的逐项 NESTED 事务包围；这里不新开事务，不自行登记结果或缓存失效。
 */
@Component
@RequiredArgsConstructor
final class TenantJobDefinitionApplySupport {

  private static final String INVALID_ARGUMENT_KEY = "error.common.invalid_argument";
  private static final Set<String> JOB_DEPENDENCY_BIZ_DATE_ALIGNMENTS =
      Set.of("SAME_DAY", "PREVIOUS_BIZ_DAY");

  private final JobDefinitionMapper jobDefinitionMapper;
  private final JobMonitoringDefaultsProperties monitoringDefaults;
  private final DefaultSingleJobWorkflowProvisioner singleJobWorkflowProvisioner;

  JobDefinitionView findJob(String tenantId, JobDefinitionSpec spec) {
    return jobDefinitionMapper.selectByUniqueKey(tenantId, spec.getJobCode());
  }

  void bindTenantContext(String tenantId) {
    jobDefinitionMapper.setTenantContext(tenantId);
  }

  void ensureManagedSingleJobWorkflows(List<JobDefinitionSpec> jobSpecs, String tenantId) {
    if (EmptyChecks.isNull(jobSpecs)) {
      return;
    }
    for (JobDefinitionSpec spec : jobSpecs) {
      if (!Texts.hasText(spec.getJobCode())) {
        continue;
      }
      JobDefinitionView job = jobDefinitionMapper.selectByUniqueKey(tenantId, spec.getJobCode());
      if (EmptyChecks.isNotNull(job)) {
        singleJobWorkflowProvisioner.provision(job);
      }
    }
  }

  static List<JobDefinitionSpec> orderJobDefinitionsByDependencies(List<JobDefinitionSpec> specs) {
    if (EmptyChecks.isNull(specs) || specs.size() < 2) {
      return specs;
    }

    Map<String, JobDefinitionSpec> jobsByCode = new LinkedHashMap<>();
    Map<String, Integer> inputOrder = new HashMap<>();
    for (int index = 0; index < specs.size(); index++) {
      JobDefinitionSpec spec = specs.get(index);
      String jobCode = spec.getJobCode();
      boolean jobCodeIsMissingOrDuplicated =
          !Texts.hasText(jobCode) || EmptyChecks.isNotNull(jobsByCode.putIfAbsent(jobCode, spec));
      if (jobCodeIsMissingOrDuplicated) {
        // 保持普通依赖写入使用的 graph → configuration-revision 锁顺序。
        return specs;
      }
      inputOrder.put(jobCode, index);
    }

    Map<String, List<String>> dependentsByUpstream = new HashMap<>();
    Map<String, Integer> dependencyCountByJob = new HashMap<>();
    for (String jobCode : jobsByCode.keySet()) {
      dependencyCountByJob.put(jobCode, 0);
    }
    for (JobDefinitionSpec spec : specs) {
      Set<String> uniqueUpstreams = new LinkedHashSet<>();
      for (String upstreamJobCode : dependencyCodesForOrdering(spec)) {
        boolean upstreamDependencyIsIneligible = EmptyChecks.isNull(upstreamJobCode)
            || !jobsByCode.containsKey(upstreamJobCode)
            || !uniqueUpstreams.add(upstreamJobCode);
        if (upstreamDependencyIsIneligible) {
          continue;
        }
        dependentsByUpstream
            .computeIfAbsent(upstreamJobCode, ignored -> new ArrayList<>())
            .add(spec.getJobCode());
        dependencyCountByJob.compute(spec.getJobCode(), (ignored, count) -> count + 1);
      }
    }

    PriorityQueue<String> readyJobs = new PriorityQueue<>(Comparator.comparingInt(inputOrder::get));
    dependencyCountByJob.forEach((jobCode, count) -> {
      if (count == 0) {
        readyJobs.add(jobCode);
      }
    });

    List<JobDefinitionSpec> ordered = new ArrayList<>(specs.size());
    while (!EmptyChecks.isEmpty(Objects.requireNonNull(readyJobs))) {
      String upstreamJobCode = readyJobs.remove();
      ordered.add(jobsByCode.get(upstreamJobCode));
      for (String downstreamJobCode :
          dependentsByUpstream.getOrDefault(upstreamJobCode, List.of())) {
        int remainingDependencies =
            dependencyCountByJob.compute(downstreamJobCode, (ignored, count) -> count - 1);
        if (remainingDependencies == 0) {
          readyJobs.add(downstreamJobCode);
        }
      }
    }

    // 重复或无效的标识仍按既定的请求项顺序处理。
    // 循环依赖仍按原请求顺序逐项校验，不在排序阶段替代写入校验。
    return ordered.size() == specs.size() ? ordered : specs;
  }

  private static List<String> dependencyCodesForOrdering(JobDefinitionSpec spec) {
    List<JobDependencyRuleRequest> rules = spec.getDependencyRules();
    if (EmptyChecks.isNotNull(rules)) {
      return rules.stream()
          .filter(EmptyChecks::isNotNull)
          .map(JobDependencyRuleRequest::upstreamJobCode)
          .map(CodeNormalizer::trimToNull)
          .filter(EmptyChecks::isNotNull)
          .toList();
    }
    String upstreamJobCode = CodeNormalizer.trimToNull(spec.getDependsOnJobCode());
    return EmptyChecks.isNull(upstreamJobCode) ? List.of() : List.of(upstreamJobCode);
  }

  void insertJobDefinition(String tenantId, JobDefinitionSpec spec, String operator) {
    List<JobDependencyRuleRequest> dependencyRules = resolveJobDependencyRules(spec, null);
    validateJobDependencyRules(tenantId, spec.getJobCode(), dependencyRules);
    String dependsOnJobCode = firstUpstreamCode(dependencyRules);
    JobDefinitionEntity entity = new JobDefinitionEntity();
    entity.setTenantId(tenantId);
    entity.setJobCode(spec.getJobCode());
    entity.setDependsOnJobCode(dependsOnJobCode);
    entity.setJobName(spec.getJobName());
    entity.setJobType(spec.getJobType());
    entity.setBizType(spec.getBizType());
    applyJobScheduleAndRouting(entity, spec);
    entity.setDagEnabled(EmptyChecks.isNotNull(spec.getDagEnabled()) && spec.getDagEnabled());
    entity.setShardStrategy(Nullables.coalesce(spec.getShardStrategy(), "NONE"));
    entity.setRetryPolicy(Nullables.coalesce(spec.getRetryPolicy(), "NONE"));
    entity.setRetryMaxCount(spec.getRetryMaxCount());
    entity.setTimeoutSeconds(spec.getTimeoutSeconds());
    int softRuntimeSeconds = resolveSoftRuntime(spec.getSoftRuntimeSeconds());
    int startGraceSeconds =
        resolveStartGrace(spec.getScheduleType(), dependsOnJobCode, spec.getStartGraceSeconds());
    LocalTime completionDeadlineLocalTime = spec.getCompletionDeadlineLocalTime();
    int dependencyCompletionWindowSeconds =
        valueOrZero(spec.getDependencyCompletionWindowSeconds());
    int completionDeadlineDayOffset = EmptyChecks.isNull(spec.getCompletionDeadlineLocalTime())
        ? 0
        : valueOrZero(spec.getCompletionDeadlineDayOffset());
    entity.setExecutionHandler(spec.getExecutionHandler());
    entity.setParamSchema(spec.getParamSchema());
    String defaultParams = TenantConfigMaskedParameters.resolve(spec.getDefaultParams(), null);
    entity.setDefaultParams(defaultParams);
    entity.setPriority(Nullables.coalesce(spec.getPriority(), 5));
    entity.setEnabled(EmptyChecks.isNotNull(spec.getEnabled()) && spec.getEnabled());
    entity.setDescription(spec.getDescription());
    String executionMode = Nullables.coalesce(spec.getExecutionMode(), "FULL");
    boolean incrementalModeIsMissingWatermarkField = "INCREMENTAL".equals(executionMode)
        && (EmptyChecks.isNull(spec.getWatermarkField())
            || EmptyChecks.isBlank(Objects.requireNonNull(spec.getWatermarkField())));
    if (incrementalModeIsMissingWatermarkField) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ResultCode.INVALID_ARGUMENT.detailKey(),
          "watermarkField is required when executionMode=INCREMENTAL for job " + spec.getJobCode());
    }
    entity.setExecutionMode(executionMode);
    entity.setWatermarkField(spec.getWatermarkField());
    entity.setCreatedBy(operator);
    entity.setUpdatedBy(operator);
    jobDefinitionMapper.insert(entity);
    replaceJobDependencyRules(tenantId, spec.getJobCode(), dependencyRules);
    validateMonitoringCompletionMode(
        completionDeadlineLocalTime, dependencyCompletionWindowSeconds);
    JobMonitoringPolicyUpsertParam monitoringPolicy = JobMonitoringPolicyUpsertParam.builder()
        .tenantId(tenantId)
        .jobDefinitionId(entity.getId())
        .softRuntimeSeconds(softRuntimeSeconds)
        .softRuntimeSeverity(severityOrWarn(spec.getSoftRuntimeSeverity()))
        .startGraceSeconds(startGraceSeconds)
        .startGraceSeverity(severityOrWarn(spec.getStartGraceSeverity()))
        .completionDeadlineLocalTime(completionDeadlineLocalTime)
        .completionDeadlineDayOffset(completionDeadlineDayOffset)
        .dependencyCompletionWindowSeconds(dependencyCompletionWindowSeconds)
        .completionDeadlineSeverity(severityOrWarn(spec.getCompletionDeadlineSeverity()))
        .updatedBy(operator)
        .build();
    jobDefinitionMapper.upsertJobMonitoringPolicy(monitoringPolicy);
  }

  void updateJobDefinition(JobDefinitionView existing, JobDefinitionSpec spec, String operator) {
    List<JobDependencyRuleRequest> dependencyRules = resolveJobDependencyRules(spec, existing);
    validateJobDependencyRules(existing.getTenantId(), existing.getJobCode(), dependencyRules);
    String dependsOnJobCode = firstUpstreamCode(dependencyRules);
    boolean hasDependencies = EmptyChecks.isNotEmpty(dependencyRules);
    LocalTime completionDeadlineLocalTime =
        EmptyChecks.isNull(spec.getCompletionDeadlineLocalTime())
            ? existing.getCompletionDeadlineLocalTime()
            : spec.getCompletionDeadlineLocalTime();
    Integer dependencyCompletionWindowSeconds = resolveMonitoringValue(
        spec.getDependencyCompletionWindowSeconds(),
        existing.getDependencyCompletionWindowSeconds());
    validateMonitoringCompletionMode(
        completionDeadlineLocalTime, dependencyCompletionWindowSeconds);
    JobDefinitionMaintenanceUpdateParam param = new JobDefinitionMaintenanceUpdateParam();
    param.setTenantId(existing.getTenantId());
    param.setJobCode(existing.getJobCode());
    param.setDependsOnJobCode(dependsOnJobCode);
    param.setTriggerMode(Nullables.coalesce(spec.getTriggerMode(), existing.getTriggerMode()));
    param.setDependencyCompletionWindowSeconds(
        hasDependencies ? dependencyCompletionWindowSeconds : 0);
    param.setJobName(Nullables.coalesce(spec.getJobName(), existing.getJobName()));
    String requestedQueueCode = CodeNormalizer.toConfigFormOrNull(spec.getQueueCode());
    String existingQueueCode = existing.getQueueCode();
    param.setQueueCode(Nullables.coalesce(requestedQueueCode, existingQueueCode));
    String requestedWorkerGroup = CodeNormalizer.toUpperOrNull(spec.getWorkerGroup());
    String existingWorkerGroup = existing.getWorkerGroup();
    param.setWorkerGroup(Nullables.coalesce(requestedWorkerGroup, existingWorkerGroup));
    param.setScheduleExpr(Nullables.coalesce(spec.getScheduleExpr(), existing.getScheduleExpr()));
    String requestedCalendarCode = CodeNormalizer.toConfigFormOrNull(spec.getCalendarCode());
    String existingCalendarCode = existing.getCalendarCode();
    param.setCalendarCode(Nullables.coalesce(requestedCalendarCode, existingCalendarCode));
    String requestedWindowCode = CodeNormalizer.toConfigFormOrNull(spec.getWindowCode());
    String existingWindowCode = existing.getWindowCode();
    param.setWindowCode(Nullables.coalesce(requestedWindowCode, existingWindowCode));
    param.setRetryPolicy(Nullables.coalesce(spec.getRetryPolicy(), existing.getRetryPolicy()));
    param.setRetryMaxCount(
        Nullables.coalesce(spec.getRetryMaxCount(), existing.getRetryMaxCount()));
    param.setTimeoutSeconds(
        Nullables.coalesce(spec.getTimeoutSeconds(), existing.getTimeoutSeconds()));
    param.setShardStrategy(
        Nullables.coalesce(spec.getShardStrategy(), existing.getShardStrategy()));
    param.setEnabled(Nullables.coalesce(spec.getEnabled(), existing.getEnabled()));
    param.setDescription(Nullables.coalesce(spec.getDescription(), existing.getDescription()));
    param.setExecutionMode(
        Nullables.coalesce(spec.getExecutionMode(), existing.getExecutionMode()));
    param.setWatermarkField(
        Nullables.coalesce(spec.getWatermarkField(), existing.getWatermarkField()));
    param.setExecutionHandler(
        Nullables.coalesce(spec.getExecutionHandler(), existing.getExecutionHandler()));
    param.setParamSchema(Nullables.coalesce(spec.getParamSchema(), existing.getParamSchema()));
    param.setPriority(Nullables.coalesce(spec.getPriority(), existing.getPriority()));
    if (EmptyChecks.isNotNull(spec.getDefaultParams())) {
      String defaultParams = TenantConfigMaskedParameters.resolve(
          spec.getDefaultParams(), existing.getDefaultParams());
      param.setDefaultParams(defaultParams);
      param.setDefaultParamsSpecified(true);
    }
    param.setUpdatedBy(operator);
    jobDefinitionMapper.updateJobDefinitionMaintenance(param);
    replaceJobDependencyRules(existing.getTenantId(), existing.getJobCode(), dependencyRules);
    updateJobMonitoringPolicy(existing, spec, param, completionDeadlineLocalTime, operator);
  }

  /** 导入时调度默认值与路由归一化在同一阶段完成，后续校验仍使用原始 spec。 */
  private void applyJobScheduleAndRouting(JobDefinitionEntity entity, JobDefinitionSpec spec) {
    String jobScheduleType = Nullables.coalesce(spec.getScheduleType(), "MANUAL");
    entity.setScheduleType(jobScheduleType);
    entity.setScheduleExpr(spec.getScheduleExpr());
    entity.setTimezone(Nullables.coalesce(spec.getTimezone(), CommonConstants.DEFAULT_TIMEZONE_ID));
    String requestedTriggerMode = spec.getTriggerMode();
    String defaultTriggerMode =
        switch (jobScheduleType) {
          case "CRON", "FIXED_RATE" -> "SCHEDULED";
          case "EVENT" -> "EVENT";
          default -> "MANUAL";
        };
    String triggerMode = Nullables.coalesce(requestedTriggerMode, defaultTriggerMode);
    entity.setTriggerMode(triggerMode);
    String normalizedWorkerGroup = CodeNormalizer.toUpperOrNull(spec.getWorkerGroup());
    entity.setWorkerGroup(normalizedWorkerGroup);
    String normalizedQueueCode = CodeNormalizer.toConfigFormOrNull(spec.getQueueCode());
    entity.setQueueCode(normalizedQueueCode);
    String normalizedCalendarCode = CodeNormalizer.toConfigFormOrNull(spec.getCalendarCode());
    entity.setCalendarCode(normalizedCalendarCode);
    String normalizedWindowCode = CodeNormalizer.toConfigFormOrNull(spec.getWindowCode());
    entity.setWindowCode(normalizedWindowCode);
  }

  private void updateJobMonitoringPolicy(
      JobDefinitionView existing,
      JobDefinitionSpec spec,
      JobDefinitionMaintenanceUpdateParam param,
      LocalTime completionDeadlineLocalTime,
      String operator) {
    JobMonitoringPolicyUpsertParam.JobMonitoringPolicyUpsertParamBuilder monitoringPolicyBuilder =
        JobMonitoringPolicyUpsertParam.builder();
    String policyTenantId = existing.getTenantId();
    monitoringPolicyBuilder.tenantId(policyTenantId);
    Long policyJobDefinitionId = existing.getId();
    monitoringPolicyBuilder.jobDefinitionId(policyJobDefinitionId);
    Integer requestedSoftRuntimeSeconds = spec.getSoftRuntimeSeconds();
    Integer existingSoftRuntimeSeconds = existing.getSoftRuntimeSeconds();
    Integer softRuntimeSeconds =
        resolveMonitoringValue(requestedSoftRuntimeSeconds, existingSoftRuntimeSeconds);
    monitoringPolicyBuilder.softRuntimeSeconds(softRuntimeSeconds);
    String requestedSoftRuntimeSeverity = spec.getSoftRuntimeSeverity();
    String existingSoftRuntimeSeverity = existing.getSoftRuntimeSeverity();
    String softRuntimeSeverity =
        resolveMonitoringSeverity(requestedSoftRuntimeSeverity, existingSoftRuntimeSeverity);
    monitoringPolicyBuilder.softRuntimeSeverity(softRuntimeSeverity);
    Integer requestedStartGraceSeconds = spec.getStartGraceSeconds();
    Integer existingStartGraceSeconds = existing.getStartGraceSeconds();
    Integer startGraceSeconds =
        resolveMonitoringValue(requestedStartGraceSeconds, existingStartGraceSeconds);
    monitoringPolicyBuilder.startGraceSeconds(startGraceSeconds);
    String requestedStartGraceSeverity = spec.getStartGraceSeverity();
    String existingStartGraceSeverity = existing.getStartGraceSeverity();
    String startGraceSeverity =
        resolveMonitoringSeverity(requestedStartGraceSeverity, existingStartGraceSeverity);
    monitoringPolicyBuilder.startGraceSeverity(startGraceSeverity);
    monitoringPolicyBuilder.completionDeadlineLocalTime(completionDeadlineLocalTime);
    Integer requestedCompletionDeadlineDayOffset = spec.getCompletionDeadlineDayOffset();
    Integer existingCompletionDeadlineDayOffset = existing.getCompletionDeadlineDayOffset();
    Integer completionDeadlineDayOffset = resolveCompletionDeadlineDayOffset(
        completionDeadlineLocalTime,
        requestedCompletionDeadlineDayOffset,
        existingCompletionDeadlineDayOffset);
    monitoringPolicyBuilder.completionDeadlineDayOffset(completionDeadlineDayOffset);
    monitoringPolicyBuilder.dependencyCompletionWindowSeconds(
        param.getDependencyCompletionWindowSeconds());
    String requestedCompletionDeadlineSeverity = spec.getCompletionDeadlineSeverity();
    String existingCompletionDeadlineSeverity = existing.getCompletionDeadlineSeverity();
    String completionDeadlineSeverity = resolveMonitoringSeverity(
        requestedCompletionDeadlineSeverity, existingCompletionDeadlineSeverity);
    monitoringPolicyBuilder.completionDeadlineSeverity(completionDeadlineSeverity);
    monitoringPolicyBuilder.updatedBy(operator);
    JobMonitoringPolicyUpsertParam monitoringPolicy = monitoringPolicyBuilder.build();
    jobDefinitionMapper.upsertJobMonitoringPolicy(monitoringPolicy);
  }

  private List<JobDependencyRuleRequest> resolveJobDependencyRules(
      JobDefinitionSpec spec, JobDefinitionView existing) {
    List<JobDependencyRuleRequest> requestedRules = spec.getDependencyRules();
    if (EmptyChecks.isNotNull(requestedRules)) {
      List<JobDependencyRuleRequest> normalized = normalizeJobDependencyRules(requestedRules);
      String legacyUpstream = CodeNormalizer.trimToNull(spec.getDependsOnJobCode());
      boolean legacyUpstreamDoesNotMatchDependencies = Texts.hasText(legacyUpstream)
          && (EmptyChecks.isEmpty(Objects.requireNonNull(normalized))
              || !legacyUpstream.equals(normalized.getFirst().upstreamJobCode()));
      if (legacyUpstreamDoesNotMatchDependencies) {
        throw invalidJobDependency();
      }
      return normalized;
    }

    String legacyUpstream = CodeNormalizer.trimToNull(spec.getDependsOnJobCode());
    if (Texts.hasText(legacyUpstream)) {
      return List.of(new JobDependencyRuleRequest(legacyUpstream, "SAME_DAY"));
    }
    if (EmptyChecks.isNull(existing) || EmptyChecks.isNull(existing.getJobDependencyRules())) {
      return List.of();
    }
    return existing.getJobDependencyRules().stream()
        .map(rule -> new JobDependencyRuleRequest(rule.upstreamJobCode(), rule.bizDateAlignment()))
        .toList();
  }

  private static List<JobDependencyRuleRequest> normalizeJobDependencyRules(
      List<JobDependencyRuleRequest> rules) {
    if (rules.size() > 32) {
      throw invalidJobDependency();
    }
    Set<String> upstreamCodes = new LinkedHashSet<>();
    List<JobDependencyRuleRequest> normalized = new ArrayList<>(rules.size());
    for (JobDependencyRuleRequest rule : rules) {
      if (EmptyChecks.isNull(rule)) {
        throw invalidJobDependency();
      }
      String upstreamCode = CodeNormalizer.trimToNull(rule.upstreamJobCode());
      String alignment = null;
      if (EmptyChecks.isNotNull(rule.bizDateAlignment())) {
        String trimmedAlignment = rule.bizDateAlignment().trim();
        alignment = trimmedAlignment.toUpperCase(Locale.ROOT);
      }
      boolean dependencyRuleIsInvalid = !Texts.hasText(upstreamCode)
          || !upstreamCode.matches("^[a-zA-Z][a-zA-Z0-9_-]{0,63}$")
          || !Texts.hasText(alignment)
          || !JOB_DEPENDENCY_BIZ_DATE_ALIGNMENTS.contains(alignment)
          || !upstreamCodes.add(upstreamCode);
      if (dependencyRuleIsInvalid) {
        throw invalidJobDependency();
      }
      normalized.add(new JobDependencyRuleRequest(upstreamCode, alignment));
    }
    return List.copyOf(normalized);
  }

  private void validateJobDependencyRules(
      String tenantId, String downstreamJobCode, List<JobDependencyRuleRequest> rules) {
    if (EmptyChecks.isEmpty(Objects.requireNonNull(rules))) {
      return;
    }

    JobDefinitionMapper jobMapper = jobDefinitionMapper;
    jobMapper.lockDependencyGraph(tenantId);
    Map<String, Set<String>> graph = new HashMap<>();
    for (JobDefinitionMapper.JobDependencyEdge edge : jobMapper.selectAllDependencies(tenantId)) {
      if (!downstreamJobCode.equals(edge.downstreamJobCode())) {
        graph
            .computeIfAbsent(edge.downstreamJobCode(), ignored -> new LinkedHashSet<>())
            .add(edge.upstreamJobCode());
      }
    }
    for (JobDependencyRuleRequest rule : rules) {
      String upstreamJobCode = rule.upstreamJobCode();
      boolean dependencyWouldBeInvalid = downstreamJobCode.equals(upstreamJobCode)
          || EmptyChecks.isNull(jobMapper.selectByUniqueKey(tenantId, upstreamJobCode))
          || reachesDependency(graph, upstreamJobCode, downstreamJobCode, new HashSet<>());
      if (dependencyWouldBeInvalid) {
        throw invalidJobDependency();
      }
    }
  }

  private void replaceJobDependencyRules(
      String tenantId, String downstreamJobCode, List<JobDependencyRuleRequest> rules) {
    jobDefinitionMapper.deleteDependencies(tenantId, downstreamJobCode);
    if (EmptyChecks.isNotEmpty(rules)) {
      jobDefinitionMapper.insertDependencyRules(tenantId, downstreamJobCode, rules);
    }
  }

  private static String firstUpstreamCode(List<JobDependencyRuleRequest> rules) {
    return EmptyChecks.isEmpty(rules) ? null : rules.getFirst().upstreamJobCode();
  }

  private static BizException invalidJobDependency() {
    return BizException.of(ResultCode.INVALID_ARGUMENT, INVALID_ARGUMENT_KEY);
  }

  private static boolean reachesDependency(
      Map<String, Set<String>> graph, String current, String target, Set<String> visited) {
    if (current.equals(target)) {
      return true;
    }
    if (!visited.add(current)) {
      return false;
    }
    for (String upstream : graph.getOrDefault(current, Set.of())) {
      if (reachesDependency(graph, upstream, target, visited)) {
        return true;
      }
    }
    return false;
  }

  private int resolveStartGrace(String scheduleType, String dependsOnJobCode, Integer requested) {
    if (EmptyChecks.isNotNull(requested)) {
      return requested;
    }
    return "CRON".equals(scheduleType) || Texts.hasText(dependsOnJobCode)
        ? monitoringDefaults.getStartGraceSeconds()
        : 0;
  }

  private static Integer resolveCompletionDeadlineDayOffset(
      LocalTime deadlineTime, Integer requestedOffset, Integer existingOffset) {
    if (EmptyChecks.isNull(deadlineTime)) {
      return 0;
    }
    return EmptyChecks.isNull(requestedOffset) ? valueOrZero(existingOffset) : requestedOffset;
  }

  private int resolveSoftRuntime(Integer requested) {
    return EmptyChecks.isNull(requested) ? monitoringDefaults.getSoftRuntimeSeconds() : requested;
  }

  /** 配置包与 API 使用相同的完成期限互斥规则，运行资格由持久事实决定。 */
  private static void validateMonitoringCompletionMode(
      LocalTime completionDeadlineLocalTime, Integer dependencyCompletionWindowSeconds) {
    boolean relativeWindowConfigured = valueOrZero(dependencyCompletionWindowSeconds) > 0;
    if (EmptyChecks.isNotNull(completionDeadlineLocalTime) && relativeWindowConfigured) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.job.monitoring_completion_modes_exclusive");
    }
  }

  private static Integer resolveMonitoringValue(Integer requested, Integer existing) {
    return valueOrZero(EmptyChecks.isNull(requested) ? existing : requested);
  }

  private static Integer valueOrZero(Integer value) {
    return EmptyChecks.isNull(value) ? 0 : value;
  }

  private static String resolveMonitoringSeverity(String requested, String existing) {
    return severityOrWarn(EmptyChecks.isNull(requested) ? existing : requested);
  }

  private static String severityOrWarn(String severity) {
    return EmptyChecks.isBlank(severity) ? "WARN" : severity;
  }
}
