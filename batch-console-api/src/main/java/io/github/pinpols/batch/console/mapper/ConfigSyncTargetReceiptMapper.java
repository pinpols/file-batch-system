package io.github.pinpols.batch.console.mapper;

import org.apache.ibatis.annotations.Param;

/** 与目标配置事务一同提交的结果收据，不根据当前配置反推历史结果。 */
public interface ConfigSyncTargetReceiptMapper {

  String setTenantContext(@Param("tenantId") String tenantId);

  String selectResult(
      @Param("auditTenantId") String auditTenantId,
      @Param("logId") Long logId,
      @Param("tenantId") String tenantId);

  int insert(
      @Param("auditTenantId") String auditTenantId,
      @Param("logId") Long logId,
      @Param("tenantId") String tenantId,
      @Param("resultJson") String resultJson);
}
