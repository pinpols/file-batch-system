package com.example.batch.console.arch;

import com.example.batch.common.arch.BaseMapperXmlTenantGuardArchTest;
import java.util.List;

/**
 * 治理护栏:任何引用 batch.* 业务表的 mapper XML,SELECT/UPDATE/DELETE 都必须强制带 tenant_id 过滤, 不允许 {@code <if
 * test="tenantId != null">AND tenant_id = #{tenantId}</if>} 这种"可空" 守护(否则 service 层一旦忘记
 * tenantGuard.resolveTenant 就漏租户隔离,原 OperationAuditQueryService 漏洞 即此类型)。
 *
 * <p>规则源自 batch-common test-jar 的 {@link BaseMapperXmlTenantGuardArchTest},此处仅声明本模块白名单。
 */
class MapperXmlTenantGuardArchTest extends BaseMapperXmlTenantGuardArchTest {

  /**
   * 已知用 "全局 admin 跨租 + tenant 用户 single-tenant" 双路径的 mapper 白名单。
   *
   * <p>这些 XML 用 {@code <if tenantId>AND tenant_id=#{tenantId}} 模式刻意保留 null fallback,以支持 ROLE_ADMIN
   * 不传 tenantId 时全表扫。**新写 mapper 严禁加入此名单**,要么走 tenantGuard.resolveTenant 服务层兜底(典型
   * OperationAuditQueryService),要么把全局 admin 路径独立成单独的 mapper 方法。
   *
   * <p>本测试的核心价值是 **防回退**:有人新写 mapper 用了 if-conditional 模式 → test fail → review; 已存在的逐步治理迁移,**禁止追加**
   * 新条目除非有明确架构理由。
   */
  @Override
  protected List<String> knownConditionalTenantMappers() {
    return List.of(
        // 全局系统表(CLAUDE.md §多租隔离 4 张豁免表)
        "BizTableSchemaMapper",
        "StepRegistryMapper",
        "ShedLockMapper",
        // 后台 reconciler / archive 跨租户工具:按状态全表扫,无 tenant 参数
        "ConsoleDashboardQueryMapper", // 全局聚合视图
        "OperationAuditMapper", // 服务层 A1 修过 tenantGuard 兜底
        // 历史遗留 conditional 模式,治理中:每条均需明确"全局 admin 入口"理由,迁移完成即移除
        "WorkflowNodeMapper",
        "WorkflowEdgeMapper",
        "FileArrivalGroupMapper",
        "ConsoleUserAccountMapper",
        "OutboxRetryLogMapper");
  }
}
