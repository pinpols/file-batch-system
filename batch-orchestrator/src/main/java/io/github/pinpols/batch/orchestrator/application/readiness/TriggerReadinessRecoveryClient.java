package io.github.pinpols.batch.orchestrator.application.readiness;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.http.OutboundAddressPolicy;
import io.github.pinpols.batch.common.http.OutboundHttpContent;
import io.github.pinpols.batch.common.http.OutboundHttpRequest;
import io.github.pinpols.batch.common.http.OutboundHttpResponse;
import io.github.pinpols.batch.common.http.OutboundHttpTransport;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.domain.entity.ReadinessWaitEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.http.OrchestratorOutboundTransport;
import java.io.IOException;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 调用 Trigger 内部恢复端点，在无 Quartz 回调时驱动一次计划 fire。
 *
 * <p>恢复必须由 Trigger 重建原 fire 身份（业务号/调度版本/触发类型），Orchestrator 只提供身份与上游就绪判定，
 * 不复制 Trigger 的启动语义。调用结果分四类：
 *
 * <ul>
 *   <li>{@code LAUNCHED}：已启动，等待可标记 RELEASED；
 *   <li>{@code NOT_READY}：Trigger 就绪闸未过（409 NOT_READY），等待保持 PENDING 下一轮重试；
 *   <li>{@code ALREADY_TERMINATED}：occurrence 已是终态（如被扫尾器超时终结），fire 永远不会发生，
 *       等待应直接终结而不是空转到 deadline；
 *   <li>{@code FAILED}：网络/5xx 等瞬态失败，保持 PENDING 重试。
 * </ul>
 */
@Slf4j
@Component
@EnableConfigurationProperties(TriggerInternalProperties.class)
public class TriggerReadinessRecoveryClient {

  private static final String INTERNAL_SECRET_HEADER = "X-Internal-Secret";

  private final OutboundHttpTransport transport;
  private final TriggerInternalProperties properties;
  private final String internalSecret;

  public TriggerReadinessRecoveryClient(
      TriggerInternalProperties properties,
      BatchSecurityProperties securityProperties,
      @OrchestratorOutboundTransport OutboundHttpTransport transport) {
    this.properties = properties;
    this.transport = transport;
    this.internalSecret = securityProperties.getInternalSecret();
  }

  public RecoveryOutcome recover(ReadinessWaitEntity wait) {
    if (EmptyChecks.isNull(wait) || !Texts.hasText(internalSecret)) {
      return RecoveryOutcome.FAILED;
    }
    try {
      String body = JsonUtils.toJson(Map.of(
          "tenantId", wait.getTenantId(),
          "workflowDefinitionId", wait.getWorkflowDefinitionId(),
          "scheduledFireTime", wait.getScheduledFireTime().toString()));
      String baseUrl = properties.getBaseUrl().replaceAll("/+$", "");
      OutboundHttpContent content = new OutboundHttpContent(body, "application/json");
      OutboundHttpRequest request = OutboundHttpRequest.post(
          baseUrl + "/internal/trigger/readiness-waits/recover",
          Map.of(INTERNAL_SECRET_HEADER, internalSecret),
          content,
          properties.getConnectTimeout(),
          properties.getRequestTimeout(),
          OutboundAddressPolicy.TRUSTED);
      OutboundHttpResponse response = transport.execute(request);
      if (response.isSuccessful()) {
        return RecoveryOutcome.LAUNCHED;
      }
      if (response.statusCode() == 409) {
        String outcome = extractOutcome(response.body());
        return "ALREADY_TERMINATED".equals(outcome)
            ? RecoveryOutcome.ALREADY_TERMINATED
            : RecoveryOutcome.NOT_READY;
      }
      log.warn(
          "readiness recovery call rejected: tenantId={}, workflowDefinitionId={},"
              + " scheduledFireTime={}, status={}",
          wait.getTenantId(),
          wait.getWorkflowDefinitionId(),
          wait.getScheduledFireTime(),
          response.statusCode());
      return RecoveryOutcome.FAILED;
    } catch (IOException | RuntimeException ex) {
      log.warn(
          "readiness recovery call failed; wait stays pending: tenantId={}, workflowDefinitionId={},"
              + " scheduledFireTime={}, cause={}",
          wait.getTenantId(),
          wait.getWorkflowDefinitionId(),
          wait.getScheduledFireTime(),
          SwallowedExceptionLogger.summary(ex));
      return RecoveryOutcome.FAILED;
    }
  }

  /** 409 响应体 {@code {"outcome":"..."}} 中的结果码；解析失败按 null 处理。 */
  private String extractOutcome(String responseBody) {
    try {
      RecoverOutcomeBody body = JsonUtils.fromJson(responseBody, RecoverOutcomeBody.class);
      return EmptyChecks.isNull(body) ? null : body.outcome();
    } catch (RuntimeException parseFailure) {
      return null;
    }
  }

  private record RecoverOutcomeBody(String outcome) {}

  /** 恢复调用结果。 */
  public enum RecoveryOutcome {
    LAUNCHED,
    NOT_READY,
    ALREADY_TERMINATED,
    FAILED
  }
}
