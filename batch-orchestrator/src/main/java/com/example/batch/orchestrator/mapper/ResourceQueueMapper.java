package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.ResourceQueueEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * batch.resource_queue CRUD。原 {@code ResourceQueueRepository}（Spring Data JDBC）已下线，
 * 调度配额相关的资源队列读取统一由本 Mapper 接管。
 */
public interface ResourceQueueMapper {

  List<ResourceQueueEntity> selectByTenantAndEnabled(
      @Param("tenantId") String tenantId, @Param("enabled") Boolean enabled);

  ResourceQueueEntity selectById(@Param("id") Long id);

  int insert(ResourceQueueEntity record);

  int update(ResourceQueueEntity record);

  int deleteById(@Param("id") Long id);
}
