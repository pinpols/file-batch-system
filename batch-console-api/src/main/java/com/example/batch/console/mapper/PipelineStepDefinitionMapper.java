package com.example.batch.console.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface PipelineStepDefinitionMapper {

  List<Map<String, Object>> selectByPipelineDefinitionId(
      @Param("pipelineDefinitionId") Long pipelineDefinitionId);

  int insert(Map<String, Object> params);

  int deleteByPipelineDefinitionId(@Param("pipelineDefinitionId") Long pipelineDefinitionId);
}
