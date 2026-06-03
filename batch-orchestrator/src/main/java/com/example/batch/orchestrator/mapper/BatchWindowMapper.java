package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.BatchWindowEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * batch.batch_window 只读 Mapper。CLAUDE.md §持久化"同一表禁双主入口":本表写入主入口在 {@code batch-console-api},orch 端仅
 * SELECT。
 */
public interface BatchWindowMapper {

  BatchWindowEntity selectFirstByTenantAndCodeAndEnabled(
      @Param("tenantId") String tenantId,
      @Param("windowCode") String windowCode,
      @Param("enabled") Boolean enabled);

  List<BatchWindowEntity> selectByTenantAndEnabled(
      @Param("tenantId") String tenantId, @Param("enabled") Boolean enabled);

  BatchWindowEntity selectById(@Param("id") Long id);
}
