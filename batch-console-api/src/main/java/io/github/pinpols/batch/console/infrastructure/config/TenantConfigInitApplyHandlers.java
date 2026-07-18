package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.Nullables;
import io.github.pinpols.batch.console.domain.file.mapper.FileChannelConfigMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.BatchWindowMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.CalendarHolidayMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.param.JobDefinitionMaintenanceUpdateParam;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertRoutingConfigMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.rbac.mapper.TenantQuotaPolicyMapper;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.PipelineDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.PipelineStepDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowDefinitionUpsertParam;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowEdgeUpsertParam;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowNodeUpsertParam;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.AlertRoutingSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.BatchWindowSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.BusinessCalendarSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.FileChannelSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.FileTemplateSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.InitMode;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.JobDefinitionSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.PipelineDefinitionSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.ResourceQueueSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.TenantQuotaPolicySpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.WorkflowDefinitionSpec;
import io.github.pinpols.batch.console.web.response.config.TenantConfigBatchInitResponse.ItemStats;
import io.github.pinpols.batch.console.web.response.config.TenantConfigBatchInitResponse.ItemStatsAccumulator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
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

  @Lazy @Autowired private TenantConfigInitApplyHandlers self;

  private final JobDefinitionMapper jobDefinitionMapper;
  private final WorkflowDefinitionMapper workflowDefinitionMapper;
  private final WorkflowNodeMapper workflowNodeMapper;
  private final WorkflowEdgeMapper workflowEdgeMapper;
  private final PipelineDefinitionMapper pipelineDefinitionMapper;
  private final PipelineStepDefinitionMapper pipelineStepDefinitionMapper;
  private final PlatformTransactionManager transactionManager;
  private final TenantFileConfigApplySupport fileConfigSupport;
  private final TenantOperationalConfigApplySupport operationalConfigSupport;

  public TenantConfigInitApplyHandlers(
      JobDefinitionMapper jobDefinitionMapper,
      WorkflowDefinitionMapper workflowDefinitionMapper,
      WorkflowNodeMapper workflowNodeMapper,
      WorkflowEdgeMapper workflowEdgeMapper,
      PipelineDefinitionMapper pipelineDefinitionMapper,
      PipelineStepDefinitionMapper pipelineStepDefinitionMapper,
      FileChannelConfigMapper fileChannelConfigMapper,
      FileTemplateConfigMapper fileTemplateConfigMapper,
      ResourceQueueMapper resourceQueueMapper,
      BatchWindowMapper batchWindowMapper,
      BusinessCalendarMapper businessCalendarMapper,
      CalendarHolidayMapper calendarHolidayMapper,
      TenantQuotaPolicyMapper tenantQuotaPolicyMapper,
      AlertRoutingConfigMapper alertRoutingConfigMapper,
      PlatformTransactionManager transactionManager) {
    this.jobDefinitionMapper = jobDefinitionMapper;
    this.workflowDefinitionMapper = workflowDefinitionMapper;
    this.workflowNodeMapper = workflowNodeMapper;
    this.workflowEdgeMapper = workflowEdgeMapper;
    this.pipelineDefinitionMapper = pipelineDefinitionMapper;
    this.pipelineStepDefinitionMapper = pipelineStepDefinitionMapper;
    this.transactionManager = transactionManager;
    this.fileConfigSupport =
        new TenantFileConfigApplySupport(fileChannelConfigMapper, fileTemplateConfigMapper);
    this.operationalConfigSupport =
        new TenantOperationalConfigApplySupport(
            resourceQueueMapper,
            batchWindowMapper,
            businessCalendarMapper,
            calendarHolidayMapper,
            tenantQuotaPolicyMapper,
            alertRoutingConfigMapper);
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
        nested.executeWithoutResult(
            status -> {
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
            code,
            ctx.tenantId(),
            ex.getMessage());
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
            (tid, s) ->
                Optional.ofNullable(jobDefinitionMapper.selectByUniqueKey(tid, s.getJobCode())),
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
    entity.setScheduleType(spec.getScheduleType());
    entity.setScheduleExpr(spec.getScheduleExpr());
    entity.setTimezone(Nullables.coalesce(spec.getTimezone(), CommonConstants.DEFAULT_TIMEZONE_ID));
    entity.setTriggerMode(Nullables.coalesce(spec.getTriggerMode(), "SCHEDULED"));
    entity.setWorkerGroup(CodeNormalizer.toUpperOrNull(spec.getWorkerGroup()));
    entity.setQueueCode(CodeNormalizer.toConfigFormOrNull(spec.getQueueCode()));
    entity.setCalendarCode(CodeNormalizer.toConfigFormOrNull(spec.getCalendarCode()));
    entity.setWindowCode(CodeNormalizer.toConfigFormOrNull(spec.getWindowCode()));
    entity.setDagEnabled(spec.getDagEnabled() != null && spec.getDagEnabled());
    entity.setShardStrategy(Nullables.coalesce(spec.getShardStrategy(), "NONE"));
    entity.setRetryPolicy(Nullables.coalesce(spec.getRetryPolicy(), "NONE"));
    entity.setRetryMaxCount(spec.getRetryMaxCount());
    entity.setTimeoutSeconds(spec.getTimeoutSeconds());
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
          "error.common.invalid_argument_detail",
          "watermarkField is required when executionMode=INCREMENTAL for job " + spec.getJobCode());
    }
    entity.setExecutionMode(executionMode);
    entity.setWatermarkField(spec.getWatermarkField());
    entity.setCreatedBy(operator);
    entity.setUpdatedBy(operator);
    jobDefinitionMapper.insert(entity);
  }

  private void updateJobDefinition(
      JobDefinitionEntity existing, JobDefinitionSpec spec, String operator) {
    JobDefinitionMaintenanceUpdateParam param = new JobDefinitionMaintenanceUpdateParam();
    param.setTenantId(existing.getTenantId());
    param.setJobCode(existing.getJobCode());
    param.setDependsOnJobCode(
        Nullables.coalesce(
            CodeNormalizer.trimToNull(spec.getDependsOnJobCode()), existing.getDependsOnJobCode()));
    param.setJobName(Nullables.coalesce(spec.getJobName(), existing.getJobName()));
    param.setQueueCode(
        Nullables.coalesce(
            CodeNormalizer.toConfigFormOrNull(spec.getQueueCode()), existing.getQueueCode()));
    param.setWorkerGroup(
        Nullables.coalesce(
            CodeNormalizer.toUpperOrNull(spec.getWorkerGroup()), existing.getWorkerGroup()));
    param.setScheduleExpr(Nullables.coalesce(spec.getScheduleExpr(), existing.getScheduleExpr()));
    param.setCalendarCode(
        Nullables.coalesce(
            CodeNormalizer.toConfigFormOrNull(spec.getCalendarCode()), existing.getCalendarCode()));
    param.setWindowCode(
        Nullables.coalesce(
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
    param.setUpdatedBy(operator);
    jobDefinitionMapper.updateJobDefinitionMaintenance(param);
  }

  ItemStats applyWorkflowDefinitions(List<WorkflowDefinitionSpec> specs, ApplyContext ctx) {
    return applySpecs(
        specs,
        ctx,
        SpecHandler.of(
            "workflow",
            WorkflowDefinitionSpec::getWorkflowCode,
            (tid, s) ->
                Optional.ofNullable(
                    workflowDefinitionMapper.selectByUniqueKey(tid, s.getWorkflowCode(), 1)),
            (c, s) -> self.upsertWorkflowDefinition(c.tenantId(), null, s, c.operator()),
            (c, s, existing) ->
                self.upsertWorkflowDefinition(c.tenantId(), existing.getId(), s, c.operator())));
  }

  @Transactional
  protected void upsertWorkflowDefinition(
      String tenantId, Long existingId, WorkflowDefinitionSpec spec, String operator) {
    WorkflowDefinitionUpsertParam param = new WorkflowDefinitionUpsertParam();
    param.setTenantId(tenantId);
    param.setWorkflowCode(spec.getWorkflowCode());
    param.setWorkflowName(spec.getWorkflowName());
    param.setWorkflowType(spec.getWorkflowType());
    param.setVersion(1);
    param.setEnabled(spec.getEnabled() != null && spec.getEnabled());
    param.setCreatedBy(operator);
    param.setUpdatedBy(operator);
    workflowDefinitionMapper.upsertWorkflowDefinition(param);

    WorkflowDefinitionEntity saved =
        workflowDefinitionMapper.selectByUniqueKey(tenantId, spec.getWorkflowCode(), 1);
    if (saved == null) {
      return;
    }
    Long defId = saved.getId();

    if (spec.getNodes() != null) {
      if (existingId != null) {
        workflowNodeMapper.deleteByWorkflowDefinitionId(defId);
        workflowEdgeMapper.deleteByWorkflowDefinitionId(defId);
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
        workflowNodeMapper.upsertWorkflowNode(nodeParam);
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
          workflowEdgeMapper.upsertWorkflowEdge(edgeParam);
        }
      }
    }
  }

  ItemStats applyPipelineDefinitions(List<PipelineDefinitionSpec> specs, ApplyContext ctx) {
    return applySpecs(
        specs,
        ctx,
        SpecHandler.<PipelineDefinitionSpec, Map<String, Object>>of(
            "pipeline",
            s -> s.getJobCode() + ":" + s.getPipelineType(),
            (tid, s) -> {
              List<Map<String, Object>> rows =
                  pipelineDefinitionMapper.selectByQuery(
                      tid, s.getJobCode(), s.getPipelineType(), null, new PageRequest(1, 1));
              return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
            },
            (c, s) -> self.insertPipelineDefinition(c.tenantId(), s),
            (c, s, existing) ->
                self.updatePipelineDefinition(
                    c.tenantId(), ((Number) existing.get(KEY_ID)).longValue(), s, existing)));
  }

  @Transactional
  protected void insertPipelineDefinition(String tenantId, PipelineDefinitionSpec spec) {
    Map<String, Object> params = new HashMap<>();
    params.put("tenant_id", tenantId);
    params.put("job_code", spec.getJobCode());
    params.put("pipeline_name", spec.getPipelineName());
    params.put("pipeline_type", spec.getPipelineType());
    params.put("biz_type", spec.getBizType());
    params.put("worker_group", spec.getWorkerGroup());
    params.put("version", 1);
    params.put(KEY_ENABLED, spec.getEnabled() != null && spec.getEnabled());
    params.put("description", spec.getDescription());
    pipelineDefinitionMapper.insert(params);
    Long defId = ((Number) params.get(KEY_ID)).longValue();
    insertPipelineSteps(defId, spec.getSteps());
  }

  @Transactional
  protected void updatePipelineDefinition(
      String tenantId, Long id, PipelineDefinitionSpec spec, Map<String, Object> existing) {
    Map<String, Object> params = new HashMap<>();
    params.put("tenant_id", tenantId);
    params.put(KEY_ID, id);
    params.put(
        "pipeline_name", Nullables.coalesce(spec.getPipelineName(), existing.get("pipeline_name")));
    params.put(
        "pipeline_type", Nullables.coalesce(spec.getPipelineType(), existing.get("pipeline_type")));
    params.put("biz_type", Nullables.coalesce(spec.getBizType(), existing.get("biz_type")));
    params.put(
        "worker_group", Nullables.coalesce(spec.getWorkerGroup(), existing.get("worker_group")));
    params.put(KEY_ENABLED, Nullables.coalesce(spec.getEnabled(), existing.get(KEY_ENABLED)));
    params.put(
        "description", Nullables.coalesce(spec.getDescription(), existing.get("description")));
    pipelineDefinitionMapper.update(params);
    if (spec.getSteps() != null) {
      pipelineStepDefinitionMapper.deleteByPipelineDefinitionId(id);
      insertPipelineSteps(id, spec.getSteps());
    }
  }

  private void insertPipelineSteps(
      Long pipelineDefinitionId, List<PipelineDefinitionSpec.StepSpec> steps) {
    if (steps == null || steps.isEmpty()) {
      return;
    }
    // 批量插入: 把 N 次单行往返折成 1 次,租户初始化场景下减少 ~10x DB round trip + 事务时长。
    List<Map<String, Object>> rows = new ArrayList<>(steps.size());
    for (PipelineDefinitionSpec.StepSpec step : steps) {
      Map<String, Object> stepParams = new HashMap<>();
      stepParams.put("pipeline_definition_id", pipelineDefinitionId);
      stepParams.put("step_code", step.getStepCode());
      stepParams.put("step_name", step.getStepName());
      stepParams.put("stage_code", step.getStageCode());
      stepParams.put("step_order", Nullables.coalesce(step.getStepOrder(), 0));
      stepParams.put("impl_code", step.getImplCode());
      stepParams.put("step_params", step.getStepParams());
      stepParams.put("timeout_seconds", Nullables.coalesce(step.getTimeoutSeconds(), 0));
      stepParams.put("retry_policy", Nullables.coalesce(step.getRetryPolicy(), "NONE"));
      stepParams.put("retry_max_count", Nullables.coalesce(step.getRetryMaxCount(), 0));
      stepParams.put(KEY_ENABLED, Nullables.coalesce(step.getEnabled(), true));
      rows.add(stepParams);
    }
    pipelineStepDefinitionMapper.insertBatch(rows);
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
            (c, s) ->
                operationalConfigSupport.upsertBusinessCalendar(
                    c.tenantId(), s, c.operator(), null),
            (c, s, existing) ->
                operationalConfigSupport.upsertBusinessCalendar(
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
