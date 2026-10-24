package io.github.pinpols.batch.console.infrastructure.config;

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
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.PipelineDefinitionSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.ResourceQueueSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.TenantQuotaPolicySpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.WorkflowDefinitionSpec;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse.ItemStats;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse.ItemStatsAccumulator;
import io.github.pinpols.batch.console.config.JobMonitoringDefaultsProperties;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.param.JobDefinitionMaintenanceUpdateParam;
import io.github.pinpols.batch.console.domain.job.param.JobMonitoringPolicyUpsertParam;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.WorkflowDefinitionSaveRequest;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowDefinitionUpsertParam;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowEdgeUpsertParam;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowNodeUpsertParam;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowDefinitionQuery;
import io.github.pinpols.batch.console.infrastructure.excel.LegacyPipelineJobParameterConverter;
import io.github.pinpols.batch.console.infrastructure.excel.LegacyPipelineJobParameterConverter.Result;
import io.github.pinpols.batch.console.infrastructure.workflow.DefaultSingleJobWorkflowProvisioner;
import io.github.pinpols.batch.console.infrastructure.workflow.WorkflowDefinitionWriteSupport;
import io.github.pinpols.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

  private final TenantDefinitionConfigMappers definitionMappers;
  private final PlatformTransactionManager transactionManager;
  private final TenantFileConfigApplySupport fileConfigSupport;
  private final TenantOperationalConfigApplySupport operationalConfigSupport;
  private final JobMonitoringDefaultsProperties monitoringDefaults;
  private final WorkflowDefinitionWriteSupport workflowWriteSupport;
  private final DefaultSingleJobWorkflowProvisioner singleJobWorkflowProvisioner;

  public TenantConfigInitApplyHandlers(
      TenantDefinitionConfigMappers definitionMappers,
      PlatformTransactionManager transactionManager,
      TenantFileConfigApplySupport fileConfigSupport,
      TenantOperationalConfigApplySupport operationalConfigSupport,
      JobMonitoringDefaultsProperties monitoringDefaults,
      WorkflowDefinitionWriteSupport workflowWriteSupport,
      DefaultSingleJobWorkflowProvisioner singleJobWorkflowProvisioner) {
    this.definitionMappers = definitionMappers;
    this.transactionManager = transactionManager;
    this.fileConfigSupport = fileConfigSupport;
    this.operationalConfigSupport = operationalConfigSupport;
    this.monitoringDefaults = monitoringDefaults;
    this.workflowWriteSupport = workflowWriteSupport;
    this.singleJobWorkflowProvisioner = singleJobWorkflowProvisioner;
  }

  void ensureManagedSingleJobWorkflows(List<JobDefinitionSpec> jobSpecs, String tenantId) {
    if (jobSpecs == null) {
      return;
    }
    for (JobDefinitionSpec spec : jobSpecs) {
      if (!Texts.hasText(spec.getJobCode())) {
        continue;
      }
      JobDefinitionEntity job =
          definitionMappers.jobDefinition.selectByUniqueKey(tenantId, spec.getJobCode());
      if (job != null) {
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
        log.warn(
            "[TenantConfigBatchInit] {} code={} tenant={} failed: {}",
            handler.typeName(),
            LogSanitizer.value(code),
            LogSanitizer.value(ctx.tenantId()),
            SwallowedExceptionLogger.summary(ex));
        acc.recordFailed(code, ex.getMessage());
      }
    }
    return acc.toItemStats();
  }

  // ── 10 个 spec apply 入口 (package-private,被主 service 直接调用) ────────────

  ItemStats applyJobDefinitions(List<JobDefinitionSpec> specs, ApplyContext ctx) {
    return applySpecs(
        specs,
        ctx,
        SpecHandler.of(
            "jobDef",
            JobDefinitionSpec::getJobCode,
            (tid, s) -> Optional.ofNullable(
                definitionMappers.jobDefinition.selectByUniqueKey(tid, s.getJobCode())),
            (c, s) -> insertJobDefinition(c.tenantId(), s, c.operator()),
            (c, s, existing) -> updateJobDefinition(existing, s, c.operator())));
  }

  private void insertJobDefinition(String tenantId, JobDefinitionSpec spec, String operator) {
    JobDefinitionEntity entity = new JobDefinitionEntity();
    entity.setTenantId(tenantId);
    entity.setJobCode(spec.getJobCode());
    entity.setDependsOnJobCode(CodeNormalizer.trimToNull(spec.getDependsOnJobCode()));
    entity.setJobName(spec.getJobName());
    entity.setJobType(spec.getJobType());
    entity.setBizType(spec.getBizType());
    String jobScheduleType = Nullables.coalesce(spec.getScheduleType(), "MANUAL");
    entity.setScheduleType(jobScheduleType);
    entity.setScheduleExpr(spec.getScheduleExpr());
    entity.setTimezone(Nullables.coalesce(spec.getTimezone(), CommonConstants.DEFAULT_TIMEZONE_ID));
    entity.setTriggerMode(Nullables.coalesce(
        spec.getTriggerMode(),
        switch (jobScheduleType) {
          case "CRON", "FIXED_RATE" -> "SCHEDULED";
          case "EVENT" -> "EVENT";
          default -> "MANUAL";
        }));
    entity.setWorkerGroup(CodeNormalizer.toUpperOrNull(spec.getWorkerGroup()));
    entity.setQueueCode(CodeNormalizer.toConfigFormOrNull(spec.getQueueCode()));
    entity.setCalendarCode(CodeNormalizer.toConfigFormOrNull(spec.getCalendarCode()));
    entity.setWindowCode(CodeNormalizer.toConfigFormOrNull(spec.getWindowCode()));
    entity.setDagEnabled(spec.getDagEnabled() != null && spec.getDagEnabled());
    entity.setShardStrategy(Nullables.coalesce(spec.getShardStrategy(), "NONE"));
    entity.setRetryPolicy(Nullables.coalesce(spec.getRetryPolicy(), "NONE"));
    entity.setRetryMaxCount(spec.getRetryMaxCount());
    entity.setTimeoutSeconds(spec.getTimeoutSeconds());
    entity.setSoftRuntimeSeconds(resolveSoftRuntime(spec.getSoftRuntimeSeconds()));
    entity.setSoftRuntimeSeverity(severityOrWarn(spec.getSoftRuntimeSeverity()));
    entity.setStartGraceSeconds(resolveStartGrace(
        spec.getScheduleType(), spec.getDependsOnJobCode(), spec.getStartGraceSeconds()));
    entity.setStartGraceSeverity(severityOrWarn(spec.getStartGraceSeverity()));
    entity.setCompletionDeadlineLocalTime(spec.getCompletionDeadlineLocalTime());
    entity.setDependencyCompletionWindowSeconds(
        valueOrZero(spec.getDependencyCompletionWindowSeconds()));
    entity.setCompletionDeadlineDayOffset(
        EmptyChecks.isNull(spec.getCompletionDeadlineLocalTime())
            ? 0
            : valueOrZero(spec.getCompletionDeadlineDayOffset()));
    entity.setCompletionDeadlineSeverity(severityOrWarn(spec.getCompletionDeadlineSeverity()));
    entity.setExecutionHandler(spec.getExecutionHandler());
    entity.setParamSchema(spec.getParamSchema());
    entity.setDefaultParams(spec.getDefaultParams());
    entity.setPriority(Nullables.coalesce(spec.getPriority(), 5));
    entity.setEnabled(spec.getEnabled() != null && spec.getEnabled());
    entity.setDescription(spec.getDescription());
    String executionMode = Nullables.coalesce(spec.getExecutionMode(), "FULL");
    if ("INCREMENTAL".equals(executionMode)
        && (spec.getWatermarkField() == null || spec.getWatermarkField().isBlank())) {
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
    saveMonitoringPolicy(tenantId, entity, operator);
  }

  private void updateJobDefinition(
      JobDefinitionEntity existing, JobDefinitionSpec spec, String operator) {
    String dependsOnJobCode = Nullables.coalesce(
        CodeNormalizer.trimToNull(spec.getDependsOnJobCode()), existing.getDependsOnJobCode());
    LocalTime completionDeadlineLocalTime =
        EmptyChecks.isNull(spec.getCompletionDeadlineLocalTime())
            ? existing.getCompletionDeadlineLocalTime()
            : spec.getCompletionDeadlineLocalTime();
    Integer dependencyCompletionWindowSeconds = resolveMonitoringValue(
        spec.getDependencyCompletionWindowSeconds(),
        existing.getDependencyCompletionWindowSeconds());
    validateScheduledMonitoring(
        existing.getScheduleType(),
        dependsOnJobCode,
        spec.getStartGraceSeconds(),
        completionDeadlineLocalTime,
        dependencyCompletionWindowSeconds);
    JobDefinitionMaintenanceUpdateParam param = new JobDefinitionMaintenanceUpdateParam();
    param.setTenantId(existing.getTenantId());
    param.setJobCode(existing.getJobCode());
    param.setDependsOnJobCode(dependsOnJobCode);
    param.setDependencyCompletionWindowSeconds(
        Texts.hasText(dependsOnJobCode) ? dependencyCompletionWindowSeconds : 0);
    param.setJobName(Nullables.coalesce(spec.getJobName(), existing.getJobName()));
    param.setQueueCode(Nullables.coalesce(
        CodeNormalizer.toConfigFormOrNull(spec.getQueueCode()), existing.getQueueCode()));
    param.setWorkerGroup(Nullables.coalesce(
        CodeNormalizer.toUpperOrNull(spec.getWorkerGroup()), existing.getWorkerGroup()));
    param.setScheduleExpr(Nullables.coalesce(spec.getScheduleExpr(), existing.getScheduleExpr()));
    param.setCalendarCode(Nullables.coalesce(
        CodeNormalizer.toConfigFormOrNull(spec.getCalendarCode()), existing.getCalendarCode()));
    param.setWindowCode(Nullables.coalesce(
        CodeNormalizer.toConfigFormOrNull(spec.getWindowCode()), existing.getWindowCode()));
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
    if (spec.getDefaultParams() != null) {
      param.setDefaultParams(spec.getDefaultParams());
      param.setDefaultParamsSpecified(true);
    }
    param.setUpdatedBy(operator);
    definitionMappers.jobDefinition.updateJobDefinitionMaintenance(param);
    definitionMappers.jobDefinition.upsertJobMonitoringPolicy(
        JobMonitoringPolicyUpsertParam.builder()
            .tenantId(existing.getTenantId())
            .jobDefinitionId(existing.getId())
            .softRuntimeSeconds(resolveMonitoringValue(
                spec.getSoftRuntimeSeconds(), existing.getSoftRuntimeSeconds()))
            .softRuntimeSeverity(resolveMonitoringSeverity(
                spec.getSoftRuntimeSeverity(), existing.getSoftRuntimeSeverity()))
            .startGraceSeconds(resolveMonitoringValue(
                spec.getStartGraceSeconds(), existing.getStartGraceSeconds()))
            .startGraceSeverity(resolveMonitoringSeverity(
                spec.getStartGraceSeverity(), existing.getStartGraceSeverity()))
            .completionDeadlineLocalTime(completionDeadlineLocalTime)
            .completionDeadlineDayOffset(resolveCompletionDeadlineDayOffset(
                completionDeadlineLocalTime,
                spec.getCompletionDeadlineDayOffset(),
                existing.getCompletionDeadlineDayOffset()))
            .dependencyCompletionWindowSeconds(param.getDependencyCompletionWindowSeconds())
            .completionDeadlineSeverity(resolveMonitoringSeverity(
                spec.getCompletionDeadlineSeverity(), existing.getCompletionDeadlineSeverity()))
            .updatedBy(operator)
            .build());
  }

  private void saveMonitoringPolicy(String tenantId, JobDefinitionEntity entity, String operator) {
    validateScheduledMonitoring(
        entity.getScheduleType(),
        entity.getDependsOnJobCode(),
        entity.getStartGraceSeconds(),
        entity.getCompletionDeadlineLocalTime(),
        entity.getDependencyCompletionWindowSeconds());
    definitionMappers.jobDefinition.upsertJobMonitoringPolicy(
        JobMonitoringPolicyUpsertParam.builder()
            .tenantId(tenantId)
            .jobDefinitionId(entity.getId())
            .softRuntimeSeconds(valueOrZero(entity.getSoftRuntimeSeconds()))
            .softRuntimeSeverity(severityOrWarn(entity.getSoftRuntimeSeverity()))
            .startGraceSeconds(valueOrZero(entity.getStartGraceSeconds()))
            .startGraceSeverity(severityOrWarn(entity.getStartGraceSeverity()))
            .completionDeadlineLocalTime(entity.getCompletionDeadlineLocalTime())
            .completionDeadlineDayOffset(valueOrZero(entity.getCompletionDeadlineDayOffset()))
            .dependencyCompletionWindowSeconds(
                valueOrZero(entity.getDependencyCompletionWindowSeconds()))
            .completionDeadlineSeverity(severityOrWarn(entity.getCompletionDeadlineSeverity()))
            .updatedBy(operator)
            .build());
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
    if ((!startGraceSupported && valueOrZero(startGraceSeconds) > 0) || invalidCompletionDeadline) {
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
      if (job != null) {
        WorkflowDefinitionEntity managed =
            definitionMappers.workflowDefinition.selectBySingleJobDefinitionId(
                tenantId, job.getId());
        if (managed != null) {
          return managed;
        }
      }
    }
    return definitionMappers
        .workflowDefinition
        .selectByQuery(WorkflowDefinitionQuery.builder()
            .tenantId(tenantId)
            .workflowCode(spec.getWorkflowCode())
            .pageRequest(new PageRequest(1, 1))
            .build())
        .stream()
        .findFirst()
        .orElse(null);
  }

  private void upsertWorkflowDefinition(
      String tenantId, Long existingId, WorkflowDefinitionSpec spec, String operator) {
    Long singleJobDefinitionId = null;
    if (Texts.hasText(spec.getSingleJobCode())) {
      JobDefinitionEntity singleJob = definitionMappers.jobDefinition.selectByUniqueKey(
          tenantId, spec.getSingleJobCode().trim());
      if (singleJob == null) {
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
    WorkflowDefinitionEntity current = existingId == null
        ? null
        : definitionMappers.workflowDefinition.selectById(tenantId, existingId);
    if (current == null && singleJobDefinitionId != null) {
      current = definitionMappers.workflowDefinition.selectBySingleJobDefinitionId(
          tenantId, singleJobDefinitionId);
    }
    String workflowCode = current == null ? spec.getWorkflowCode() : current.getWorkflowCode();
    param.setWorkflowCode(workflowCode);
    param.setVersion(current == null ? 1 : current.getVersion());
    param.setEnabled(spec.getEnabled() != null && spec.getEnabled());
    param.setSingleJobDefinitionId(singleJobDefinitionId);
    param.setScheduleEnabled(spec.getScheduleEnabled() != null && spec.getScheduleEnabled());
    String scheduleType = Nullables.coalesce(spec.getScheduleType(), "MANUAL");
    param.setScheduleType(scheduleType);
    param.setScheduleExpr(spec.getScheduleExpr());
    param.setTimezone(Nullables.coalesce(spec.getTimezone(), CommonConstants.DEFAULT_TIMEZONE_ID));
    param.setTriggerMode(Nullables.coalesce(
        spec.getTriggerMode(),
        switch (scheduleType) {
          case "CRON", "FIXED_RATE" -> "SCHEDULED";
          case "EVENT" -> "EVENT";
          default -> "MANUAL";
        }));
    param.setCalendarCode(spec.getCalendarCode());
    param.setDependsOnJobCode(spec.getDependsOnJobCode());
    param.setCreatedBy(operator);
    param.setUpdatedBy(operator);
    definitionMappers.workflowDefinition.upsertWorkflowDefinition(param);

    WorkflowDefinitionEntity saved = current == null
        ? definitionMappers.workflowDefinition.selectByUniqueKey(
            tenantId, workflowCode, param.getVersion())
        : current;
    if (saved == null) {
      return;
    }
    Long defId = saved.getId();

    if (spec.getNodes() != null) {
      if (existingId != null) {
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
        nodeParam.setRelatedPipelineCode(nodeSpec.getRelatedPipelineCode());
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
      if (spec.getEdges() != null) {
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

    int nextVersion = current == null ? 1 : current.getVersion() + 1;
    if (current != null) {
      int rows = definitionMappers.workflowDefinition.updateAndBumpVersion(
          tenantId,
          defId,
          current.getVersion(),
          Nullables.coalesce(spec.getWorkflowName(), current.getWorkflowName()),
          Nullables.coalesce(spec.getWorkflowType(), current.getWorkflowType()),
          Nullables.coalesce(spec.getEnabled(), current.getEnabled()),
          param.getScheduleEnabled(),
          scheduleType,
          spec.getScheduleExpr(),
          param.getTimezone(),
          param.getTriggerMode(),
          spec.getCalendarCode(),
          spec.getDependsOnJobCode());
      if (rows == 0) {
        throw BizException.of(
            ResultCode.CONFLICT,
            "error.workflow_full_update.version_conflict",
            current.getVersion(),
            current.getVersion());
      }
    }
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
    workflowWriteSupport.appendVersionSnapshot(
        tenantId, defId, workflowCode, nextVersion, snapshot, param.getScheduleEnabled(), operator);
  }

  ItemStats migrateLegacyPipelineDefinitions(
      List<JobDefinitionSpec> jobSpecs,
      List<PipelineDefinitionSpec> pipelineSpecs,
      ApplyContext ctx) {
    if (pipelineSpecs == null || pipelineSpecs.isEmpty()) {
      return ItemStats.empty();
    }
    List<JobDefinitionSpec> mutableJobs = jobSpecs == null ? new ArrayList<>() : jobSpecs;
    Map<String, JobDefinitionSpec> jobsByCode = new LinkedHashMap<>();
    mutableJobs.stream()
        .filter(spec -> Texts.hasText(spec.getJobCode()))
        .forEach(spec -> jobsByCode.putIfAbsent(spec.getJobCode(), spec));

    ItemStatsAccumulator stats = new ItemStatsAccumulator();
    List<PipelineDefinitionSpec> candidates = new ArrayList<>();
    for (PipelineDefinitionSpec pipeline : pipelineSpecs) {
      String jobCode = pipeline.getJobCode();
      JobDefinitionSpec job = jobsByCode.get(jobCode);
      JobDefinitionEntity existing =
          definitionMappers.jobDefinition.selectByUniqueKey(ctx.tenantId(), jobCode);
      if (existing != null && ctx.mode() == InitMode.SKIP_EXISTING) {
        stats.recordSkipped(
            migrationCode(pipeline),
            "target Job is skipped; legacy Pipeline settings were not applied");
        continue;
      }
      if (job == null && existing != null) {
        job = new JobDefinitionSpec();
        job.setJobCode(existing.getJobCode());
        job.setJobType(existing.getJobType());
        job.setDefaultParams(existing.getDefaultParams());
        mutableJobs.add(job);
        jobsByCode.put(jobCode, job);
      }
      candidates.add(pipeline);
    }

    if (candidates.isEmpty()) {
      return stats.toItemStats();
    }
    Map<Integer, String> codeByRowNo = new LinkedHashMap<>();
    List<Map<String, String>> jobRows = new ArrayList<>();
    int nextRowNo = 2;
    for (JobDefinitionSpec job : mutableJobs) {
      if (!jobsByCode.containsKey(job.getJobCode())) {
        continue;
      }
      codeByRowNo.put(nextRowNo, job.getJobCode());
      jobRows.add(migrationRow(
          nextRowNo++,
          "job_code",
          job.getJobCode(),
          "job_type",
          job.getJobType(),
          "default_params",
          job.getDefaultParams()));
    }

    List<Map<String, String>> pipelineRows = new ArrayList<>();
    List<Map<String, String>> stepRows = new ArrayList<>();
    Map<Integer, PipelineDefinitionSpec> pipelineByRowNo = new LinkedHashMap<>();
    for (PipelineDefinitionSpec pipeline : candidates) {
      int rowNo = nextRowNo++;
      pipelineByRowNo.put(rowNo, pipeline);
      codeByRowNo.put(rowNo, pipeline.getJobCode());
      Map<String, String> pipelineRow = migrationRow(
          rowNo,
          "job_code",
          pipeline.getJobCode(),
          "pipeline_type",
          pipeline.getPipelineType(),
          "version",
          String.valueOf(Nullables.coalesce(pipeline.getVersion(), 1)),
          "enabled",
          String.valueOf(Nullables.coalesce(pipeline.getEnabled(), true)),
          "worker_group",
          pipeline.getWorkerGroup(),
          "biz_type",
          pipeline.getBizType());
      pipelineRows.add(pipelineRow);
      List<PipelineDefinitionSpec.StepSpec> steps =
          pipeline.getSteps() == null ? List.of() : pipeline.getSteps();
      for (PipelineDefinitionSpec.StepSpec step : steps) {
        stepRows.add(migrationRow(
            rowNo,
            "job_code",
            pipeline.getJobCode(),
            "version",
            String.valueOf(Nullables.coalesce(pipeline.getVersion(), 1)),
            "stage_code",
            step.getStageCode(),
            "step_order",
            String.valueOf(Nullables.coalesce(step.getStepOrder(), 0)),
            "impl_code",
            step.getImplCode(),
            "step_params",
            step.getStepParams(),
            "timeout_seconds",
            String.valueOf(Nullables.coalesce(step.getTimeoutSeconds(), 0)),
            "retry_policy",
            Nullables.coalesce(step.getRetryPolicy(), "NONE"),
            "retry_max_count",
            String.valueOf(Nullables.coalesce(step.getRetryMaxCount(), 0)),
            "enabled",
            String.valueOf(Nullables.coalesce(step.getEnabled(), true))));
      }
    }

    Result converted = LegacyPipelineJobParameterConverter.convert(
        ctx.tenantId(), jobRows, pipelineRows, stepRows, definitionMappers.jobDefinition);
    Map<String, String> convertedDefaults = new HashMap<>();
    for (Map<String, String> row : converted.jobRows()) {
      convertedDefaults.put(row.get("job_code"), row.get("default_params"));
    }
    Map<String, List<String>> failuresByJob = new LinkedHashMap<>();
    for (WorkbookIssue issue : converted.issues()) {
      String jobCode = codeByRowNo.get(issue.rowNo());
      if (jobCode == null) {
        candidates.forEach(pipeline -> failuresByJob
            .computeIfAbsent(pipeline.getJobCode(), ignored -> new ArrayList<>())
            .add(issue.message()));
      } else {
        failuresByJob.computeIfAbsent(jobCode, ignored -> new ArrayList<>()).add(issue.message());
      }
    }
    mutableJobs.removeIf(job -> failuresByJob.containsKey(job.getJobCode()));
    for (Map.Entry<String, JobDefinitionSpec> entry : jobsByCode.entrySet()) {
      if (!failuresByJob.containsKey(entry.getKey())
          && convertedDefaults.containsKey(entry.getKey())) {
        entry.getValue().setDefaultParams(convertedDefaults.get(entry.getKey()));
      }
    }
    for (Map.Entry<Integer, PipelineDefinitionSpec> entry : pipelineByRowNo.entrySet()) {
      String jobCode = entry.getValue().getJobCode();
      List<String> errors = failuresByJob.get(jobCode);
      String code = migrationCode(entry.getValue());
      if (errors == null || errors.isEmpty()) {
        stats.recordMigrated(code, "legacy settings converted to Job default_params by Job type");
      } else {
        stats.recordFailed(code, String.join("; ", errors));
      }
    }
    return stats.toItemStats();
  }

  private static Map<String, String> migrationRow(int rowNo, String... entries) {
    Map<String, String> row = new LinkedHashMap<>();
    for (int index = 0; index + 1 < entries.length; index += 2) {
      if (entries[index + 1] != null) {
        row.put(entries[index], entries[index + 1]);
      }
    }
    row.put("__excel_row_no", String.valueOf(rowNo));
    return row;
  }

  private static String migrationCode(PipelineDefinitionSpec pipeline) {
    return pipeline.getJobCode() + ":" + pipeline.getPipelineType();
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
