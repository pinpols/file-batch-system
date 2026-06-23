package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * batch.job_definition 只读 Mapper。CLAUDE.md §持久化"同一表禁双主入口":本表写入主入口在 {@code batch-console-api},orch
 * 端仅 SELECT。{@code param_schema} / {@code default_params} 走 {@code ::text + MapJsonbTypeHandler}。
 */
public interface JobDefinitionMapper {

  JobDefinitionEntity selectFirstByTenantAndCodeAndEnabled(
      @Param("tenantId") String tenantId,
      @Param("jobCode") String jobCode,
      @Param("enabled") Boolean enabled);

  List<JobDefinitionEntity> selectByTenantAndEnabled(
      @Param("tenantId") String tenantId, @Param("enabled") Boolean enabled);

  JobDefinitionEntity selectById(@Param("id") Long id);
}
