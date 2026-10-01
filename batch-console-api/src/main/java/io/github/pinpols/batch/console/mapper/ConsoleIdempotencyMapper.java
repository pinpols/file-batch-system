package io.github.pinpols.batch.console.mapper;

import org.apache.ibatis.annotations.Param;

/** Console HTTP 幂等完成态的持久化 Mapper。 */
public interface ConsoleIdempotencyMapper {

  /** 查询 Console 层是否已经持久化完成态。 */
  int countCompleted(
      @Param("tenantId") String tenantId, @Param("idempotencyKey") String idempotencyKey);

  /** Redis 完成态写失败时，使用平台库保留最终完成标记。 */
  int insertCompleted(
      @Param("tenantId") String tenantId, @Param("idempotencyKey") String idempotencyKey);
}
