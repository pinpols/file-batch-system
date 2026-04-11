package com.example.batch.console.mapper;

import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface OutboxEventMapper {

    long countByStatus(
            @Param("tenantId") String tenantId, @Param("publishStatus") String publishStatus);

    List<Map<String, Object>> statsByStatus(@Param("tenantId") String tenantId);

    int deletePublishedBefore(
            @Param("tenantId") String tenantId, @Param("beforeTime") Instant beforeTime);

    int deleteGiveUpBefore(
            @Param("tenantId") String tenantId, @Param("beforeTime") Instant beforeTime);

    int resetToNew(
            @Param("tenantId") String tenantId,
            @Param("ids") List<Long> ids,
            @Param("fromStatuses") List<String> fromStatuses);
}
