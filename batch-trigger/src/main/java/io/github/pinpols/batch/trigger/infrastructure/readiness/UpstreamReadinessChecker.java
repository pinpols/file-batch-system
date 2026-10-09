package io.github.pinpols.batch.trigger.infrastructure.readiness;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.trigger.application.UpstreamReadinessPort;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 上游就绪查询客户端(ADR-043 依赖感知 fire)。
 *
 * <p>trigger fire 前经 orchestrator 只读 API 查上游同 bizDate 是否已 SUCCESS,不直连状态表。
 *
 * <p>fail-closed(结算优先):查询失败时不放行 fire,记 ERROR 让运维介入。
 *
 * <p>emergency switch batch.trigger.readiness-gate.enabled 默认 true;关闭时一律放行。开关收敛到
 * {@link ReadinessGateProperties};本组件上的 {@code @EnableConfigurationProperties} 让该配置随组件自动注册
 * （batch-trigger 未启用 {@code @ConfigurationPropertiesScan}），与 {@code BatchTimezoneProvider} 同一约定。
 */
@Slf4j
@Component
@EnableConfigurationProperties(ReadinessGateProperties.class)
public class UpstreamReadinessChecker implements UpstreamReadinessPort {

  private final RestClient orchestratorRestClient;
  private final boolean enabled;

  public UpstreamReadinessChecker(
      RestClient orchestratorRestClient, ReadinessGateProperties readinessGateProperties) {
    this.orchestratorRestClient = orchestratorRestClient;
    this.enabled = readinessGateProperties.isEnabled();
  }

  /**
   * 上游 job 在指定 bizDate 是否就绪(已 SUCCESS)。
   *
   * @return true=就绪可 fire;false=未就绪 / 查询失败(fail-closed)
   */
  @Override
  public Optional<Instant> readyAt(String tenantId, String upstreamJobCode, LocalDate bizDate) {
    if (!enabled) {
      return Optional.of(Instant.now());
    }
    try {
      ReadinessResponse response = orchestratorRestClient
          .get()
          .uri(builder -> builder
              .path("/internal/readiness/job")
              .queryParam("tenantId", tenantId)
              .queryParam("jobCode", upstreamJobCode)
              .queryParam("bizDate", bizDate)
              .build())
          .retrieve()
          .body(ReadinessResponse.class);
      if (EmptyChecks.isNull(response) || !response.ready()) {
        return Optional.empty();
      }
      // 与旧 Orchestrator 混合部署时响应可能没有 readyAt；以本次通过门禁的时刻作为兼容基准。
      return Optional.ofNullable(response.readyAt()).or(() -> Optional.of(Instant.now()));
    } catch (RuntimeException e) {
      log.error(
          "upstream readiness check failed, fail-closed (skip fire): tenantId={} upstream={} "
              + "bizDate={} cause={}",
          tenantId,
          upstreamJobCode,
          bizDate,
          SwallowedExceptionLogger.summary(e));
      return Optional.empty();
    }
  }
}
