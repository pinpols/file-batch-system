package com.example.batch.console.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface FilePipelineStepRunMapper {

    List<Map<String, Object>> selectByQuery(@Param("pipelineInstanceId") Long pipelineInstanceId,
                                            @Param("stepCode") String stepCode,
                                            @Param("stageCode") String stageCode,
                                            @Param("stepStatus") String stepStatus);
}
