package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationPreviewTokenEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.Param;

/** Persistence for one-use replay operation preview credentials. */
public interface ReplayOperationPreviewTokenMapper {

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
