package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.SystemParameterEntity;
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
      @Param("tenantId") String tenantId,
      @Param("paramKey") String paramKey,
      @Param("paramValue") String paramValue,
      @Param("description") String description,
      @Param("operator") String operator);

  void deleteByTenantAndKey(@Param("tenantId") String tenantId, @Param("paramKey") String paramKey);
}
