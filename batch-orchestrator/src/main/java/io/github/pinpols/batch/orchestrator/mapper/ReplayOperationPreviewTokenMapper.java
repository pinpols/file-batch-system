package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationPreviewTokenEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.Param;

/** 一次性重放操作预览凭证的持久化。 */
public interface ReplayOperationPreviewTokenMapper {

  String setTenantContext(@Param("tenantId") String tenantId);

  int insert(ReplayOperationPreviewTokenEntity token);

  ReplayOperationPreviewTokenEntity selectForUpdate(
      @Param("tenantId") String tenantId, @Param("tokenHash") String tokenHash);

  int consume(
      @Param("tenantId") String tenantId,
      @Param("tokenHash") String tokenHash,
      @Param("now") Instant now);

  int linkOperation(
      @Param("tenantId") String tenantId,
      @Param("tokenHash") String tokenHash,
      @Param("operationId") Long operationId);
}
