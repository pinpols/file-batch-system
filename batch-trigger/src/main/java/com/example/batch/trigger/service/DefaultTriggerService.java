package com.example.batch.trigger.service;

import com.example.batch.common.dto.LaunchEnvelope;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.persistence.entity.TriggerOutboxEventEntity;
import com.example.batch.common.persistence.entity.TriggerRequestEntity;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.CodeNormalizer;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.trigger.domain.command.PendingCatchUpApprovalCommand;
import com.example.batch.trigger.domain.command.ScheduledTriggerCommand;
import com.example.batch.trigger.domain.command.TriggerLaunchCommand;
import com.example.batch.trigger.mapper.BusinessCalendarMapper;
import com.example.batch.trigger.mapper.TenantStatusMapper;
import com.example.batch.trigger.mapper.TriggerOutboxEventMapper;
import com.example.batch.trigger.mapper.TriggerRequestMapper;
import com.example.batch.trigger.support.CalendarBizDateDefinition;
import com.example.batch.trigger.support.CalendarHolidayRule;
import com.example.batch.trigger.support.TriggerCalendarConfig;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 *       状态落库等待人工审批，不立即转给 Orchestrator。
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
  private final TriggerOutboxEventMapper triggerOutboxEventMapper;
  private final BusinessCalendarMapper businessCalendarMapper;
  private final TenantStatusMapper tenantStatusMapper;
  private final PlatformTransactionManager transactionManager;

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
    String dedupKey = buildScheduledDedupKey(command);
    return persistAndForward(launchRequest, dedupKey);
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
    return persistPending(launchRequest, dedupKey);
  }

  @Override
  public LaunchResponse approvePendingCatchUp(PendingCatchUpApprovalCommand command) {
    validatePendingApproval(command);
    assertTenantActive(command.getTenantId());

    // 5.6: idempotency — if a request with this key was already launched, return early
    if (command.getIdempotencyKey() != null && !command.getIdempotencyKey().isBlank()) {
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
                  triggerRequestMapper.selectByTenantAndRequestId(
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
              triggerOutboxEventMapper.insert(buildOutboxEntity(launchRequest, dedupKey));
              // 同事务推进到 LAUNCHED — outbox 保证 at-least-once 投递；
              // 若 relay 多轮失败 → outbox 走 GIVE_UP 路径并触发告警，trigger_request 不再卡死。
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
   * trigger_outbox_event。两表一起提交,任何一步失败整体回滚 → 不会出现 "trigger_request 落库但 outbox 缺失" 的不一致。
   */
  private TriggerRequestEntity insertPendingAndOutboxOrReturnExisting(
      LaunchRequest launchRequest, String dedupKey) {
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    final TriggerRequestEntity[] existingHolder = new TriggerRequestEntity[1];
    tx.execute(
        _ -> {
          TriggerRequestEntity existing =
              triggerRequestMapper.selectByTenantAndDedupKey(launchRequest.tenantId(), dedupKey);
          if (existing != null) {
            existingHolder[0] = existing;
            return null;
          }
          triggerRequestMapper.insert(buildPendingEntity(launchRequest, dedupKey));
          triggerOutboxEventMapper.insert(buildOutboxEntity(launchRequest, dedupKey));
          return null;
        });
    return existingHolder[0];
  }

  private TriggerOutboxEventEntity buildOutboxEntity(LaunchRequest r, String dedupKey) {
    TriggerOutboxEventEntity entity = new TriggerOutboxEventEntity();
    entity.setTenantId(r.tenantId());
    entity.setRequestId(r.requestId());
    entity.setTopic("batch.trigger.launch.v1");
    entity.setPayload(
        JsonUtils.toJson(LaunchEnvelope.of(r, dedupKey, BatchDateTimeSupport.utcNow())));
    entity.setPublishStatus(OutboxPublishStatus.NEW.code());
    entity.setPublishAttempt(0);
    entity.setTraceId(r.traceId());
    entity.setNextPublishAt(BatchDateTimeSupport.utcNow());
    return entity;
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
  private LaunchResponse persistPending(LaunchRequest launchRequest, String dedupKey) {
    TriggerRequestEntity existing =
        triggerRequestMapper.selectByTenantAndDedupKey(launchRequest.tenantId(), dedupKey);
    if (existing != null) {
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
    return new LaunchResponse(entity.getRequestId(), entity.getTraceId());
  }

  private String buildScheduledDedupKey(ScheduledTriggerCommand command) {
    return command.descriptor().getTenantId()
        + ":"
        + command.descriptor().getJobCode()
        + ":"
        + command.fireTime();
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
    Set<LocalDate> holidays =
        rules.stream()
            .filter(rule -> isDayType(rule, "HOLIDAY"))
            .map(CalendarHolidayRule::getBizDate)
            .collect(Collectors.toSet());
    Set<LocalDate> workdayOverrides =
        rules.stream()
            .filter(rule -> isDayType(rule, "WORKDAY_OVERRIDE"))
            .map(CalendarHolidayRule::getBizDate)
            .collect(Collectors.toSet());
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
      throw BizException.of(
          ResultCode.BUSINESS_ERROR,
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
    if (command.getRequestId() == null || command.getRequestId().isBlank()) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.request_id_required");
    }
  }
}
