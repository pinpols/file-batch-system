package io.github.pinpols.batch.console.domain.rbac.mapper;

import io.github.pinpols.batch.console.domain.rbac.entity.ConsoleUserBatchOperationEntity;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface ConsoleUserBatchOperationMapper {
  int insertOperation(
      @Param("operationId") UUID operationId,
      @Param("requestId") UUID requestId,
      @Param("actor") String actor,
      @Param("sourceDigest") String sourceDigest,
      @Param("accountCount") int accountCount,
      @Param("tenantIds") String tenantIds);

  ConsoleUserBatchOperationEntity selectByOperationId(
      @Param("operationId") UUID operationId, @Param("actor") String actor);

  ConsoleUserBatchOperationEntity selectByRequestId(
      @Param("requestId") UUID requestId, @Param("actor") String actor);
}
