package io.github.pinpols.batch.orchestrator.infrastructure.idempotency;

import io.github.pinpols.batch.common.service.IdempotencyGuard;
import io.github.pinpols.batch.orchestrator.mapper.IdempotencyRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * D-1: 基于 {@code batch.idempotency_record} 表的幂等守卫实现。 利用 PostgreSQL {@code INSERT ... ON CONFLICT DO
 * NOTHING} 实现原子性检查-插入， 同一 key 重复调用不会重复执行，直接返回上次结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseIdempotencyGuard implements IdempotencyGuard {

  private final IdempotencyRecordMapper idempotencyRecordMapper;

  @Override
  @Transactional
  public String executeOnce(String tenantId, String idempotencyKey, IdempotentAction action) {
    // 尝试占位：ON CONFLICT DO NOTHING 在 key 已存在时返回 0
    int inserted = idempotencyRecordMapper.insertIfAbsent(tenantId, idempotencyKey, null);
    if (inserted <= 0) {
      log.debug("idempotency key already executed: tenantId={}, key={}", tenantId, idempotencyKey);
      return idempotencyRecordMapper.selectResultByKey(tenantId, idempotencyKey);
    }
    // 本事务成功占位，执行业务动作；同事务内回写 result，保证幂等语义完整
    String result = action.execute();
    if (result != null) {
      idempotencyRecordMapper.updateResult(tenantId, idempotencyKey, result);
    }
    return result;
  }

  @Override
  public boolean isAlreadyExecuted(String tenantId, String idempotencyKey) {
    // 用 countByKey 而非 selectResultByKey != null,避免 race:executeOnce 占位行 result=null
    // 期间并发调用本方法会判定"未执行"导致重复执行。占位行存在即视为"已认领",由占位
    // 事务负责完成回写;调用方若需要等待结果,应轮询 selectResultByKey。
    return idempotencyRecordMapper.countByKey(tenantId, idempotencyKey) > 0;
  }
}
