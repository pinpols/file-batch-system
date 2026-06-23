package io.github.pinpols.batch.console.domain.rbac.mapper;

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

  // MyBatis 列绑定签名，等价于 record/DTO 构造器——按 build/pmd-ruleset.xml 注释规约豁免
  @SuppressWarnings("PMD.ExcessiveParameterList")
  void insert(
      @Param("tenantId") String tenantId,
      @Param("keyName") String keyName,
      @Param("keyPrefix") String keyPrefix,
      @Param("keyHash") String keyHash,
      @Param("salt") String salt,
      @Param("keyHashAlgo") String keyHashAlgo,
      @Param("scopes") String scopes,
      @Param("expiresAt") Instant expiresAt,
      @Param("operator") String operator);

  void revoke(
      @Param("tenantId") String tenantId, @Param("id") Long id, @Param("operator") String operator);
}
