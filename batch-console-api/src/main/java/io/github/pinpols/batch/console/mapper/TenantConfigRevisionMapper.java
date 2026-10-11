package io.github.pinpols.batch.console.mapper;

import org.apache.ibatis.annotations.Param;

/** 租户配置版本读取与 apply 期间的版本行锁。 */
public interface TenantConfigRevisionMapper {

  Long currentConfigurationRevision(@Param("tenantId") String tenantId);

  Long lockConfigurationRevision(@Param("tenantId") String tenantId);
}
