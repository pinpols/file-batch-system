package com.example.batch.orchestrator.mapper;

import org.apache.ibatis.annotations.Param;

/** D-1: batch.idempotency_record 表的 Mapper。 */
public interface IdempotencyRecordMapper {

  /** 插入幂等记录，key 已存在则忽略（INSERT ... ON CONFLICT DO NOTHING），成功返回 1，已存在返回 0。 */
  int insertIfAbsent(
      @Param("tenantId") String tenantId,
      @Param("idempotencyKey") String idempotencyKey,
      @Param("resultPayload") String resultPayload);

  /** 查询已执行操作的结果载荷。 */
  String selectResultByKey(
      @Param("tenantId") String tenantId, @Param("idempotencyKey") String idempotencyKey);

  /**
   * 查询是否存在该 key 的占位/完成记录(包括 result_payload 为 null 的"执行中"占位)。
   *
   * <p>用于 {@link DatabaseIdempotencyGuard#isAlreadyExecuted} 防止 race:occupant 已 INSERT 占位但 result
   * 尚未回写时,并发查询不应判定为"未执行"。
   */
  int countByKey(
      @Param("tenantId") String tenantId, @Param("idempotencyKey") String idempotencyKey);

  /** 回写已执行操作的结果载荷（在同事务内调用，保证 result 与占位行同事务落库）。 */
  int updateResult(
      @Param("tenantId") String tenantId,
      @Param("idempotencyKey") String idempotencyKey,
      @Param("resultPayload") String resultPayload);
}
