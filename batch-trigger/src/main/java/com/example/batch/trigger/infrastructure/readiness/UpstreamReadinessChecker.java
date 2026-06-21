package com.example.batch.trigger.infrastructure.readiness;

import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 上游就绪查询客户端(ADR-043 依赖感知 fire)。
 *
 * <p>trigger fire 前经 orchestrator 只读 API 查上游同 bizDate 是否已 SUCCESS,不直连状态表。
 *
 * <p>fail-closed(结算优先):查询失败时不放行 fire,记 ERROR 让运维介入。
 *
 * <p>emergency switch batch.trigger.readiness-gate.enabled 默认 true;关闭时一律放行。
 */
@Slf4j
@Component
public class UpstreamReadinessChecker {

  private final RestClient orchestratorRestClient;
  private final boolean enabled;

  public UpstreamReadinessChecker(
      RestClient orchestratorRestClient,
      @Value("${batch.trigger.readiness-gate.enabled:true}") boolean enabled) {
    this.orchestratorRestClient = orchestratorRestClient;
    this.enabled = enabled;
  }

  /**
   * 上游 job 在指定 bizDate 是否就绪(已 SUCCESS)。
   *
   * @return true=就绪可 fire;false=未就绪 / 查询失败(fail-closed)
   */
  public boolean isReady(String tenantId, String upstreamJobCode, LocalDate bizDate) {
    if (!enabled) {
      return true;
    }
    try {
      ReadinessResponse response =
          orchestratorRestClient
              .get()
              .uri(
                  builder ->
                      builder
                          .path("/internal/readiness/job")
                          .queryParam("tenantId", tenantId)
                          .queryParam("jobCode", upstreamJobCode)
                          .queryParam("bizDate", bizDate)
                          .build())
              .retrieve()
              .body(ReadinessResponse.class);
      return response != null && response.ready();
    } catch (RuntimeException e) {
      log.error(
          "upstream readiness check failed, fail-closed (skip fire): tenantId={} upstream={} "
              + "bizDate={} cause={}",
          tenantId,
          upstreamJobCode,
          bizDate,
          e.getMessage());
      return false;
    }
  }
}
