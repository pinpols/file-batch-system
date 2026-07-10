package io.github.pinpols.batch.console.arch;

import io.github.pinpols.batch.common.arch.BaseMapperXmlTenantGuardArchTest;
import java.util.List;
import java.util.Set;

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
   * 不传 tenantId 时全表扫。**新写 mapper 严禁加入此名单**,要么走 tenantGuard.resolveTenant 服务层回退(典型
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
        "OperationAuditMapper", // 服务层 A1 修过 tenantGuard 回退
        // 历史遗留 conditional 模式,治理中:每条均需明确"全局 admin 入口"理由,迁移完成即移除
        "WorkflowNodeMapper",
        "WorkflowEdgeMapper",
        "FileArrivalGroupMapper",
        "ConsoleUserAccountMapper",
        "OutboxRetryLogMapper");
  }

  /**
   * batch.* UPDATE/DELETE 缺 tenant_id 谓词的语句级豁免(表带 tenant_id 列,但隔离不在本条 SQL 的 WHERE)。每条注明 by-design
   * 依据。console-api 是租户可达面,故此处逐条核实过服务层隔离机制。红线:新写用户可达 batch.* 写严禁往此追加。
   */
  @Override
  protected Set<String> knownTenantlessBatchWriteStatements() {
    return Set.of(
        // 账号 CRUD:服务层 ConsoleUserAccountService 每次写前先 selectById 再 assertSameTenantOrGlobal(读校验后按
        // id 写)
        "ConsoleUserAccountMapper#updateProfile",
        "ConsoleUserAccountMapper#updatePasswordHash",
        "ConsoleUserAccountMapper#updatePasswordHashAndMustChange",
        "ConsoleUserAccountMapper#updateEnabled",
        "ConsoleUserAccountMapper#deleteById",
        // 假日子表:业务日历的子表,服务层先 calendarMapper.selectById(tenantId,id) 校验父日历归属,再按 calendar_id/id 改删
        "CalendarHolidayMapper#update",
        "CalendarHolidayMapper#deleteById",
        "CalendarHolidayMapper#deleteByCalendarId",
        "CalendarHolidayMapper#deleteByCalendarIdAndId",
        // Web Push 订阅:按浏览器 push endpoint(全局唯一)或订阅 id;touch/stale 由推送发件器/清理任务驱动
        "ConsolePushSubscriptionMapper#touchLastPushedAt",
        "ConsolePushSubscriptionMapper#deleteAllByEndpoint",
        "ConsolePushSubscriptionMapper#deleteIfStaleSince",
        // Webhook 投递重试:重试调度器全局认领后按投递 id 推进状态(非用户可达的按 id 直改)
        "ConsoleWebhookDeliveryLogMapper#claimForRetry",
        "ConsoleWebhookDeliveryLogMapper#markRetrySuccess",
        "ConsoleWebhookDeliveryLogMapper#markRetryFailure",
        "ConsoleWebhookDeliveryLogMapper#markGiveUp",
        // 工作流定义子表级联删除:按父 workflow_definition_id 删(父定义已按 tenant 校验后整体重建)
        "WorkflowNodeMapper#deleteByWorkflowDefinitionId",
        "WorkflowEdgeMapper#deleteByWorkflowDefinitionId");
  }
}
