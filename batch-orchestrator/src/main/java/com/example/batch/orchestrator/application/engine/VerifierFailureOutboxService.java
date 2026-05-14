package com.example.batch.orchestrator.application.engine;

import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
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
 *   <li>task SUCCESS 写库失败 → outbox 也回滚（避免幽灵事件）
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

  private final OutboxEventMapper outboxEventMapper;

  /** 调用方持有当前事务；本方法 MANDATORY，无事务直接抛。 */
  @Transactional(propagation = Propagation.MANDATORY)
  public int writeVerifierFailures(TaskOutcomeCommand command, JobTaskEntity task) {
    if (command == null || task == null) {
      return 0;
    }
    List<Map<String, Object>> failures = command.verifierFailures();
    if (failures == null || failures.isEmpty()) {
      return 0;
    }
    int written = 0;
    int index = 0;
    for (Map<String, Object> failure : failures) {
      if (failure == null) {
        index++;
        continue;
      }
      OutboxEventEntity event = buildEvent(command, task, failure, index);
      outboxEventMapper.insert(event);
      written++;
      index++;
    }
    if (log.isInfoEnabled()) {
      log.info(
          "ContentVerifier failures persisted as outbox events: tenantId={}, taskId={}, count={}",
          command.tenantId(),
          command.taskId(),
          written);
    }
    return written;
  }

  private OutboxEventEntity buildEvent(
      TaskOutcomeCommand command, JobTaskEntity task, Map<String, Object> failure, int index) {
    String reason = stringValue(failure.get("code"));
    String message = stringValue(failure.get("message"));
    Object evidence = failure.get("evidence");

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", "v1");
    payload.put("tenantId", command.tenantId());
    payload.put("taskId", command.taskId());
    payload.put("jobInstanceId", task.getJobInstanceId());
    payload.put("workerId", command.workerId());
    // verifier 业务码（如 EXPORT_FILE_NON_EMPTY）目前未单独传过来，复用 reason 作为 code，
    // 避免引入额外字段；告警面板可同时按 reason 过滤。
    payload.put("code", reason);
    payload.put("reason", reason);
    payload.put("message", message);
    payload.put("evidence", evidence);

    OutboxEventEntity event = new OutboxEventEntity();
    event.setTenantId(command.tenantId());
    event.setAggregateType(AGGREGATE_TYPE);
    event.setAggregateId(task.getJobInstanceId());
    event.setEventType(EVENT_TYPE);
    // event_key 加 index 后缀：若同一 task 出多个失败且 reason 相同（同 verifier 重跑 / 不同
    // verifier 撞 code），不会触发 outbox_event 唯一约束冲突导致整事务回滚。
    event.setEventKey(
        command.tenantId()
            + ":verifier:"
            + command.taskId()
            + ":"
            + (reason == null ? "UNKNOWN" : reason)
            + ":"
            + index);
    event.setPayloadJson(JsonUtils.toJson(payload));
    event.setPublishStatus(OutboxPublishStatus.NEW.code());
    event.setPublishAttempt(0);
    return event;
  }

  private static String stringValue(Object value) {
    return value == null ? null : value.toString();
  }
}
