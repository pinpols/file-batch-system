package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.JobDefinitionEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * batch.job_definition CRUD。原 {@code JobDefinitionRepository}（Spring Data JDBC）已下线， 配置态读写统一由本
 * Mapper 接管。
 *
 * <p>{@code param_schema} / {@code default_params} 是 {@code Map<String,Object>} → JSONB；读路径走 {@code
 * ::text + MapJsonbTypeHandler}，写路径走 {@code cast(#{...} as jsonb)} + {@code MapJsonbTypeHandler}。
 */
public interface JobDefinitionMapper {

  JobDefinitionEntity selectFirstByTenantAndCodeAndEnabled(
      @Param("tenantId") String tenantId,
      @Param("jobCode") String jobCode,
      @Param("enabled") Boolean enabled);

  List<JobDefinitionEntity> selectByTenantAndEnabled(
      @Param("tenantId") String tenantId, @Param("enabled") Boolean enabled);

  JobDefinitionEntity selectById(@Param("id") Long id);

  int insert(JobDefinitionEntity record);

  int update(JobDefinitionEntity record);

  int deleteById(@Param("id") Long id);
}
