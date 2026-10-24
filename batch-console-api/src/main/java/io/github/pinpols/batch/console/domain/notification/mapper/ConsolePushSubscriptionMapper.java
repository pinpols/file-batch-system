package io.github.pinpols.batch.console.domain.notification.mapper;

import io.github.pinpols.batch.console.domain.notification.entity.ConsolePushSubscriptionEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/** {@code batch.console_push_subscription} MyBatis 映射。 */
public interface ConsolePushSubscriptionMapper {
  Optional<ConsolePushSubscriptionEntity> findByTenantAndId(
      @Param("tenantId") String tenantId, @Param("id") Long id);

  /** 推送侧批量查:某租户某用户的全部活跃订阅(多设备各一条)。 */
  List<ConsolePushSubscriptionEntity> findByTenantAndUser(
      @Param("tenantId") String tenantId, @Param("username") String username);

  /** 推送侧按租户广播(罕用,例如系统级公告);谨慎使用,避免风暴。 */
  List<ConsolePushSubscriptionEntity> findByTenant(@Param("tenantId") String tenantId);

  /**
   * UPSERT:同 (tenant_id, username, endpoint) 已存在则更新 keys + last_seen_at; 否则新建。前端每次安装 / 重新授权都重发同
   * endpoint,UPSERT 避免重复主键冲突。
   */
  int upsert(ConsolePushSubscriptionEntity sub);

  /** unsubscribe 端点用:按 (tenant, username, endpoint) 删除单条。 */
  int deleteByEndpoint(
      @Param("tenantId") String tenantId,
      @Param("username") String username,
      @Param("endpoint") String endpoint);

  /** Provider 回执只更新发送时的原订阅凭据版本。 */
  int touchIfUnchanged(@Param("row") ConsolePushSubscriptionEntity row, @Param("ts") Instant ts);

  /** Provider 的过期回执不得删除发送后已更新的设备凭据。 */
  int deleteIfUnchanged(@Param("row") ConsolePushSubscriptionEntity row);

  /** 长时间未刷新的过期订阅(默认 60 天)清理,可由定时任务调。 */
  int deleteIfStaleSince(@Param("staleBefore") Instant staleBefore);
}
