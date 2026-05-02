package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
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

  JobDefinitionRecord selectFirstByTenantAndCodeAndEnabled(
      @Param("tenantId") String tenantId,
      @Param("jobCode") String jobCode,
      @Param("enabled") Boolean enabled);

  List<JobDefinitionRecord> selectByTenantAndEnabled(
      @Param("tenantId") String tenantId, @Param("enabled") Boolean enabled);

  JobDefinitionRecord selectById(@Param("id") Long id);

  int insert(JobDefinitionRecord record);

  int update(JobDefinitionRecord record);

  int deleteById(@Param("id") Long id);
}
