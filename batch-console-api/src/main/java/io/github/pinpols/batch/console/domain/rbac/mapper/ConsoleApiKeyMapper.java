package io.github.pinpols.batch.console.domain.rbac.mapper;

import io.github.pinpols.batch.console.domain.rbac.param.ConsoleApiKeyInsertParam;


import io.github.pinpols.batch.console.domain.rbac.entity.ApiKeyEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/** {@code batch.api_key} MyBatis 映射（替代原 Spring Data JDBC ConsoleApiKeyRepository）。 */
public interface ConsoleApiKeyMapper {

  List<ApiKeyEntity> findAllByTenant(@Param("tenantId") String tenantId);

  Optional<ApiKeyEntity> findByTenantAndId(
      @Param("tenantId") String tenantId, @Param("id") Long id);

  Optional<ApiKeyEntity> findByTenantAndName(
      @Param("tenantId") String tenantId, @Param("keyName") String keyName);

  void insert(
      ConsoleApiKeyInsertParam parameters);

  void revoke(
      @Param("tenantId") String tenantId, @Param("id") Long id, @Param("operator") String operator);
}
