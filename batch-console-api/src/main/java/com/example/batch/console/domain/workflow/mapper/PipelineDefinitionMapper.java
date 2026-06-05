package com.example.batch.console.domain.workflow.mapper;

import com.example.batch.common.model.PageRequest;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface PipelineDefinitionMapper {

  List<Map<String, Object>> selectByQuery(
      @Param("tenantId") String tenantId,
      @Param("jobCode") String jobCode,
      @Param("pipelineType") String pipelineType,
      @Param("enabled") Boolean enabled,
      @Param("pageRequest") PageRequest pageRequest);

  long countByQuery(
      @Param("tenantId") String tenantId,
      @Param("jobCode") String jobCode,
      @Param("pipelineType") String pipelineType,
      @Param("enabled") Boolean enabled);

  Map<String, Object> selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  Map<String, Object> selectByUniqueKey(
      @Param("tenantId") String tenantId,
      @Param("jobCode") String jobCode,
      @Param("version") Integer version);

  int insert(Map<String, Object> params);

  int update(Map<String, Object> params);

  int toggleEnabled(
      @Param("tenantId") String tenantId, @Param("id") Long id, @Param("enabled") Boolean enabled);

  int deleteByTenantAndId(@Param("tenantId") String tenantId, @Param("id") Long id);

  /**
   * BE Spike(workflow-dag-designer): 下拉数据源,仅 enabled=true,按 jobCode 升序返回 (jobCode, pipelineName)。
   */
  List<com.example.batch.console.domain.workflow.web.response.CodeNameOption> selectActiveCodeNames(
      @Param("tenantId") String tenantId);

  /**
   * MVP DAG 兜底校验:FILE_STEP.related_pipeline_code 是否在同租户 pipeline_definition 存在(任意 enabled)。
   *
   * <p>单一查询 method:返回该 jobCode 在同租户下的记录数,>0 即存在。
   */
  long countByJobCode(@Param("tenantId") String tenantId, @Param("jobCode") String jobCode);
}
