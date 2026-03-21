package com.example.batch.console.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface FilePipelineMapper {

    List<Map<String, Object>> selectByQuery(@Param("tenantId") String tenantId,
                                            @Param("fileId") Long fileId,
                                            @Param("pipelineInstanceId") Long pipelineInstanceId,
                                            @Param("pipelineType") String pipelineType,
                                            @Param("runStatus") String runStatus,
                                            @Param("traceId") String traceId,
                                            @Param("fromTime") Instant fromTime,
                                            @Param("toTime") Instant toTime);
}
