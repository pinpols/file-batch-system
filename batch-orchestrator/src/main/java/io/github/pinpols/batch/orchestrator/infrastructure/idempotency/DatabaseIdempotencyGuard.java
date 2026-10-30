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
    // count 可识别已提交但 result 为 null 的记录；其他事务未提交的占位行不可见。
    // 本查询不能作为并发认领围栏，原子认领由 executeOnce 的唯一约束负责。
    return idempotencyRecordMapper.countByKey(tenantId, idempotencyKey) > 0;
  }
}
