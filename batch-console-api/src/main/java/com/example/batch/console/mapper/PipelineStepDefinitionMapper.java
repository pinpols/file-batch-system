package com.example.batch.console.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface PipelineStepDefinitionMapper {

  List<Map<String, Object>> selectByPipelineDefinitionId(
      @Param("pipelineDefinitionId") Long pipelineDefinitionId);

  int insert(Map<String, Object> params);

  /** 批量插入。空集合视为 no-op,返回 0。租户初始化 / Excel 导入大批量场景必走此版本。 */
  int insertBatch(List<Map<String, Object>> rows);

  int deleteByPipelineDefinitionId(@Param("pipelineDefinitionId") Long pipelineDefinitionId);
}
