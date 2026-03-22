package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.CompensationCommandEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.Param;

public interface CompensationCommandMapper {

    int insert(CompensationCommandEntity entity);

    CompensationCommandEntity selectById(@Param("tenantId") String tenantId,
                                         @Param("id") Long id);

    int updateStatus(@Param("tenantId") String tenantId,
                     @Param("id") Long id,
                     @Param("commandStatus") String commandStatus,
                     @Param("relatedJobInstanceId") Long relatedJobInstanceId,
                     @Param("relatedFileId") Long relatedFileId,
                     @Param("resultSummary") String resultSummary,
                     @Param("errorCode") String errorCode,
                     @Param("errorMessage") String errorMessage,
                     @Param("finishedAt") Instant finishedAt);

    int countRunningByTarget(@Param("tenantId") String tenantId,
                             @Param("compensationType") String compensationType,
                             @Param("targetId") Long targetId);
}
