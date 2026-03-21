package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.domain.query.OutboxEventQuery;
import java.util.List;

public interface OutboxEventMapper {

    int insert(OutboxEventEntity entity);

    List<OutboxEventEntity> selectPending(OutboxEventQuery query);

    int markPublishing(@org.apache.ibatis.annotations.Param("tenantId") String tenantId,
                       @org.apache.ibatis.annotations.Param("id") Long id);

    int markPublished(@org.apache.ibatis.annotations.Param("tenantId") String tenantId,
                      @org.apache.ibatis.annotations.Param("id") Long id);

    int markFailed(@org.apache.ibatis.annotations.Param("tenantId") String tenantId,
                   @org.apache.ibatis.annotations.Param("id") Long id,
                   @org.apache.ibatis.annotations.Param("nextPublishAt") java.time.Instant nextPublishAt);
}
