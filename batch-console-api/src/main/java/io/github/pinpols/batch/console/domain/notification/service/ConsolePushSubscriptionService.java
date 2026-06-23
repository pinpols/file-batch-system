package io.github.pinpols.batch.console.domain.notification.service;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.config.ConsolePushProperties;
import io.github.pinpols.batch.console.domain.notification.entity.ConsolePushSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsolePushSubscriptionMapper;
import io.github.pinpols.batch.console.domain.notification.web.request.ConsolePushSubscribeRequest;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PWA Web Push 订阅管理。
 *
 * <p>controller 入口仅负责持久化前端传来的 PushSubscription 三元组;真正发推动作在 {@link ConsolePushSender}。
 */
@Service
@RequiredArgsConstructor
public class ConsolePushSubscriptionService {

  private final ConsolePushSubscriptionMapper repository;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsolePushProperties properties;

  /** 当 push 模块被关闭时,controller 应该尽早 404,这里给个判断。 */
  public boolean isEnabled() {
    return properties.isEnabled()
        && properties.getPublicKey() != null
        && properties.getPrivateKey() != null;
  }

  /** VAPID 公钥(给前端用 base64-url) */
  public String vapidPublicKey() {
    requireEnabled();
    return properties.getPublicKey();
  }

  /** 列出当前用户在当前租户下的全部订阅(用于"已绑定哪些设备"展示)。 */
  public List<ConsolePushSubscriptionEntity> listForUser(String tenantId, String username) {
    requireEnabled();
    return repository.findByTenantAndUser(tenantGuard.resolveTenant(tenantId), username);
  }

  /**
   * 订阅 upsert:同 (tenant, username, endpoint) 已存在则刷新 keys + last_seen_at。
   *
   * <p>前端每次安装 / 重新授权 / 切设备都可能重发,upsert 保证不重复主键冲突也不留下无效记录。
   */
  @Transactional
  public void subscribe(
      String tenantId, String username, ConsolePushSubscribeRequest request, String userAgent) {
    requireEnabled();
    if (request == null
        || request.endpoint() == null
        || request.endpoint().isBlank()
        || request.keys() == null
        || request.keys().p256dh() == null
        || request.keys().auth() == null) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.push.invalid_subscription");
    }
    ConsolePushSubscriptionEntity entity = new ConsolePushSubscriptionEntity();
    entity.setTenantId(tenantGuard.resolveTenant(tenantId));
    entity.setUsername(username);
    entity.setEndpoint(request.endpoint());
    entity.setP256dhKey(request.keys().p256dh());
    entity.setAuthSecret(request.keys().auth());
    entity.setUserAgent(userAgent);
    repository.upsert(entity);
  }

  /** 单条订阅取消(按 endpoint 精确删,不影响同用户其它设备)。 */
  @Transactional
  public void unsubscribe(String tenantId, String username, String endpoint) {
    requireEnabled();
    if (endpoint == null || endpoint.isBlank()) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.push.endpoint_required");
    }
    repository.deleteByEndpoint(tenantGuard.resolveTenant(tenantId), username, endpoint);
  }

  /** 定时任务用:清理 N 天未刷新的订阅。 */
  @Transactional
  public int purgeStale(Duration staleAfter) {
    return repository.deleteIfStaleSince(Instant.now().minus(staleAfter));
  }

  private void requireEnabled() {
    if (!isEnabled()) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.push.disabled");
    }
  }
}
