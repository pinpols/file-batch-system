package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.logging.LogSanitizer;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Nullables;
import io.github.pinpols.batch.common.validation.ValidResourceCode;
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
import io.github.pinpols.batch.console.domain.job.support.BusinessCalendarTimezone;
import io.github.pinpols.batch.console.domain.job.view.ConsoleJobConfigViews.ActiveCalendarRow;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 配置包定义的逐类应用处理器。单租户事务、类别顺序和 strict 结果由上层编排器管理；这里负责逐项保存与错误统计。
 *
 * <p>SpecHandler、ApplyContext 与 applySpecs 管理共同的查找/写入/统计流程；Job、Workflow、文件和运维规则
 * 委托各自的 ApplySupport，事务与统计不下沉到领域协作者。
 */
@Slf4j
@Component
public class TenantConfigInitApplyHandlers {

  private final TenantJobDefinitionApplySupport jobDefinitionSupport;
  private final PlatformTransactionManager transactionManager;
  private final TenantFileConfigApplySupport fileConfigSupport;
  private final TenantOperationalConfigApplySupport operationalConfigSupport;
  private final TenantWorkflowDefinitionApplySupport workflowDefinitionSupport;

  public TenantConfigInitApplyHandlers(
      TenantJobDefinitionApplySupport jobDefinitionSupport,
      PlatformTransactionManager transactionManager,
      TenantFileConfigApplySupport fileConfigSupport,
      TenantOperationalConfigApplySupport operationalConfigSupport,
      TenantWorkflowDefinitionApplySupport workflowDefinitionSupport) {
    this.jobDefinitionSupport = jobDefinitionSupport;
    this.transactionManager = transactionManager;
    this.fileConfigSupport = fileConfigSupport;
    this.operationalConfigSupport = operationalConfigSupport;
    this.workflowDefinitionSupport = workflowDefinitionSupport;
  }

  void bindTenantContext(String tenantId) {
    jobDefinitionSupport.bindTenantContext(tenantId);
  }

  void ensureManagedSingleJobWorkflows(List<JobDefinitionSpec> jobSpecs, String tenantId) {
    jobDefinitionSupport.ensureManagedSingleJobWorkflows(jobSpecs, tenantId);
  }

  /** 上下文：一次 apply 调用所需的四个不变量，避免在各类 apply 方法中重复传参。 */
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
   * upsert 调用的 6 个简单类型。
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

  private enum ApplyAction {
    CREATED,
    UPDATED,
    SKIPPED
  }

  /**
   * 公共 apply 模板：封装"查找 → 跳过/更新/创建"循环和异常收集，消除各类 apply 方法中的重复代码。
   *
   * <p>每条 spec 包裹在 PROPAGATION_NESTED 子事务里(底层用 JDBC Savepoint),单条失败回滚到 savepoint, 不污染
   * outer @Transactional 主事务 — 否则一条 SQL 报错会让 PG 把整个事务标 "aborted",后续所有 SELECT/INSERT 都撞 "current
   * transaction is aborted, commands ignored until end of transaction block",批量初始化变成
   * 全部成功或全部失败。成功统计在子事务完成后登记，避免已经回滚的条目重复计为成功。
   */
  private <T, E> ItemStats applySpecs(List<T> specs, ApplyContext ctx, SpecHandler<T, E> handler) {
    return applySpecs(specs, ctx, handler, ignored -> {});
  }

  private <T, E> ItemStats applySpecs(
      List<T> specs, ApplyContext ctx, SpecHandler<T, E> handler, Consumer<T> validate) {
    if (specs == null || specs.isEmpty()) {
      return ItemStats.empty();
    }
    TransactionTemplate nested = new TransactionTemplate(transactionManager);
    nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    ItemStatsAccumulator acc = new ItemStatsAccumulator();
    for (T spec : specs) {
      String code = handler.code(spec);
      try {
        ApplyAction action = nested.execute(status -> {
          Optional<E> existing = handler.find(ctx.tenantId(), spec);
          if (existing.isPresent()) {
            if (ctx.mode() == InitMode.UPSERT) {
              validate.accept(spec);
              if (!ctx.dryRun()) {
                handler.update(ctx, spec, existing.get());
              }
              return ApplyAction.UPDATED;
            } else {
              return ApplyAction.SKIPPED;
            }
          } else {
            validate.accept(spec);
            if (!ctx.dryRun()) {
              handler.insert(ctx, spec);
            }
            return ApplyAction.CREATED;
          }
        });
        switch (Objects.requireNonNull(action)) {
          case CREATED -> acc.recordCreated(code);
          case UPDATED -> acc.recordUpdated(code);
          case SKIPPED -> acc.recordSkipped(code);
        }
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

  // ── 9 个 spec apply 入口 (package-private,被主 service 直接调用) ────────────

  ItemStats applyJobDefinitions(List<JobDefinitionSpec> specs, ApplyContext ctx) {
    List<JobDefinitionSpec> orderedSpecs =
        TenantJobDefinitionApplySupport.orderJobDefinitionsByDependencies(specs);
    return applySpecs(
        orderedSpecs,
        ctx,
        SpecHandler.of(
            "jobDef",
            JobDefinitionSpec::getJobCode,
            (tid, s) -> Optional.ofNullable(jobDefinitionSupport.findJob(tid, s)),
            (c, s) -> jobDefinitionSupport.insertJobDefinition(c.tenantId(), s, c.operator()),
            (c, s, existing) ->
                jobDefinitionSupport.updateJobDefinition(existing, s, c.operator())),
        spec -> {
          String code = spec.getJobCode();
          if (EmptyChecks.isNull(code) || !code.matches(ValidResourceCode.REGEX)) {
            throw new IllegalArgumentException("error.common.invalid_argument");
          }
        });
  }

  ItemStats applyWorkflowDefinitions(List<WorkflowDefinitionSpec> specs, ApplyContext ctx) {
    return applySpecs(
        specs,
        ctx,
        SpecHandler.of(
            "workflow",
            WorkflowDefinitionSpec::getWorkflowCode,
            (tid, s) ->
                Optional.ofNullable(workflowDefinitionSupport.findWorkflowForImport(tid, s)),
            (c, s) -> workflowDefinitionSupport.upsertWorkflowDefinition(
                c.tenantId(), null, s, c.operator()),
            (c, s, existing) -> workflowDefinitionSupport.upsertWorkflowDefinition(
                c.tenantId(), existing.getId(), s, c.operator())),
        spec -> {
          if (EmptyChecks.isNotNull(spec.getNodes())) {
            for (WorkflowDefinitionSpec.NodeSpec node : spec.getNodes()) {
              if (EmptyChecks.isNull(node.getNodeName())) {
                throw new IllegalArgumentException("error.common.invalid_argument");
              }
            }
          }
        });
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
            (c, s) -> fileConfigSupport.upsertTemplate(c.tenantId(), s, c.operator())),
        fileConfigSupport::validateTemplate);
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
        SpecHandler.<BusinessCalendarSpec, ActiveCalendarRow>of(
            "calendar",
            BusinessCalendarSpec::getCalendarCode,
            (tid, s) -> Optional.ofNullable(operationalConfigSupport.findBusinessCalendar(tid, s)),
            (c, s) -> operationalConfigSupport.upsertBusinessCalendar(
                c.tenantId(), s, c.operator(), null),
            (c, s, existing) -> operationalConfigSupport.upsertBusinessCalendar(
                c.tenantId(), s, c.operator(), existing.id())),
        spec -> BusinessCalendarTimezone.requireValid(
            Nullables.coalesce(spec.getTimezone(), CommonConstants.DEFAULT_TIMEZONE_ID)));
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
