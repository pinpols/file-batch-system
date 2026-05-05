package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.BatchDayWaitingLaunchEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface BatchDayWaitingLaunchMapper {

  int insert(BatchDayWaitingLaunchEntity entity);

  BatchDayWaitingLaunchEntity selectByTenantAndRequestId(
      @Param("tenantId") String tenantId, @Param("requestId") String requestId);

  List<BatchDayWaitingLaunchEntity> selectWaiting(
      @Param("tenantId") String tenantId, @Param("limit") int limit);
}
