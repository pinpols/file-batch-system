package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * batch.workflow_definition 只读 Mapper。CLAUDE.md §持久化"同一表禁双主入口":本表写入主入口在 {@code
 * batch-console-api}(用户 UI CRUD),orch 端仅 SELECT 用于缓存 / reconciler。
 *
 * <p>orchestrator 若需要 seeding / 修复定义数据,走 db migration 或调用 console-api 的 ProxyService, 不得在本接口加
 * insert/update/delete。
 */
public interface WorkflowDefinitionMapper {

  /** 按 (tenantId, workflowCode, enabled) 取首条；用于 cache 服务回源。 */
  WorkflowDefinitionEntity selectFirstByTenantAndCodeAndEnabled(
      @Param("tenantId") String tenantId,
      @Param("workflowCode") String workflowCode,
      @Param("enabled") Boolean enabled);

  /** 列出指定 tenant 下指定启用状态的全部 workflow 定义；console 列表 / 缓存预热用。 */
  List<WorkflowDefinitionEntity> selectByTenantAndEnabled(
      @Param("tenantId") String tenantId, @Param("enabled") Boolean enabled);

  WorkflowDefinitionEntity selectById(@Param("id") Long id);

  /** ADR-025 reconciler：跨租户列出 enabled=true 全量。 */
  List<WorkflowDefinitionEntity> selectAllEnabled(@Param("limit") int limit);
}
