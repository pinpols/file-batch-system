package io.github.pinpols.batch.trigger.infrastructure;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * C-2.14：启动期对 Orchestrator 做一次握手探测，提前暴露 internal-secret 配置漂移 / 连通性问题。
 *
 * <p>过去的行为：secret 错配要等到第一次真实 trigger 打到 Orchestrator 才被 401 拦截， 发生时刻往往远离启动日志，排障成本高。现在在 {@link
 * ApplicationReadyEvent} 触发时调一次 {@code GET /internal/orchestrator/drain/status}（轻量 + 已鉴权）探测：
 *
 * <ul>
 *   <li>2xx：记 INFO，启动继续
 *   <li>401 / 403：<b>log.error</b> 明示"secret 不一致"；启动继续（不 fail-fast 以避免 trigger
 *       启动失败造成连锁延迟调度），但运维应在启动后 1 分钟内发现告警
 *   <li>连接异常：log.warn（Orchestrator 可能还没起来 / 网络暂不可达，非 secret 问题）
 * </ul>
 *
 * <p>bypass-mode=true 时跳过（dev/e2e 场景 Orchestrator 可能不共享 secret）。
 */
@Slf4j
@Component
public class OrchestratorSecretPreflightCheck {

  private final RestClient orchestratorRestClient;
  private final BatchSecurityProperties securityProperties;

  public OrchestratorSecretPreflightCheck(
      RestClient orchestratorRestClient, BatchSecurityProperties securityProperties) {
    this.orchestratorRestClient = orchestratorRestClient;
    this.securityProperties = securityProperties;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void runPreflight() {
    if (securityProperties.isBypassMode()) {
      log.info("OrchestratorSecretPreflightCheck skipped: bypass-mode=true");
      return;
    }
    try {
      HttpStatusCode status =
          orchestratorRestClient
              .get()
              .uri("/internal/orchestrator/drain/status")
              .retrieve()
              .toBodilessEntity()
              .getStatusCode();
      log.info(
          "orchestrator preflight ok: status={} — internal-secret alignment confirmed", status);
    } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden authErr) {
      log.error(
          "orchestrator preflight FAILED (auth): status={} — batch.security.internal-secret"
              + " likely mismatched between trigger and orchestrator; trigger dispatch will"
              + " keep failing with 401 until fixed",
          authErr.getStatusCode());
    } catch (RuntimeException connErr) {
      log.warn(
          "orchestrator preflight failed (non-auth): cause={} — orchestrator may still be"
              + " warming up; will not block trigger startup",
          connErr.getMessage());
    }
  }
}
