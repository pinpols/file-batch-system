package io.github.pinpols.batch.trigger.service;

import io.github.pinpols.batch.common.dto.LaunchEnvelope;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.TriggerMisfirePendingEntity;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.trigger.domain.command.PendingCatchUpApprovalCommand;
import io.github.pinpols.batch.trigger.domain.command.ScheduledTriggerCommand;
import io.github.pinpols.batch.trigger.domain.command.TriggerLaunchCommand;
import io.github.pinpols.batch.trigger.event.TriggerOutboxDomainEventPublisher;
import io.github.pinpols.batch.trigger.infrastructure.readiness.UpstreamReadinessChecker;
import io.github.pinpols.batch.trigger.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.trigger.mapper.TenantStatusMapper;
import io.github.pinpols.batch.trigger.mapper.TriggerMisfirePendingMapper;
import io.github.pinpols.batch.trigger.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.trigger.support.CalendarBizDateDefinition;
import io.github.pinpols.batch.trigger.support.CalendarHolidayRule;
import io.github.pinpols.batch.trigger.support.TriggerCalendarConfig;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 触发层核心服务，负责校验 → 持久化 trigger_request → 转发给 Orchestrator，提供 4 条入口：
 *
 * <ul>
 *   <li>{@link #launch} — API 手工触发，校验 idempotencyKey + bizDate + triggerType 后调用 {@link
 *       #persistAndForward}。
 *   <li>{@link #launchScheduled} — Quartz 定时触发，先解析业务日历得到 bizDate；若 bizDate=null
 *       表示日历标记为节假日+SKIP，直接跳过不产生 trigger_request。
 *   <li>{@link #createPendingCatchUp} — CatchUpPolicy=MANUAL_APPROVAL 路径，把请求以 {@code ACCEPTED}
 *       状态写入数据库等待人工审批，不立即转给 Orchestrator。
 *   <li>{@link #approvePendingCatchUp} — 人工审批通过后补跑：CAS 将 {@code ACCEPTED →
 *       PROCESSING}（防并发双审批），再在事务外 HTTP 转发， 成功后更新为 {@code LAUNCHED}。
 * </ul>
 *
 * <p><b>持久化与转发模式（{@link #persistAndForward}）</b>：在 {@code PROPAGATION_REQUIRES_NEW} 事务内同时写
 * trigger_request（PENDING）和 trigger_outbox_event（NEW），提交后立即标 ACCEPTED 返回； relay 周期发
 * Kafka，orchestrator 端 consumer 异步执行 launch。最终去重由 orchestrator 侧 {@code
 * uk_job_instance_tenant_dedup} 保证，trigger 层只做尽力去重。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultTriggerService implements TriggerService {

  private final LaunchAdapterService launchAdapterService;
  private final TriggerRequestMapper triggerRequestMapper;
  private final TriggerMisfirePendingMapper triggerMisfirePendingMapper;
  private final TriggerOutboxDomainEventPublisher triggerOutboxPublisher;
  private final BusinessCalendarMapper businessCalendarMapper;
  private final TenantStatusMapper tenantStatusMapper;
  private final PlatformTransactionManager transactionManager;
  private final UpstreamReadinessChecker upstreamReadinessChecker;

  private record PendingApprovalTarget(
      TriggerRequestEntity request, Long pendingId, boolean approvePending) {}

  @Override
  public LaunchResponse launch(TriggerLaunchCommand command) {
    validateRequest(command);
    assertTenantActive(command.request().getTenantId());
    LaunchRequest launchRequest = launchAdapterService.fromApiRequest(command);
    return persistAndForward(launchRequest, command.idempotencyKey());
  }

  @Override
  public LaunchResponse launchScheduled(ScheduledTriggerCommand command) {
    assertTenantActive(command.descriptor().getTenantId());
    LaunchRequest launchRequest =
        launchAdapterService.fromScheduledTrigger(command, loadCalendarDefinition(command));
    if (launchRequest.bizDate() == null) {
      return skipScheduled(command);
    }
    if (!upstreamReady(command, launchRequest)) {
      // ADR-043:依赖未就绪不再返回 skipped 直接丢批,改抛 UpstreamNotReadyException,
      // 由 wheel 调度器走 readiness defer(窗口内同 bizDate 重检 / 超窗 give-up),防日批晚到上游丢当天。
      throw new UpstreamNotReadyException(
          launchRequest.tenantId(),
          command.descriptor().getJobCode(),
          command.descriptor().getDependsOnJobCode(),
          launchRequest.bizDate());
    }
    String dedupKey = buildScheduledDedupKey(command);
    return persistAndForward(launchRequest, dedupKey);
  }

  /**
   * ADR-043 依赖感知 fire 闸:声明了 dependsOnJobCode 的触发器,fire 前查上游就绪。
   *
   * <p>无声明(绝大多数存量触发器)→ 直接放行,行为不变。
   */
  private boolean upstreamReady(ScheduledTriggerCommand command, LaunchRequest launchRequest) {
    String dependsOn = command.descriptor().getDependsOnJobCode();
    if (!Texts.hasText(dependsOn)) {
      return true;
    }
    return upstreamReadinessChecker.isReady(
        launchRequest.tenantId(), dependsOn, launchRequest.bizDate());
  }

  @Override
  @Transactional
  public LaunchResponse createPendingCatchUp(ScheduledTriggerCommand command) {
    assertTenantActive(command.descriptor().getTenantId());
    LaunchRequest launchRequest =
        launchAdapterService.fromScheduledTrigger(command, loadCalendarDefinition(command));
    if (launchRequest.bizDate() == null) {
      return skipScheduled(command);
    }
    String dedupKey = buildScheduledDedupKey(command);
    return persistPending(launchRequest, dedupKey, command);
  }

  @Override
  public LaunchResponse approvePendingCatchUp(PendingCatchUpApprovalCommand command) {
    validatePendingApproval(command);
    assertTenantActive(command.getTenantId());

    PendingApprovalTarget pendingTarget = resolveRequestFromPending(command);
    TriggerRequestEntity requestFromPending =
        pendingTarget == null ? null : pendingTarget.request();

    // 5.6:幂等 —— 若已有相同 key 的请求被 launch 过,则提前返回
    if (requestFromPending == null
        && command.getIdempotencyKey() != null
        && !command.getIdempotencyKey().isBlank()) {
      TriggerRequestEntity existing =
          triggerRequestMapper.selectByTenantAndDedupKey(
              command.getTenantId(), command.getIdempotencyKey());
      if (existing != null && "LAUNCHED".equalsIgnoreCase(existing.getRequestStatus())) {
        return new LaunchResponse(existing.getRequestId(), existing.getTraceId());
      }
    }

    // ADR-010：审批通过后通过 outbox 异步转发，绝不走同步 HTTP。CAS → INSERT outbox → 标 LAUNCHED
    // 三步在同事务内完成，orchestrator 宕机或网络故障不会让 trigger_request 卡在 PROCESSING。
    // relay 周期把 trigger_outbox_event 推到 Kafka topic batch.trigger.launch.v1，
    // orchestrator 端消费触发实际 launch；最终一致性由 outbox + (tenant,request_id) 唯一约束保证。
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    LaunchResponse result =
        tx.execute(
            _ -> {
              TriggerRequestEntity pendingRequest =
                  requestFromPending != null
                      ? triggerRequestMapper.selectById(requestFromPending.getId())
                      : triggerRequestMapper.selectByTenantAndRequestId(
                          command.getTenantId(), command.getRequestId());
              Guard.requireFound(pendingRequest, "pending catch-up request not found");
              if (!TriggerType.CATCH_UP.code().equalsIgnoreCase(pendingRequest.getTriggerType())) {
                throw BizException.of(ResultCode.BUSINESS_ERROR, "error.request.not_catch_up");
              }
              if ("REJECTED".equalsIgnoreCase(pendingRequest.getRequestStatus())) {
                throw BizException.of(ResultCode.BUSINESS_ERROR, "error.request.already_rejected");
              }
              if ("LAUNCHED".equalsIgnoreCase(pendingRequest.getRequestStatus())) {
                return new LaunchResponse(
                    pendingRequest.getRequestId(), pendingRequest.getTraceId());
              }
              // 原子 CAS：ACCEPTED → PROCESSING，并发审批只有一个能进入
              int claimed =
                  triggerRequestMapper.updateRequestStatusConditional(
                      command.getTenantId(), command.getRequestId(), "PROCESSING", "ACCEPTED");
              if (claimed <= 0) {
                // 另一实例正在处理；返回当前状态，重试方需重新查询
                return new LaunchResponse(
                    pendingRequest.getRequestId(), pendingRequest.getTraceId());
              }
              if (pendingTarget != null && pendingTarget.approvePending()) {
                triggerMisfirePendingMapper.approve(pendingTarget.pendingId(), "trigger-api");
              }
              LaunchRequest launchRequest =
                  new LaunchRequest(
                      pendingRequest.getTenantId(),
                      pendingRequest.getJobCode(),
                      pendingRequest.getBizDate(),
                      TriggerType.CATCH_UP,
                      pendingRequest.getRequestId(),
                      pendingRequest.getTraceId(),
                      Map.of(
                          "operationType",
                          "CATCH_UP_APPROVAL",
                          "approvalMode",
                          "MANUAL_APPROVAL",
                          "catchUpApproved",
                          true,
                          "reason",
                          command.getReason() == null ? "" : command.getReason()));
              String dedupKey =
                  Texts.hasText(pendingRequest.getDedupKey())
                      ? pendingRequest.getDedupKey()
                      : pendingRequest.getRequestId();
              publishLaunchOutbox(launchRequest, dedupKey);
              // 同事务推进到 LAUNCHED — outbox 保证 at-least-once 投递；
              // 若 relay 多轮失败 → outbox 走 GIVE_UP 路径并触发告警，trigger_request 不再长期停滞。
              triggerRequestMapper.updateRequestStatus(
                  command.getTenantId(), command.getRequestId(), "LAUNCHED");
              return new LaunchResponse(pendingRequest.getRequestId(), pendingRequest.getTraceId());
            });
    return result;
  }

  private LaunchResponse persistAndForward(LaunchRequest launchRequest, String dedupKey) {
    TriggerRequestEntity existing = insertPendingAndOutboxOrReturnExisting(launchRequest, dedupKey);
    if (existing != null) {
      return new LaunchResponse(existing.getRequestId(), existing.getTraceId());
    }
    triggerRequestMapper.updateRequestStatus(
        launchRequest.tenantId(), launchRequest.requestId(), "ACCEPTED");
    return new LaunchResponse(launchRequest.requestId(), launchRequest.traceId());
  }

  /**
   * ADR-010 异步路径:在 REQUIRES_NEW 单事务内 SELECT 去重 + INSERT trigger_request + INSERT
   * trigger_outbox_event。两表一起提交,任何一步失败整体回滚 → 不会出现 "trigger_request 写入数据库但 outbox 缺失" 的不一致。
   */
  private TriggerRequestEntity insertPendingAndOutboxOrReturnExisting(
      LaunchRequest launchRequest, String dedupKey) {
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    // R-arch-audit-2026-05-23 P1: 用 AtomicReference 替代单元素数组 holder。
    // 数组 workaround 是 lambda effectively-final 限制的反模式，AtomicReference 语义更清晰
    // 且无并发开销（TransactionTemplate.execute 单线程内同步执行）。
    AtomicReference<TriggerRequestEntity> existingHolder = new AtomicReference<>();
    tx.execute(
        _ -> {
          TriggerRequestEntity existing =
              triggerRequestMapper.selectByTenantAndDedupKey(launchRequest.tenantId(), dedupKey);
          if (existing != null) {
            existingHolder.set(existing);
            return null;
          }
          triggerRequestMapper.insert(buildPendingEntity(launchRequest, dedupKey));
          publishLaunchOutbox(launchRequest, dedupKey);
          return null;
        });
    return existingHolder.get();
  }

  /**
   * 收敛到 {@link TriggerOutboxDomainEventPublisher} 的唯一 trigger_outbox_event 写入入口。
   *
   * <p>之前主路径直接 mapper.insert(buildOutboxEntity),抽象未真正收敛字段语义会持续漂移。 现在统一走 publisher.publishRaw
   * 路径(性能等价 — 都是 LaunchEnvelope JSON 一次序列化, 跳过 DomainEvent.payload Map ↔ record 来回转换)。
   */
  private void publishLaunchOutbox(LaunchRequest r, String dedupKey) {
    String payloadJson =
        JsonUtils.toJson(LaunchEnvelope.of(r, dedupKey, BatchDateTimeSupport.utcNow()));
    triggerOutboxPublisher.publishRaw(r.tenantId(), r.requestId(), r.traceId(), payloadJson);
  }

  private TriggerRequestEntity buildPendingEntity(LaunchRequest r, String dedupKey) {
    TriggerRequestEntity entity = new TriggerRequestEntity();
    entity.setTenantId(r.tenantId());
    entity.setRequestId(r.requestId());
    entity.setTriggerType(r.triggerType().code());
    entity.setJobCode(r.jobCode());
    entity.setBizDate(r.bizDate());
    entity.setDedupKey(dedupKey);
    entity.setRequestStatus("PENDING");
    entity.setTraceId(r.traceId());
    entity.setDryRun(r.dryRun());
    return entity;
  }

  /** MANUAL_APPROVAL 场景先把 catch-up 请求登记为待审批，不立即转给 orchestrator。 */
  private LaunchResponse persistPending(
      LaunchRequest launchRequest, String dedupKey, ScheduledTriggerCommand command) {
    TriggerRequestEntity existing =
        triggerRequestMapper.selectByTenantAndDedupKey(launchRequest.tenantId(), dedupKey);
    if (existing != null) {
      linkMisfirePending(command, existing);
      return new LaunchResponse(existing.getRequestId(), existing.getTraceId());
    }
    TriggerRequestEntity entity = new TriggerRequestEntity();
    entity.setTenantId(launchRequest.tenantId());
    entity.setRequestId(launchRequest.requestId());
    entity.setTriggerType(TriggerType.CATCH_UP.code());
    entity.setJobCode(launchRequest.jobCode());
    entity.setBizDate(launchRequest.bizDate());
    entity.setDedupKey(dedupKey);
    entity.setRequestStatus("ACCEPTED");
    entity.setTraceId(launchRequest.traceId());
    entity.setDryRun(launchRequest.dryRun());
    triggerRequestMapper.insert(entity);
    linkMisfirePending(command, entity);
    return new LaunchResponse(entity.getRequestId(), entity.getTraceId());
  }

  private void linkMisfirePending(ScheduledTriggerCommand command, TriggerRequestEntity request) {
    if (command == null || command.triggerRuntimeStateId() == null || request == null) {
      return;
    }
    TriggerMisfirePendingEntity pending = new TriggerMisfirePendingEntity();
    pending.setTriggerRuntimeStateId(command.triggerRuntimeStateId());
    pending.setTenantId(command.descriptor().getTenantId());
    pending.setJobCode(command.descriptor().getJobCode());
    pending.setScheduledFireTime(command.fireTime());
    try {
      triggerMisfirePendingMapper.insertPending(pending);
    } catch (DuplicateKeyException dup) {
      pending =
          triggerMisfirePendingMapper.selectByRuntimeStateAndFireTime(
              command.triggerRuntimeStateId(), command.fireTime());
    }
    if (pending != null && pending.getId() != null && request.getId() != null) {
      triggerMisfirePendingMapper.linkCatchUpRequest(pending.getId(), request.getId());
    }
  }

  private PendingApprovalTarget resolveRequestFromPending(PendingCatchUpApprovalCommand command) {
    if (command.getPendingId() == null) {
      return null;
    }
    TriggerMisfirePendingEntity pending =
        Guard.requireFound(
            triggerMisfirePendingMapper.selectById(command.getPendingId()),
            "misfire pending not found");
    if (!command.getTenantId().equals(pending.getTenantId())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.tenant_id_required");
    }
    if ("REJECTED".equalsIgnoreCase(pending.getStatus())
        || "EXPIRED".equalsIgnoreCase(pending.getStatus())) {
      throw BizException.of(ResultCode.BUSINESS_ERROR, "error.request.already_rejected");
    }
    Long requestId = pending.getCatchUpRequestId();
    if (requestId == null) {
      throw BizException.of(
          ResultCode.BUSINESS_ERROR,
          "error.common.business_error_detail",
          "misfire pending is not linked to catch-up request");
    }
    TriggerRequestEntity request =
        Guard.requireFound(
            triggerRequestMapper.selectById(requestId), "catch-up request not found");
    command.setRequestId(request.getRequestId());
    return new PendingApprovalTarget(
        request, pending.getId(), "PENDING".equalsIgnoreCase(pending.getStatus()));
  }

  private String buildScheduledDedupKey(ScheduledTriggerCommand command) {
    // R-arch-audit-2026-05-23 P2: 用 Instant.toEpochMilli() 替代 Instant.toString()。
    // Instant.toString() 输出会按精度自动调整（如 "...:00Z" vs "...:00.000Z"），不同 JVM /
    // 序列化路径可能产生不同字符串，导致同一 fireTime 算出不同 dedupKey，去重失效。
    // toEpochMilli() 是稳定的 long → String，跨 JVM 一致。
    return command.descriptor().getTenantId()
        + ":"
        + command.descriptor().getJobCode()
        + ":"
        + command.fireTime().toEpochMilli();
  }

  private LaunchResponse skipScheduled(ScheduledTriggerCommand command) {
    log.info(
        "scheduled trigger skipped by business calendar: tenantId={}, jobCode={},"
            + " calendarCode={}, fireTime={}",
        command.descriptor().getTenantId(),
        command.descriptor().getJobCode(),
        command.descriptor().getCalendarCode(),
        command.fireTime());
    return LaunchResponse.skipped(command.traceId());
  }

  private CalendarBizDateDefinition loadCalendarDefinition(ScheduledTriggerCommand command) {
    if (command == null || command.descriptor() == null) {
      return null;
    }
    // 归一化到「配置码」形式（小写 + `-`→`_`，与 V64 migration 对 DB 存量归一一致），
    // 否则 Quartz JobDataMap 里老存下来的 `strict-calendar` 在 DB 是 `strict_calendar` 时查不到。
    String calendarCode = CodeNormalizer.toConfigFormOrNull(command.descriptor().getCalendarCode());
    if (!Texts.hasText(calendarCode)) {
      return null;
    }
    TriggerCalendarConfig calendar =
        businessCalendarMapper.selectActiveByTenantAndCalendarCode(
            command.descriptor().getTenantId(), calendarCode);
    if (calendar == null || calendar.getId() == null) {
      // M-14: 配置了日历码但数据库中未找到——告警使错误配置在日志中可见
      log.warn(
          "calendar definition not found: tenantId={}, calendarCode={} — scheduled"
              + " trigger will proceed without calendar filtering",
          command.descriptor().getTenantId(),
          calendarCode);
      return null;
    }
    List<CalendarHolidayRule> rules =
        businessCalendarMapper.selectHolidayRulesByCalendarId(calendar.getId());
    if (rules == null) {
      rules = List.of();
    }
    // R-arch-audit-2026-05-23 P1: 用 toUnmodifiableSet 替代 toSet，防止下游意外修改 holidays /
    // workdayOverrides。CalendarBizDateDefinition 是 record，字段引用不可变但 Set 本身可写，
    // toUnmodifiableSet 明确回退，符合 CLAUDE.md §集合 "返回不可变集合" 约定。
    Set<LocalDate> holidays =
        rules.stream()
            .filter(rule -> isDayType(rule, "HOLIDAY"))
            .map(CalendarHolidayRule::getBizDate)
            .collect(Collectors.toUnmodifiableSet());
    Set<LocalDate> workdayOverrides =
        rules.stream()
            .filter(rule -> isDayType(rule, "WORKDAY_OVERRIDE"))
            .map(CalendarHolidayRule::getBizDate)
            .collect(Collectors.toUnmodifiableSet());
    return new CalendarBizDateDefinition(
        calendar.getTimezone(),
        calendar.getCutoffTime(),
        calendar.getHolidayRollRule(),
        holidays,
        workdayOverrides);
  }

  private boolean isDayType(CalendarHolidayRule rule, String expectedType) {
    return rule != null
        && rule.getBizDate() != null
        && expectedType.equalsIgnoreCase(normalize(rule.getDayType()));
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  private void assertTenantActive(String tenantId) {
    String status = tenantStatusMapper.selectStatus(tenantId);
    if ("SUSPENDED".equals(status)) {
      // R-arch-audit-2026-05-23 P1: 用 ResultCode.TENANT_SUSPENDED 替代 BUSINESS_ERROR + 字符串后缀，
      // 让上游（QuartzLaunchJob / Wheel fire）能通过 getCode() 枚举比较识别租户暂停语义，
      // 不再依赖脆弱的 e.getMessage().contains("tenant is suspended")。
      throw BizException.of(
          ResultCode.TENANT_SUSPENDED,
          "error.common.business_error_detail",
          "tenant is suspended, triggers are not allowed: " + tenantId);
    }
  }

  private void validateRequest(TriggerLaunchCommand command) {
    Guard.require(command != null, "launch command is required");
    if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
      throw BizException.of(
          ResultCode.MISSING_IDEMPOTENCY_KEY,
          "error.common.missing_idempotency_key_detail",
          ResultCode.MISSING_IDEMPOTENCY_KEY.defaultMessage());
    }
    if (command.request() == null) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.request_body_required");
    }
    if (command.request().getTenantId() == null || command.request().getTenantId().isBlank()) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.tenant_id_required");
    }
    if (command.request().getJobCode() == null || command.request().getJobCode().isBlank()) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.job.code_required");
    }
    if (command.request().getBizDate() == null) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.biz_date_required");
    }
    if (command.request().getTriggerType() == null) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.trigger.type_required");
    }
  }

  private void validatePendingApproval(PendingCatchUpApprovalCommand command) {
    Guard.require(command != null, "approval command is required");
    if (command.getTenantId() == null || command.getTenantId().isBlank()) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.tenant_id_required");
    }
    if (command.getPendingId() == null
        && (command.getRequestId() == null || command.getRequestId().isBlank())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.request_id_required");
    }
  }
}
