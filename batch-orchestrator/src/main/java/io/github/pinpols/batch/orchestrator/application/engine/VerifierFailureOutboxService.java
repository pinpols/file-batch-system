package io.github.pinpols.batch.orchestrator.application.engine;

import io.github.pinpols.batch.common.event.DomainEvent;
import io.github.pinpols.batch.common.event.DomainEventPublisher;
import io.github.pinpols.batch.common.logging.LogSanitizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.command.VerifierFailure;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-030 §F：把 worker 上报的 ContentVerifier 失败转成 outbox 事件，与 task SUCCESS 同事务写入。
 *
 * <p>事件 schema（payload JSON v1）：
 *
 * <pre>{@code
 * {
 *   "schemaVersion": "v1",
 *   "tenantId": "<tenantId>",
 *   "taskId": <taskId>,
 *   "jobInstanceId": <jobInstanceId>,
 *   "code": "<verifierCode 例如 EXPORT_FILE_NON_EMPTY>",
 *   "reason": "<VerifyResult.code 例如 EXPORT_FILE_EMPTY>",
 *   "message": "...",
 *   "evidence": { ... }
 * }
 * }</pre>
 *
 * <p>每条 verifier 失败写 1 行（aggregate_id=jobInstanceId，event_type=verifier.failure.v1）。 调用方必须已在
 * {@code @Transactional} 内（{@link Propagation#MANDATORY}），保证：
 *
 * <ul>
 *   <li>task SUCCESS 写库失败 → outbox 也回滚（避免残留事件）
 *   <li>task SUCCESS 写库成功 + outbox 写入失败 → 全部回滚（task 仍待 worker 重投）
 * </ul>
 *
 * <p>本服务<b>不</b>翻转 task 状态：失败 verifier 是软告警，硬中止策略走 ADR-030 §G。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VerifierFailureOutboxService {

  private static final String EVENT_TYPE = "verifier.failure.v1";
  private static final String AGGREGATE_TYPE = "JOB_TASK";

  private final DomainEventPublisher domainEventPublisher;

  /** 调用方持有当前事务；本方法 MANDATORY，无事务直接抛。 */
  @Transactional(propagation = Propagation.MANDATORY)
  public int writeVerifierFailures(TaskOutcomeCommand command, JobTaskEntity task) {
    if (EmptyChecks.isNull(command) || EmptyChecks.isNull(task)) {
      return 0;
    }
    return publishFailures(command, task, VerifierFailure.fromWire(command.verifierFailures()));
  }

  /** typed 结果与旧调用共享同一事务、事件顺序和幂等键，避免主链重复解析固定 Map。 */
  @Transactional(propagation = Propagation.MANDATORY)
  public int writeVerifierFailures(
      TaskOutcomeCommand command, JobTaskEntity task, List<VerifierFailure> failures) {
    return publishFailures(command, task, failures);
  }

  // 两个公开入口各自经过事务代理；内部只共享发布逻辑，不依赖同类调用重新触发事务拦截。
  private int publishFailures(
      TaskOutcomeCommand command, JobTaskEntity task, List<VerifierFailure> failures) {
    if (EmptyChecks.isNull(command) || EmptyChecks.isNull(task)) {
      return 0;
    }
    if (EmptyChecks.isEmpty(failures)) {
      return 0;
    }
    int written = 0;
    int index = 0;
    for (VerifierFailure failure : failures) {
      if (EmptyChecks.isNull(failure)) {
        index++;
        continue;
      }
      domainEventPublisher.publish(buildEvent(command, task, failure, index));
      written++;
      index++;
    }
    if (log.isInfoEnabled()) {
      log.info(
          "ContentVerifier failures persisted as outbox events: tenantId={}, taskId={}, count={}",
          LogSanitizer.value(command.tenantId()),
          command.taskId(),
          written);
    }
    return written;
  }

  private DomainEvent buildEvent(
      TaskOutcomeCommand command, JobTaskEntity task, VerifierFailure failure, int index) {
    String reason = failure.code();
    String message = failure.message();
    Object evidence = failure.evidence();

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", "v1");
    payload.put("tenantId", command.tenantId());
    payload.put("taskId", command.taskId());
    payload.put("jobInstanceId", task.getJobInstanceId());
    payload.put("workerId", command.workerId());
    // verifier 业务码（如 EXPORT_FILE_NON_EMPTY）目前未单独传过来,复用 reason 作 code,
    // 避免引入额外字段;告警面板可同时按 reason 过滤
    payload.put("code", reason);
    payload.put("reason", reason);
    payload.put("message", message);
    payload.put("evidence", evidence);

    // event_key 加 index 后缀:若同一 task 出多个失败且 reason 相同(同 verifier 重跑 /
    // 不同 verifier 撞 code),不会触发 outbox_event 唯一约束冲突导致整事务回滚
    String eventKey = command.tenantId()
        + ":verifier:"
        + command.taskId()
        + ":"
        + (EmptyChecks.isNull(reason) ? "UNKNOWN" : reason)
        + ":"
        + index;
    return DomainEvent.builder(command.tenantId())
        .aggregate(AGGREGATE_TYPE, task.getJobInstanceId())
        .type(EVENT_TYPE)
        .key(eventKey)
        .payload(payload)
        .build();
  }
}
