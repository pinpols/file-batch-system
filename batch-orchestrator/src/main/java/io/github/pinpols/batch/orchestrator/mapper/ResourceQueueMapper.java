package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.ResourceQueueEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * batch.resource_queue 只读 Mapper。CLAUDE.md §持久化"同一表禁双主入口":本表写入主入口在 {@code batch-console-api},orch
 * 端仅 SELECT。
 */
public interface ResourceQueueMapper {

  List<ResourceQueueEntity> selectByTenantAndEnabled(
      @Param("tenantId") String tenantId, @Param("enabled") Boolean enabled);

  ResourceQueueEntity selectById(@Param("id") Long id);
}
