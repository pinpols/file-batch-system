package io.github.pinpols.batch.orchestrator.arch;

import io.github.pinpols.batch.common.arch.BaseMapperXmlTenantGuardArchTest;
import java.util.List;

/**
 * 治理护栏:batch-orchestrator mapper XML 中 {@code <if test="tenantId != null">AND tenant_id =
 * #{tenantId}</if>} 这种"可空"租户过滤,只允许在已知的 ROLE_ADMIN 跨租运维入口存在。新增 mapper 必须走无条件 {@code AND tenant_id =
 * #{tenantId}} 或加入白名单并写明原因。
 *
 * <p>规则源自 batch-common test-jar 的 {@link BaseMapperXmlTenantGuardArchTest},此处仅声明本模块白名单。
 */
class MapperXmlTenantGuardArchTest extends BaseMapperXmlTenantGuardArchTest {

  /**
   * 已知 ROLE_ADMIN 跨租运维查询 / dashboard 聚合的 mapper 白名单。新写 mapper **严禁追加** — 应改成无条件租户过滤(典型业务路径)或拆出独立的
   * admin 全局方法。
   */
  @Override
  protected List<String> knownConditionalTenantMappers() {
    return List.of(
        // 跨租 retry / outbox 重试观察台 — admin 全局查
        "EventDeliveryLogMapper",
        "EventOutboxRetryMapper",
        "RetryScheduleMapper",
        "OutboxEventMapper",
        // 跨租 batch-day 等待视图 — admin 全局排查
        "BatchDayWaitingLaunchMapper",
        // 跨租文件治理聚合
        "FileGovernanceMapper");
  }
}
