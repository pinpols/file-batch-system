package com.example.batch.console.domain.notification.mapper;

import com.example.batch.console.domain.notification.entity.ConsolePushSubscriptionEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** {@code batch.console_push_subscription} MyBatis 映射。 */
public interface ConsolePushSubscriptionMapper {

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

  /** 推送收到 410/404 时调:按 endpoint 全删(跨租户,因为同一物理设备 endpoint 唯一)。 */
  int deleteAllByEndpoint(@Param("endpoint") String endpoint);

  /** 更新最近一次实际推送时间(成功后) */
  int touchLastPushedAt(@Param("id") Long id, @Param("ts") Instant ts);

  /** 长时间未刷新的过期订阅(默认 60 天)清理,可由定时任务调。 */
  int deleteIfStaleSince(@Param("staleBefore") Instant staleBefore);
}
