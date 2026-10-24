package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.console.domain.workflow.application.command.WorkflowVersionSnapshotCommand;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.LogSanitizer;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Nullables;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.AlertRoutingSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.BatchWindowSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.BusinessCalendarSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.FileChannelSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.FileTemplateSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.InitMode;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.JobDefinitionSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.ResourceQueueSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.TenantQuotaPolicySpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.WorkflowDefinitionSpec;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse.ItemStats;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse.ItemStatsAccumulator;
import io.github.pinpols.batch.console.config.JobMonitoringDefaultsProperties;
import io.github.pinpols.batch.console.domain.job.application.contract.request.JobDependencyRuleRequest;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.param.JobDefinitionMaintenanceUpdateParam;
import io.github.pinpols.batch.console.domain.job.param.JobMonitoringPolicyUpsertParam;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.WorkflowDefinitionSaveRequest;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowDefinitionUpsertParam;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowDefinitionVersionUpdateParam;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowEdgeUpsertParam;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowNodeUpsertParam;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowDefinitionQuery;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowNodeQuery;
import io.github.pinpols.batch.console.infrastructure.workflow.DefaultSingleJobWorkflowProvisioner;
import io.github.pinpols.batch.console.infrastructure.workflow.WorkflowDefinitionDagInspector;
import io.github.pinpols.batch.console.infrastructure.workflow.WorkflowDefinitionWriteSupport;
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
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * P2-3 god-class-decomposition extract: 把 TenantConfigInit 服务的 10 类 spec apply + 对应
 * insert/update/upsert helper(共 ~500 行) 集中在一处,主 service 只剩 batchInit 入口 + initForTenant 编排。
 *
 * <p>SpecHandler / ApplyContext / applySpecs 模板也一并搬过来 — 它们紧紧绑定 10 个 apply 方法,留在主 service 反而把
 * strategy 抽象暴露给 batchInit 不必关心的层次。
 */
@Slf4j
@Component
public class TenantConfigInitApplyHandlers {

  private static final String KEY_ENABLED = "enabled";
  private static final String KEY_ID = "id";
  private static final String INVALID_ARGUMENT_KEY = "error.common.invalid_argument";
  private static final Set<String> JOB_DEPENDENCY_BIZ_DATE_ALIGNMENTS =
      Set.of("SAME_DAY", "PREVIOUS_BIZ_DAY");

  private final TenantDefinitionConfigMappers definitionMappers;
  private final PlatformTransactionManager transactionManager;
  private final TenantFileConfigApplySupport fileConfigSupport;
  private final TenantOperationalConfigApplySupport operationalConfigSupport;
  private final JobMonitoringDefaultsProperties monitoringDefaults;
  private final WorkflowDefinitionWriteSupport workflowWriteSupport;
  private final DefaultSingleJobWorkflowProvisioner singleJobWorkflowProvisioner;
  private final WorkflowDefinitionDagInspector workflowDagInspector;

  public TenantConfigInitApplyHandlers(
      TenantDefinitionConfigMappers definitionMappers,
      PlatformTransactionManager transactionManager,
      TenantFileConfigApplySupport fileConfigSupport,
      TenantOperationalConfigApplySupport operationalConfigSupport,
      JobMonitoringDefaultsProperties monitoringDefaults,
      WorkflowDefinitionWriteSupport workflowWriteSupport,
      DefaultSingleJobWorkflowProvisioner singleJobWorkflowProvisioner,
      WorkflowDefinitionDagInspector workflowDagInspector) {
    this.definitionMappers = definitionMappers;
    this.transactionManager = transactionManager;
    this.fileConfigSupport = fileConfigSupport;
    this.operationalConfigSupport = operationalConfigSupport;
    this.monitoringDefaults = monitoringDefaults;
    this.workflowWriteSupport = workflowWriteSupport;
    this.singleJobWorkflowProvisioner = singleJobWorkflowProvisioner;
    this.workflowDagInspector = workflowDagInspector;
  }

  void bindTenantContext(String tenantId) {
    definitionMappers.jobDefinition.setTenantContext(tenantId);
  }

  void ensureManagedSingleJobWorkflows(List<JobDefinitionSpec> jobSpecs, String tenantId) {
    if (EmptyChecks.isNull(jobSpecs)) {
      return;
    }
    for (JobDefinitionSpec spec : jobSpecs) {
      if (!Texts.hasText(spec.getJobCode())) {
        continue;
      }
      JobDefinitionEntity job =
          definitionMappers.jobDefinition.selectByUniqueKey(tenantId, spec.getJobCode());
      if (EmptyChecks.isNotNull(job)) {
        singleJobWorkflowProvisioner.provision(job);
      }
    }
  }

  /** 上下文：一次 apply 调用所需的四个不变量，避免在 10 个 apply* 方法中重复传参。 */
  record ApplyContext(String tenantId, InitMode mode, String operator, boolean dryRun) {}

  /**
   * 三元消费者：用于 SpecHandler.update，因 Java 标准库无 TriConsumer。 签名：(ApplyContext, spec, existingRecord) →
   * void
   */
  @FunctionalInterface
  private interface SpecUpdater<T, E> {
    void update(ApplyContext ctx, T spec, E existing);
  }

  /**
   * 单一配置类型的处理策略。
   *
   * <p>{@link #of} 用于 insert/update 行为不同的类型(作业定义、工作流定义); {@link #upsertable} 用于 insert/update 共用同一
   * upsert 调用的 7 个简单类型。
   */
  private interface SpecHandler<T, E> {
    String typeName();

    String code(T spec);

    Optional<E> find(String tenantId, T spec);

    void insert(ApplyContext ctx, T spec);

    void update(ApplyContext ctx, T spec, E existing);

    static <T, E> SpecHandler<T, E> of(
        String typeName,
        Function<T, String> codeOf,
        BiFunction<String, T, Optional<E>> finder,
        BiConsumer<ApplyContext, T> inserter,
        SpecUpdater<T, E> updater) {
      return new SpecHandler<>() {
        @Override
        public String typeName() {
          return typeName;
        }

        @Override
        public String code(T s) {
          return codeOf.apply(s);
        }

        @Override
        public Optional<E> find(String tid, T s) {
          return finder.apply(tid, s);
        }

        @Override
        public void insert(ApplyContext ctx, T s) {
          inserter.accept(ctx, s);
        }

        @Override
        public void update(ApplyContext ctx, T s, E e) {
          updater.update(ctx, s, e);
        }
      };
    }

    /** insert 与 update 走同一 upsert 路径时的便捷工厂。 */
    static <T, E> SpecHandler<T, E> upsertable(
        String typeName,
        Function<T, String> codeOf,
        BiFunction<String, T, Optional<E>> finder,
        BiConsumer<ApplyContext, T> upsertAction) {
      return of(
          typeName,
          codeOf,
          finder,
          upsertAction,
          (ctx, spec, existing) -> upsertAction.accept(ctx, spec));
    }
  }

  /**
   * 公共 apply 模板：封装"查找 → 跳过/更新/创建"循环和异常收集，消除 10 个 apply* 方法中的重复代码。
   *
   * <p>每条 spec 包裹在 PROPAGATION_NESTED 子事务里(底层用 JDBC Savepoint),单条失败回滚到 savepoint, 不污染
   * outer @Transactional 主事务 — 否则一条 SQL 报错会让 PG 把整个事务标 "aborted",后续所有 SELECT/INSERT 都撞 "current
   * transaction is aborted, commands ignored until end of transaction block",批量初始化变成
   * all-or-nothing。
   */
  private <T, E> ItemStats applySpecs(List<T> specs, ApplyContext ctx, SpecHandler<T, E> handler) {
    if (specs == null || specs.isEmpty()) {
      return ItemStats.empty();
    }
    TransactionTemplate nested = new TransactionTemplate(transactionManager);
    nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    ItemStatsAccumulator acc = new ItemStatsAccumulator();
    for (T spec : specs) {
      String code = handler.code(spec);
      try {
        nested.executeWithoutResult(status -> {
          Optional<E> existing = handler.find(ctx.tenantId(), spec);
          if (existing.isPresent()) {
            if (ctx.mode() == InitMode.UPSERT) {
              if (!ctx.dryRun()) {
                handler.update(ctx, spec, existing.get());
              }
              acc.recordUpdated(code);
            } else {
              acc.recordSkipped(code);
            }
          } else {
            if (!ctx.dryRun()) {
              handler.insert(ctx, spec);
            }
            acc.recordCreated(code);
          }
        });
      } catch (Exception ex) {
        String handlerTypeForLog = handler.typeName();
        String codeForLog = LogSanitizer.value(code);
        String tenantIdForLog = LogSanitizer.value(ctx.tenantId());
        String exceptionSummaryForLog = SwallowedExceptionLogger.summary(ex);
        log.warn(
            "[TenantConfigBatchInit] {} code={} tenant={} failed: {}",
            handlerTypeForLog,
            codeForLog,
            tenantIdForLog,
            exceptionSummaryForLog);
        acc.recordFailed(code, TenantConfigFailureMessages.forFailure(ex));
      }
    }
    return acc.toItemStats();
  }

  // ── 10 个 spec apply 入口 (package-private,被主 service 直接调用) ────────────

  ItemStats applyJobDefinitions(List<JobDefinitionSpec> specs, ApplyContext ctx) {
    List<JobDefinitionSpec> orderedSpecs = orderJobDefinitionsByDependencies(specs);
    return applySpecs(
        orderedSpecs,
        ctx,
        SpecHandler.of(
            "jobDef",
            JobDefinitionSpec::getJobCode,
            (tid, s) -> Optional.ofNullable(
                definitionMappers.jobDefinition.selectByUniqueKey(tid, s.getJobCode())),
            (c, s) -> insertJobDefinition(c.tenantId(), s, c.operator()),
            (c, s, existing) -> updateJobDefinition(existing, s, c.operator())));
  }

  private static List<JobDefinitionSpec> orderJobDefinitionsByDependencies(
      List<JobDefinitionSpec> specs) {
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
    // 循环依赖包继续沿用逐项失败行为；数据库约束仍会
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

  private void insertJobDefinition(String tenantId, JobDefinitionSpec spec, String operator) {
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
    entity.setDagEnabled(spec.getDagEnabled() != null && spec.getDagEnabled());
    entity.setShardStrategy(Nullables.coalesce(spec.getShardStrategy(), "NONE"));
    entity.setRetryPolicy(Nullables.coalesce(spec.getRetryPolicy(), "NONE"));
    entity.setRetryMaxCount(spec.getRetryMaxCount());
    entity.setTimeoutSeconds(spec.getTimeoutSeconds());
    int softRuntimeSeconds = resolveSoftRuntime(spec.getSoftRuntimeSeconds());
    entity.setSoftRuntimeSeconds(softRuntimeSeconds);
    entity.setSoftRuntimeSeverity(severityOrWarn(spec.getSoftRuntimeSeverity()));
    int startGraceSeconds =
        resolveStartGrace(spec.getScheduleType(), dependsOnJobCode, spec.getStartGraceSeconds());
    entity.setStartGraceSeconds(startGraceSeconds);
    entity.setStartGraceSeverity(severityOrWarn(spec.getStartGraceSeverity()));
    entity.setCompletionDeadlineLocalTime(spec.getCompletionDeadlineLocalTime());
    entity.setDependencyCompletionWindowSeconds(
        valueOrZero(spec.getDependencyCompletionWindowSeconds()));
    int completionDeadlineDayOffset =
        EmptyChecks.isNull(spec.getCompletionDeadlineLocalTime())
            ? 0
            : valueOrZero(spec.getCompletionDeadlineDayOffset());
    entity.setCompletionDeadlineDayOffset(completionDeadlineDayOffset);
    entity.setCompletionDeadlineSeverity(severityOrWarn(spec.getCompletionDeadlineSeverity()));
    entity.setExecutionHandler(spec.getExecutionHandler());
    entity.setParamSchema(spec.getParamSchema());
    entity.setDefaultParams(spec.getDefaultParams());
    entity.setPriority(Nullables.coalesce(spec.getPriority(), 5));
    entity.setEnabled(spec.getEnabled() != null && spec.getEnabled());
    entity.setDescription(spec.getDescription());
    String executionMode = Nullables.coalesce(spec.getExecutionMode(), "FULL");
    boolean incrementalModeIsMissingWatermarkField =
        "INCREMENTAL".equals(executionMode)
            && (EmptyChecks.isNull(spec.getWatermarkField()) || EmptyChecks.isBlank(Objects.requireNonNull(spec.getWatermarkField())));
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
    definitionMappers.jobDefinition.insert(entity);
    replaceJobDependencyRules(tenantId, spec.getJobCode(), dependencyRules);
    saveMonitoringPolicy(tenantId, entity, operator);
  }

  private void updateJobDefinition(
      JobDefinitionEntity existing, JobDefinitionSpec spec, String operator) {
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
    validateScheduledMonitoring(
        existing.getScheduleType(),
        hasDependencies ? dependsOnJobCode : null,
        spec.getStartGraceSeconds(),
        completionDeadlineLocalTime,
        dependencyCompletionWindowSeconds);
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
      param.setDefaultParams(spec.getDefaultParams());
      param.setDefaultParamsSpecified(true);
    }
    param.setUpdatedBy(operator);
    definitionMappers.jobDefinition.updateJobDefinitionMaintenance(param);
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
      JobDefinitionEntity existing,
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
    definitionMappers.jobDefinition.upsertJobMonitoringPolicy(monitoringPolicy);
  }

  private WorkflowDefinitionSaveRequest buildWorkflowVersionSnapshot(
      String tenantId,
      WorkflowDefinitionSpec spec,
      WorkflowDefinitionUpsertParam param,
      String scheduleType) {
    WorkflowDefinitionSaveRequest snapshot = new WorkflowDefinitionSaveRequest();
    snapshot.setTenantId(tenantId);
    snapshot.setWorkflowCode(spec.getWorkflowCode());
    snapshot.setWorkflowName(spec.getWorkflowName());
    snapshot.setWorkflowType(Nullables.coalesce(spec.getWorkflowType(), "DAG"));
    snapshot.setEnabled(Nullables.coalesce(spec.getEnabled(), true));
    snapshot.setScheduleType(scheduleType);
    snapshot.setScheduleExpr(spec.getScheduleExpr());
    snapshot.setTimezone(param.getTimezone());
    snapshot.setTriggerMode(param.getTriggerMode());
    snapshot.setCalendarCode(spec.getCalendarCode());
    snapshot.setDependsOnJobCode(spec.getDependsOnJobCode());
    return snapshot;
  }

  private void saveMonitoringPolicy(String tenantId, JobDefinitionEntity entity, String operator) {
    validateScheduledMonitoring(
        entity.getScheduleType(),
        entity.getDependsOnJobCode(),
        entity.getStartGraceSeconds(),
        entity.getCompletionDeadlineLocalTime(),
        entity.getDependencyCompletionWindowSeconds());
    JobMonitoringPolicyUpsertParam.JobMonitoringPolicyUpsertParamBuilder monitoringPolicyBuilder =
        JobMonitoringPolicyUpsertParam.builder();
    monitoringPolicyBuilder.tenantId(tenantId);
    monitoringPolicyBuilder.jobDefinitionId(entity.getId());
    Integer softRuntimeSeconds = valueOrZero(entity.getSoftRuntimeSeconds());
    monitoringPolicyBuilder.softRuntimeSeconds(softRuntimeSeconds);
    String softRuntimeSeverity = severityOrWarn(entity.getSoftRuntimeSeverity());
    monitoringPolicyBuilder.softRuntimeSeverity(softRuntimeSeverity);
    Integer startGraceSeconds = valueOrZero(entity.getStartGraceSeconds());
    monitoringPolicyBuilder.startGraceSeconds(startGraceSeconds);
    String startGraceSeverity = severityOrWarn(entity.getStartGraceSeverity());
    monitoringPolicyBuilder.startGraceSeverity(startGraceSeverity);
    monitoringPolicyBuilder.completionDeadlineLocalTime(entity.getCompletionDeadlineLocalTime());
    Integer completionDeadlineDayOffset = valueOrZero(entity.getCompletionDeadlineDayOffset());
    monitoringPolicyBuilder.completionDeadlineDayOffset(completionDeadlineDayOffset);
    Integer dependencyCompletionWindowSeconds =
        valueOrZero(entity.getDependencyCompletionWindowSeconds());
    monitoringPolicyBuilder.dependencyCompletionWindowSeconds(dependencyCompletionWindowSeconds);
    String completionDeadlineSeverity = severityOrWarn(entity.getCompletionDeadlineSeverity());
    monitoringPolicyBuilder.completionDeadlineSeverity(completionDeadlineSeverity);
    monitoringPolicyBuilder.updatedBy(operator);
    JobMonitoringPolicyUpsertParam monitoringPolicy = monitoringPolicyBuilder.build();
    definitionMappers.jobDefinition.upsertJobMonitoringPolicy(monitoringPolicy);
  }

  private List<JobDependencyRuleRequest> resolveJobDependencyRules(
      JobDefinitionSpec spec, JobDefinitionEntity existing) {
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
      boolean dependencyRuleIsInvalid =
          !Texts.hasText(upstreamCode)
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

    JobDefinitionMapper jobMapper = definitionMappers.jobDefinition;
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
    definitionMappers.jobDefinition.deleteDependencies(tenantId, downstreamJobCode);
    if (EmptyChecks.isNotEmpty(rules)) {
      definitionMappers.jobDefinition.insertDependencyRules(tenantId, downstreamJobCode, rules);
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

  private static void validateScheduledMonitoring(
      String scheduleType,
      String dependsOnJobCode,
      Integer startGraceSeconds,
      LocalTime completionDeadlineLocalTime,
      Integer dependencyCompletionWindowSeconds) {
    boolean cron = "CRON".equals(scheduleType);
    boolean dependency = Texts.hasText(dependsOnJobCode);
    boolean startGraceSupported = cron || dependency;
    boolean invalidCompletionDeadline =
        EmptyChecks.isNotNull(completionDeadlineLocalTime) && (!cron || dependency)
            || valueOrZero(dependencyCompletionWindowSeconds) > 0 && !dependency
            || EmptyChecks.isNotNull(completionDeadlineLocalTime)
                && valueOrZero(dependencyCompletionWindowSeconds) > 0;
    boolean positiveStartGraceIsUnsupported =
        !startGraceSupported && valueOrZero(startGraceSeconds) > 0;
    boolean scheduledMonitoringConfigurationIsInvalid =
        positiveStartGraceIsUnsupported || invalidCompletionDeadline;
    if (scheduledMonitoringConfigurationIsInvalid) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument");
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

  ItemStats applyWorkflowDefinitions(List<WorkflowDefinitionSpec> specs, ApplyContext ctx) {
    return applySpecs(
        specs,
        ctx,
        SpecHandler.of(
            "workflow",
            WorkflowDefinitionSpec::getWorkflowCode,
            (tid, s) -> Optional.ofNullable(findWorkflowForImport(tid, s)),
            (c, s) -> upsertWorkflowDefinition(c.tenantId(), null, s, c.operator()),
            (c, s, existing) ->
                upsertWorkflowDefinition(c.tenantId(), existing.getId(), s, c.operator())));
  }

  private WorkflowDefinitionEntity findWorkflowForImport(
      String tenantId, WorkflowDefinitionSpec spec) {
    if (Texts.hasText(spec.getSingleJobCode())) {
      JobDefinitionEntity job = definitionMappers.jobDefinition.selectByUniqueKey(
          tenantId, spec.getSingleJobCode().trim());
      if (EmptyChecks.isNotNull(job)) {
        WorkflowDefinitionEntity managed =
            definitionMappers.workflowDefinition.selectBySingleJobDefinitionId(
                tenantId, job.getId());
        if (EmptyChecks.isNotNull(managed)) {
          return managed;
        }
      }
    }
    WorkflowDefinitionQuery workflowQuery = WorkflowDefinitionQuery.builder()
        .tenantId(tenantId)
        .workflowCode(spec.getWorkflowCode())
        .pageRequest(new PageRequest(1, 1))
        .build();
    return definitionMappers.workflowDefinition.selectByQuery(workflowQuery).stream()
        .findFirst()
        .orElse(null);
  }

  private void upsertWorkflowDefinition(
      String tenantId, Long existingId, WorkflowDefinitionSpec spec, String operator) {
    Long singleJobDefinitionId = null;
    if (Texts.hasText(spec.getSingleJobCode())) {
      JobDefinitionEntity singleJob = definitionMappers.jobDefinition.selectByUniqueKey(
          tenantId, spec.getSingleJobCode().trim());
      if (EmptyChecks.isNull(singleJob)) {
        throw BizException.of(
            ResultCode.NOT_FOUND,
            ResultCode.NOT_FOUND.detailKey(),
            "Managed Workflow Job not found: " + spec.getSingleJobCode());
      }
      singleJobDefinitionId = singleJob.getId();
    }
    WorkflowDefinitionUpsertParam param = new WorkflowDefinitionUpsertParam();
    param.setTenantId(tenantId);
    param.setWorkflowCode(spec.getWorkflowCode());
    param.setWorkflowName(spec.getWorkflowName());
    param.setWorkflowType(spec.getWorkflowType());
    WorkflowDefinitionEntity current = EmptyChecks.isNull(existingId)
        ? null
        : definitionMappers.workflowDefinition.selectById(tenantId, existingId);
    if (EmptyChecks.isNull(current) && EmptyChecks.isNotNull(singleJobDefinitionId)) {
      current = definitionMappers.workflowDefinition.selectBySingleJobDefinitionId(
          tenantId, singleJobDefinitionId);
    }
    String workflowCode =
        EmptyChecks.isNull(current) ? spec.getWorkflowCode() : current.getWorkflowCode();
    param.setWorkflowCode(workflowCode);
    param.setVersion(EmptyChecks.isNull(current) ? 1 : current.getVersion());
    param.setEnabled(spec.getEnabled() != null && spec.getEnabled());
    param.setSingleJobDefinitionId(singleJobDefinitionId);
    param.setScheduleEnabled(
        EmptyChecks.isNotNull(spec.getScheduleEnabled()) && spec.getScheduleEnabled());
    String scheduleType = Nullables.coalesce(spec.getScheduleType(), "MANUAL");
    param.setScheduleType(scheduleType);
    param.setScheduleExpr(spec.getScheduleExpr());
    param.setTimezone(Nullables.coalesce(spec.getTimezone(), CommonConstants.DEFAULT_TIMEZONE_ID));
    String requestedTriggerMode = spec.getTriggerMode();
    String defaultTriggerMode = switch (scheduleType) {
      case "CRON", "FIXED_RATE" -> "SCHEDULED";
      case "EVENT" -> "EVENT";
      default -> "MANUAL";
    };
    String resolvedTriggerMode = Nullables.coalesce(requestedTriggerMode, defaultTriggerMode);
    param.setTriggerMode(resolvedTriggerMode);
    param.setCalendarCode(spec.getCalendarCode());
    param.setDependsOnJobCode(spec.getDependsOnJobCode());
    param.setCreatedBy(operator);
    param.setUpdatedBy(operator);
    definitionMappers.workflowDefinition.upsertWorkflowDefinition(param);

    WorkflowDefinitionEntity saved = EmptyChecks.isNull(current)
        ? definitionMappers.workflowDefinition.selectByUniqueKey(
            tenantId, workflowCode, param.getVersion())
        : current;
    if (saved == null) {
      return;
    }
    Long defId = saved.getId();

    upsertWorkflowGraph(tenantId, defId, spec, EmptyChecks.isNotNull(existingId));

    boolean effectiveEnabled = EmptyChecks.isNull(current)
        ? Boolean.TRUE.equals(param.getEnabled())
        : Boolean.TRUE.equals(Nullables.coalesce(spec.getEnabled(), current.getEnabled()));
    if (effectiveEnabled || Boolean.TRUE.equals(param.getScheduleEnabled())) {
      WorkflowNodeQuery workflowNodeQuery = WorkflowNodeQuery.ofDefinition(tenantId, defId, null);
      List<WorkflowNodeEntity> workflowNodes =
          definitionMappers.workflowNode.selectByQuery(workflowNodeQuery);
      workflowDagInspector.requireExecutableJobReferences(tenantId, workflowNodes);
    }

    int nextVersion = EmptyChecks.isNull(current) ? 1 : current.getVersion() + 1;
    if (EmptyChecks.isNotNull(current)) {
      WorkflowDefinitionVersionUpdateParam versionedUpdate =
          WorkflowDefinitionVersionUpdateParam.builder()
              .tenantId(tenantId)
              .id(defId)
              .expectedVersion(current.getVersion())
              .workflowName(Nullables.coalesce(spec.getWorkflowName(), current.getWorkflowName()))
              .workflowType(Nullables.coalesce(spec.getWorkflowType(), current.getWorkflowType()))
              .enabled(Nullables.coalesce(spec.getEnabled(), current.getEnabled()))
              .scheduleEnabled(param.getScheduleEnabled())
              .scheduleType(scheduleType)
              .scheduleExpr(spec.getScheduleExpr())
              .timezone(param.getTimezone())
              .triggerMode(param.getTriggerMode())
              .calendarCode(spec.getCalendarCode())
              .dependsOnJobCode(spec.getDependsOnJobCode())
              .build();
      int rows = definitionMappers.workflowDefinition.updateAndBumpVersion(versionedUpdate);
      if (rows == 0) {
        throw BizException.of(
            ResultCode.CONFLICT,
            "error.workflow_full_update.version_conflict",
            current.getVersion(),
            current.getVersion());
      }
    }
    WorkflowDefinitionSaveRequest snapshot =
        buildWorkflowVersionSnapshot(tenantId, spec, param, scheduleType);
    WorkflowVersionSnapshotCommand snapshotCommand = WorkflowVersionSnapshotCommand.builder()
        .tenantId(tenantId)
        .definitionId(defId)
        .workflowCode(workflowCode)
        .version(nextVersion)
        .body(snapshot)
        .scheduleEnabled(param.getScheduleEnabled())
        .savedBy(operator)
        .build();
    workflowWriteSupport.appendVersionSnapshot(snapshotCommand);
  }

  /** 图的替换与节点/边写入保持在调用方同一事务中，随后仍由定义 CAS 决定整体提交。 */
  private void upsertWorkflowGraph(
      String tenantId, Long defId, WorkflowDefinitionSpec spec, boolean replaceExistingGraph) {
    if (EmptyChecks.isNotNull(spec.getNodes())) {
      if (replaceExistingGraph) {
        definitionMappers.workflowNode.deleteByWorkflowDefinitionId(defId);
        definitionMappers.workflowEdge.deleteByWorkflowDefinitionId(defId);
      }
      for (WorkflowDefinitionSpec.NodeSpec nodeSpec : spec.getNodes()) {
        WorkflowNodeUpsertParam nodeParam = new WorkflowNodeUpsertParam();
        nodeParam.setTenantId(tenantId);
        nodeParam.setWorkflowDefinitionId(defId);
        nodeParam.setNodeCode(nodeSpec.getNodeCode());
        nodeParam.setNodeName(nodeSpec.getNodeName());
        nodeParam.setNodeType(nodeSpec.getNodeType());
        nodeParam.setRelatedJobCode(nodeSpec.getRelatedJobCode());
        nodeParam.setWorkerGroup(nodeSpec.getWorkerGroup());
        nodeParam.setWindowCode(nodeSpec.getWindowCode());
        nodeParam.setNodeOrder(nodeSpec.getNodeOrder());
        nodeParam.setRetryPolicy(nodeSpec.getRetryPolicy());
        nodeParam.setRetryMaxCount(nodeSpec.getRetryMaxCount());
        nodeParam.setTimeoutSeconds(nodeSpec.getTimeoutSeconds());
        nodeParam.setNodeParams(nodeSpec.getNodeParams());
        nodeParam.setEnabled(Nullables.coalesce(nodeSpec.getEnabled(), true));
        definitionMappers.workflowNode.upsertWorkflowNode(nodeParam);
      }
      if (EmptyChecks.isNotNull(spec.getEdges())) {
        for (WorkflowDefinitionSpec.EdgeSpec edgeSpec : spec.getEdges()) {
          WorkflowEdgeUpsertParam edgeParam = new WorkflowEdgeUpsertParam();
          edgeParam.setTenantId(tenantId);
          edgeParam.setWorkflowDefinitionId(defId);
          edgeParam.setFromNodeCode(edgeSpec.getFromNodeCode());
          edgeParam.setToNodeCode(edgeSpec.getToNodeCode());
          edgeParam.setEdgeType(Nullables.coalesce(edgeSpec.getEdgeType(), "NORMAL"));
          edgeParam.setConditionExpr(edgeSpec.getConditionExpr());
          edgeParam.setEnabled(Nullables.coalesce(edgeSpec.getEnabled(), true));
          definitionMappers.workflowEdge.upsertWorkflowEdge(edgeParam);
        }
      }
    }
  }

  ItemStats applyFileChannels(List<FileChannelSpec> specs, ApplyContext ctx) {
    return applySpecs(
        specs,
        ctx,
        SpecHandler.upsertable(
            "channel",
            FileChannelSpec::getChannelCode,
            (tid, s) -> Optional.ofNullable(fileConfigSupport.findChannel(tid, s)),
            (c, s) -> fileConfigSupport.upsertChannel(c.tenantId(), s, c.operator())));
  }

  ItemStats applyFileTemplates(List<FileTemplateSpec> specs, ApplyContext ctx) {
    return applySpecs(
        specs,
        ctx,
        SpecHandler.upsertable(
            "template",
            FileTemplateSpec::getTemplateCode,
            (tid, s) -> Optional.ofNullable(fileConfigSupport.findTemplate(tid, s)),
            (c, s) -> fileConfigSupport.upsertTemplate(c.tenantId(), s, c.operator())));
  }

  ItemStats applyResourceQueues(List<ResourceQueueSpec> specs, ApplyContext ctx) {
    return applySpecs(
        specs,
        ctx,
        SpecHandler.upsertable(
            "queue",
            ResourceQueueSpec::getQueueCode,
            (tid, s) -> Optional.ofNullable(operationalConfigSupport.findResourceQueue(tid, s)),
            (c, s) -> operationalConfigSupport.upsertResourceQueue(c.tenantId(), s, c.operator())));
  }

  ItemStats applyBatchWindows(List<BatchWindowSpec> specs, ApplyContext ctx) {
    return applySpecs(
        specs,
        ctx,
        SpecHandler.upsertable(
            "window",
            BatchWindowSpec::getWindowCode,
            (tid, s) -> Optional.ofNullable(operationalConfigSupport.findBatchWindow(tid, s)),
            (c, s) -> operationalConfigSupport.upsertBatchWindow(c.tenantId(), s)));
  }

  ItemStats applyBusinessCalendars(List<BusinessCalendarSpec> specs, ApplyContext ctx) {
    return applySpecs(
        specs,
        ctx,
        SpecHandler.<BusinessCalendarSpec, Map<String, Object>>of(
            "calendar",
            BusinessCalendarSpec::getCalendarCode,
            (tid, s) -> Optional.ofNullable(operationalConfigSupport.findBusinessCalendar(tid, s)),
            (c, s) -> operationalConfigSupport.upsertBusinessCalendar(
                c.tenantId(), s, c.operator(), null),
            (c, s, existing) -> operationalConfigSupport.upsertBusinessCalendar(
                c.tenantId(), s, c.operator(), ((Number) existing.get(KEY_ID)).longValue())));
  }

  ItemStats applyQuotaPolicies(List<TenantQuotaPolicySpec> specs, ApplyContext ctx) {
    return applySpecs(
        specs,
        ctx,
        SpecHandler.upsertable(
            "quota",
            TenantQuotaPolicySpec::getPolicyCode,
            (tid, s) -> Optional.ofNullable(operationalConfigSupport.findQuotaPolicy(tid, s)),
            (c, s) -> operationalConfigSupport.upsertQuotaPolicy(c.tenantId(), s)));
  }

  ItemStats applyAlertRoutings(List<AlertRoutingSpec> specs, ApplyContext ctx) {
    return applySpecs(
        specs,
        ctx,
        SpecHandler.upsertable(
            "alertRouting",
            AlertRoutingSpec::getRouteCode,
            (tid, s) -> Optional.ofNullable(operationalConfigSupport.findAlertRouting(tid, s)),
            (c, s) -> operationalConfigSupport.upsertAlertRouting(c.tenantId(), s, c.operator())));
  }
}
