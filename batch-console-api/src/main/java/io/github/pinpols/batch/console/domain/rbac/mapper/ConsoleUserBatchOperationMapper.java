package io.github.pinpols.batch.console.domain.rbac.mapper;

import io.github.pinpols.batch.console.domain.rbac.param.ConsoleUserBatchOperationInsertOperationParam;
import io.github.pinpols.batch.console.domain.rbac.view.ConsoleUserBatchOperationView;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface ConsoleUserBatchOperationMapper {
  int insertOperation(ConsoleUserBatchOperationInsertOperationParam parameters);

  ConsoleUserBatchOperationView selectByOperationId(
      @Param("operationId") UUID operationId,
      @Param("actor") String actor,
      @Param("targetTenantId") String targetTenantId);

  ConsoleUserBatchOperationView selectByRequestId(
      @Param("requestId") UUID requestId,
      @Param("actor") String actor,
      @Param("targetTenantId") String targetTenantId);
}
