package io.github.pinpols.batch.console.mapper;

import io.github.pinpols.batch.console.domain.config.param.ConfigBatchOperationInsertParam;
import io.github.pinpols.batch.console.domain.config.param.ConfigBatchTargetReceiptInsertParam;
import io.github.pinpols.batch.console.domain.config.view.ConfigBatchOperationRow;
import org.apache.ibatis.annotations.Param;

/** 原批量操作登记与逐目标事务回执。 */
public interface ConfigBatchOperationMapper {

  String setTenantContext(@Param("tenantId") String tenantId);

  int insertOperation(ConfigBatchOperationInsertParam params);

  ConfigBatchOperationRow selectOperation(
      @Param("auditTenantId") String auditTenantId,
      @Param("requestIdentityHash") String requestIdentityHash);

  int insertReceipt(ConfigBatchTargetReceiptInsertParam params);

  String selectReceipt(
      @Param("auditTenantId") String auditTenantId,
      @Param("operationId") String operationId,
      @Param("targetOrdinal") int targetOrdinal,
      @Param("tenantId") String tenantId);
}
