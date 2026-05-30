package com.example.batch.console.domain.rbac.mapper;

import com.example.batch.console.domain.rbac.entity.ApiKeyEntity;
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
      @Param("tenantId") String tenantId,
      @Param("keyName") String keyName,
      @Param("keyPrefix") String keyPrefix,
      @Param("keyHash") String keyHash,
      @Param("scopes") String scopes,
      @Param("expiresAt") Instant expiresAt,
      @Param("operator") String operator);

  void revoke(
      @Param("tenantId") String tenantId, @Param("id") Long id, @Param("operator") String operator);
}
