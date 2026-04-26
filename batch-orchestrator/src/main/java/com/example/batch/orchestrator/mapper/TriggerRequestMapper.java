package com.example.batch.orchestrator.mapper;

import com.example.batch.common.persistence.entity.TriggerRequestEntity;
import org.apache.ibatis.annotations.Param;

public interface TriggerRequestMapper {

  int insert(TriggerRequestEntity entity);

  TriggerRequestEntity selectByTenantAndRequestId(
      @Param("tenantId") String tenantId, @Param("requestId") String requestId);

  TriggerRequestEntity selectByTenantAndDedupKey(
      @Param("tenantId") String tenantId, @Param("dedupKey") String dedupKey);

  int updateAcceptance(
      @Param("tenantId") String tenantId,
      @Param("requestId") String requestId,
      @Param("requestStatus") String requestStatus,
      @Param("relatedJobInstanceId") Long relatedJobInstanceId);

  /**
   * CAS 更新 trigger_type：仅当当前类型等于 {@code expectedTriggerType} 时改为 {@code triggerType}， 返回受影响行数。用于
   * late-arrival 路由等需要"先 DB 后内存"原子改写的场景，避免内存 / DB 不一致。
   */
  int updateTriggerType(
      @Param("tenantId") String tenantId,
      @Param("requestId") String requestId,
      @Param("triggerType") String triggerType,
      @Param("expectedTriggerType") String expectedTriggerType);
}
