package com.example.batch.orchestrator.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * D-1: batch.idempotency_record 表的 Mapper。
 */
public interface IdempotencyRecordMapper {

    /**
     * 插入幂等记录，key 已存在则忽略（INSERT ... ON CONFLICT DO NOTHING），成功返回 1，已存在返回 0。
     */
    int insertIfAbsent(@Param("tenantId") String tenantId,
                       @Param("idempotencyKey") String idempotencyKey,
                       @Param("resultPayload") String resultPayload);

    /**
     * 查询已执行操作的结果载荷。
     */
    String selectResultByKey(@Param("tenantId") String tenantId,
                             @Param("idempotencyKey") String idempotencyKey);
}
