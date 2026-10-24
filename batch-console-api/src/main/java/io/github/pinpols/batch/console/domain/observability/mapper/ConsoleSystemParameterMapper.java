package io.github.pinpols.batch.console.domain.observability.mapper;

import io.github.pinpols.batch.console.domain.observability.param.ConsoleSystemParameterUpsertParam;


import io.github.pinpols.batch.console.domain.observability.entity.SystemParameterEntity;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/**
 * {@code batch.system_parameter} MyBatis 映射（替代原 Spring Data JDBC ConsoleSystemParameterRepository）。
 */
public interface ConsoleSystemParameterMapper {

  List<SystemParameterEntity> findAllByTenant(@Param("tenantId") String tenantId);

  Optional<SystemParameterEntity> findByTenantAndKey(
      @Param("tenantId") String tenantId, @Param("paramKey") String paramKey);

  void upsert(
      ConsoleSystemParameterUpsertParam parameters);

  void deleteByTenantAndKey(@Param("tenantId") String tenantId, @Param("paramKey") String paramKey);
}
