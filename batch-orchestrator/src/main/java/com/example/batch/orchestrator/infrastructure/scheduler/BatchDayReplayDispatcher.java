package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.application.service.governance.CompensationService;
import com.example.batch.orchestrator.config.BatchDayReplayDispatchProperties;
import com.example.batch.orchestrator.domain.command.CompensationSubmitCommand;
import com.example.batch.orchestrator.domain.entity.BatchDayReplayEntryEntity;
import com.example.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.BatchDayReplayEntryMapper;
import com.example.batch.orchestrator.mapper.BatchDayReplaySessionMapper;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-020 §决策 §实施分阶段 Stage 4 — RUNNING session 派发器。
 *
 * <p>每轮：
 *
 * <ul>
 *   <li>选 {@link BatchDayReplaySessionEntity#status} 为 RUNNING 的 session（OUTPUTS_ONLY 跳过 — 由 {@link
 *       com.example.batch.orchestrator.application.service.replay.BatchDayReplayService#executeOutputsOnly}
 *       同步路径完成）；
 *   <li>对每个 session 拉一批 PENDING entries，逐条调 {@link CompensationService#submit} 触发 rerun，并把 {@code
 *       replay_session_id} 透传到 {@link CompensationSubmitCommand#replaySessionId()} → 一路到 {@code
 *       job_instance.replay_session_id}（Stage 5 终态钩子按此反查 entry）；
 *   <li>compensation submit 失败 → entry status = FAILED + 失败原因；submit 成功 → entry status = RUNNING。
 * </ul>
 *
 * <p>关键不变量：
 *
 * <ul>
 *   <li>每轮处理上限由 {@link BatchDayReplayDispatchProperties#getSessionBatchSize} + {@link
 *       BatchDayReplayDispatchProperties#getEntryBatchSize} 双控；防止一次把 dispatcher 打爆；
 *   <li>{@link OrchestratorGracefulShutdown#isDraining} 期间直接 skip；
 *   <li>每个 entry 的 status 推进走独立短事务（REQUIRES_NEW），避免单条失败把整批 rollback。
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchDayReplayDispatcher {

  private static final String STATUS_RUNNING = "RUNNING";
  private static final String SCOPE_OUTPUTS_ONLY = "OUTPUTS_ONLY";
  private static final String ENTRY_PENDING = "PENDING";
  private static final String ENTRY_RUNNING = "RUNNING";
  private static final String ENTRY_FAILED = "FAILED";

  private final BatchDayReplaySessionMapper sessionMapper;
  private final BatchDayReplayEntryMapper entryMapper;
  private final CompensationService compensationService;
  private final BatchDayReplayDispatchProperties properties;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final BatchDateTimeSupport dateTimeSupport;

  /**
   * P1-5 修复(AOP 自调用失效): {@link #dispatchEntry} 标了 {@code @Transactional(REQUIRES_NEW)}, 但 {@link
   * #dispatchSession} 直接 {@code dispatchEntry(...)} 同类调用,Spring AOP 不织入,REQUIRES_NEW 退化。 注入
   * {@code @Lazy self} 走代理,真正激活独立短事务,避免单条失败回滚整批。 见 CLAUDE.md Java 编码细则 #3 豁免清单。
   */
  @Lazy @Autowired private BatchDayReplayDispatcher self;

  @Scheduled(fixedDelayString = "${batch.replay.dispatch.poll-interval-millis:30000}")
  @SchedulerLock(
      name = "batch_day_replay_dispatch",
      lockAtMostFor = "PT5M",
      lockAtLeastFor = "PT15S")
  public void scheduledDispatch() {
    if (!properties.isEnabled() || gracefulShutdown.isDraining()) {
      return;
    }
    List<BatchDayReplaySessionEntity> running =
        sessionMapper.selectByStatus(STATUS_RUNNING, properties.getSessionBatchSize());
    if (running == null || running.isEmpty()) {
      return;
    }
    for (BatchDayReplaySessionEntity session : running) {
      if (session == null || session.id() == null) {
        continue;
      }
      if (SCOPE_OUTPUTS_ONLY.equals(session.scope())) {
        // OUTPUTS_ONLY 走同步 promote 路径（BatchDayReplayService.executeOutputsOnly），dispatcher 不接管
        continue;
      }
      try {
        dispatchSession(session);
      } catch (Exception failure) {
        log.warn(
            "batch_day_replay dispatch session error: tenantId={}, sessionId={}, msg={}",
            session.tenantId(),
            session.id(),
            failure.getMessage());
      }
    }
  }

  /**
   * 单 session 派发循环：拉 PENDING entries 逐条调 compensationService.submit。每条 entry 走独立 REQUIRES_NEW 事务，
   * 避免单条失败回滚整批。
   */
  void dispatchSession(BatchDayReplaySessionEntity session) {
    List<BatchDayReplayEntryEntity> pending =
        entryMapper.selectBySessionAndStatus(
            session.id(), ENTRY_PENDING, properties.getEntryBatchSize());
    if (pending == null || pending.isEmpty()) {
      return;
    }
    for (BatchDayReplayEntryEntity entry : pending) {
      if (entry == null || entry.id() == null) {
        continue;
      }
      try {
        // P1-5: 走 @Lazy self 代理调用,确保 dispatchEntry 的 REQUIRES_NEW 生效。
        // 测试场景 self 可能未注入(纯单测),退化到 this 调用,语义不变(原行为)。
        (self != null ? self : this).dispatchEntry(session, entry);
      } catch (Exception entryFailure) {
        log.warn(
            "batch_day_replay dispatch entry error: sessionId={}, entryId={}, jobCode={}, msg={}",
            session.id(),
            entry.id(),
            entry.jobCode(),
            entryFailure.getMessage());
      }
    }
  }

  /** 单 entry 派发：成功 → entry RUNNING；失败 → entry FAILED + 失败原因。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void dispatchEntry(BatchDayReplaySessionEntity session, BatchDayReplayEntryEntity entry) {
    Instant now = dateTimeSupport.nowInstant();
    CompensationSubmitCommand command =
        CompensationSubmitCommand.builder()
            .tenantId(session.tenantId())
            .compensationType("JOB")
            .targetId(entry.sourceInstanceId())
            .jobCode(entry.jobCode())
            .bizDate(session.bizDate())
            .reason("BATCH_DAY_REPLAY:" + session.reason())
            .operatorId(session.requestedBy())
            .resultPolicy(session.resultPolicy())
            .configVersionPolicy(session.configVersionPolicy())
            .configVersion(session.configVersion())
            .replaySessionId(session.id())
            .traceId(session.traceId())
            .build();
    try {
      compensationService.submit(command);
      entryMapper.updateStatus(entry.id(), ENTRY_RUNNING, null, null, null, now, null, now);
    } catch (RuntimeException submitFailure) {
      entryMapper.updateStatus(
          entry.id(),
          ENTRY_FAILED,
          null,
          null,
          truncate(submitFailure.getMessage(), 1024),
          now,
          now,
          now);
      log.warn(
          "batch_day_replay compensation submit failed: sessionId={}, entryId={}, jobCode={},"
              + " msg={}",
          session.id(),
          entry.id(),
          entry.jobCode(),
          submitFailure.getMessage());
    }
  }

  private static String truncate(String text, int max) {
    if (text == null) {
      return null;
    }
    return text.length() <= max ? text : text.substring(0, max);
  }
}
