package io.github.pinpols.batch.trigger.arch;

import io.github.pinpols.batch.common.arch.BaseMapperXmlTenantGuardArchTest;
import java.util.Set;

/**
 * batch-trigger 多租 mapper XML 守护。规则源自 batch-common test-jar 的 {@link
 * BaseMapperXmlTenantGuardArchTest}。
 */
class TriggerMapperXmlTenantGuardArchTest extends BaseMapperXmlTenantGuardArchTest {

  /**
   * batch.* UPDATE/DELETE 缺 tenant_id 谓词的语句级豁免。batch-trigger 是内部调度进程,以下均为调度器/发件箱内部状态机(全局轮询 + 按全局 id
   * 或状态推进),非用户可达按 id 直改。红线:新写严禁往此追加。
   */
  @Override
  protected Set<String> knownTenantlessBatchWriteStatements() {
    return Set.of(
        // 补触发审批:控制台经内部 trigger API 按 misfire id + 状态推进;markExpired 为跨租过期 reaper
        "TriggerMisfirePendingMapper#approve",
        "TriggerMisfirePendingMapper#reject",
        "TriggerMisfirePendingMapper#linkCatchUpRequest",
        "TriggerMisfirePendingMapper#markExpired",
        // 触发发件箱发布器状态机:发布器全局轮询后按全局行 id 推进;resetStalePublishing 为跨租 reaper
        "TriggerOutboxEventMapper#markPublishing",
        "TriggerOutboxEventMapper#markPublished",
        "TriggerOutboxEventMapper#markFailed",
        "TriggerOutboxEventMapper#resetStalePublishing");
  }
}
