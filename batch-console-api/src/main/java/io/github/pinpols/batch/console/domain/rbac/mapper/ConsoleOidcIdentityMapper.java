package io.github.pinpols.batch.console.domain.rbac.mapper;

import io.github.pinpols.batch.console.domain.rbac.entity.ConsoleOidcIdentityEntity;
import io.github.pinpols.batch.console.domain.rbac.entity.ConsoleUserAccountEntity;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/** OIDC 外部身份映射读写；租户一致性同时由数据库复合外键保证。 */
public interface ConsoleOidcIdentityMapper {

  Optional<ConsoleUserAccountEntity> findEnabledAccount(
      @Param("tenantId") String tenantId,
      @Param("issuer") String issuer,
      @Param("subject") String subject);

  List<ConsoleOidcIdentityEntity> findByTenant(@Param("tenantId") String tenantId);

  Optional<ConsoleOidcIdentityEntity> findByAccountAndTenant(
      @Param("accountId") long accountId, @Param("tenantId") String tenantId);

  int insert(
      @Param("tenantId") String tenantId,
      @Param("accountId") long accountId,
      @Param("issuer") String issuer,
      @Param("subject") String subject,
      @Param("linkedBy") String linkedBy);

  int deleteByIdAndTenant(@Param("id") long id, @Param("tenantId") String tenantId);
}
