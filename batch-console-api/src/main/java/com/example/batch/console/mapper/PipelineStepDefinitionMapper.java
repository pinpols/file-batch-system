package com.example.batch.console.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface PipelineStepDefinitionMapper {

    List<Map<String, Object>> selectByPipelineDefinitionId(
            @Param("pipelineDefinitionId") Long pipelineDefinitionId);

    int insert(Map<String, Object> params);

    int deleteByPipelineDefinitionId(@Param("pipelineDefinitionId") Long pipelineDefinitionId);
}
