package com.example.batch.trigger.infrastructure.scheduler;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.persistence.entity.TriggerRequestEntity;
import com.example.batch.trigger.domain.OrchestratorTriggerAdapter;
import com.example.batch.trigger.infrastructure.TriggerGracefulShutdown;
import com.example.batch.trigger.mapper.TriggerRequestMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 对转发 Orchestrator 失败（{@code FORWARD_FAILED}）的 trigger_request 做定时重试。
 *
 * <p>每次最多取 {@code BATCH_SIZE=50} 条、{@code 1 小时}内创建的记录，逐条重发；
 * 成功更新为 {@code ACCEPTED}，失败则递增 {@code forwardRetryCount}；
 * 达到 {@code MAX_RETRIES=5} 次后标记 {@code GIVE_UP} 并记录 error 日志，不再重试。
 * 4xx 错误在首次转发时已标记 {@code REJECTED}，此处不会重拾。
 * ShedLock 保证集群内只有一个节点同时执行重试，避免多节点重复转发同一请求。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TriggerForwardRetryScheduler {

  private static final int MAX_RETRIES = 5;
  private static final int BATCH_SIZE = 50;
  private static final Duration RETRY_WINDOW = Duration.ofHours(1);

  private final TriggerRequestMapper triggerRequestMapper;
  private final OrchestratorTriggerAdapter orchestratorTriggerAdapter;
  private final TriggerGracefulShutdown gracefulShutdown;

  @Scheduled(fixedDelayString = "${batch.trigger.forward-retry-interval-millis:30000}")
  @SchedulerLock(name = "trigger_forward_retry", lockAtMostFor = "PT2M", lockAtLeastFor = "PT10S")
  public void retryFailedForwards() {
    if (gracefulShutdown.isDraining()) {
      return;
    }

    Instant createdAfter = Instant.now().minus(RETRY_WINDOW);
    List<TriggerRequestEntity> failedRequests =
        triggerRequestMapper.selectForwardFailedForRetry(MAX_RETRIES, createdAfter, BATCH_SIZE);

    if (failedRequests == null || failedRequests.isEmpty()) {
      return;
    }

    log.info("trigger forward retry: found {} failed requests to retry", failedRequests.size());

    for (TriggerRequestEntity entity : failedRequests) {
      retrySingleForward(entity);
    }
  }

  private void retrySingleForward(TriggerRequestEntity entity) {
    TriggerType triggerType = resolveTriggerType(entity.getTriggerType());
    LaunchRequest request =
        new LaunchRequest(
            entity.getTenantId(),
            entity.getJobCode(),
            entity.getBizDate(),
            triggerType,
            entity.getRequestId(),
            entity.getTraceId(),
            Map.of());

    try {
      orchestratorTriggerAdapter.sendTrigger(request);
      triggerRequestMapper.updateRequestStatus(
          entity.getTenantId(), entity.getRequestId(), "ACCEPTED");
      log.info(
          "trigger forward retry succeeded: requestId={}, tenantId={}, jobCode={}",
          entity.getRequestId(),
          entity.getTenantId(),
          entity.getJobCode());
    } catch (Exception e) {
      int nextCount = entity.getForwardRetryCount() + 1;
      String nextStatus = nextCount >= MAX_RETRIES ? "GIVE_UP" : "FORWARD_FAILED";
      triggerRequestMapper.incrementForwardRetryCount(
          entity.getTenantId(), entity.getRequestId(), nextStatus);
      if (nextCount >= MAX_RETRIES) {
        log.error(
            "trigger forward retry exhausted: requestId={}, tenantId={}, jobCode={},"
                + " retries={} — marking GIVE_UP",
            entity.getRequestId(),
            entity.getTenantId(),
            entity.getJobCode(),
            nextCount);
      } else {
        log.warn(
            "trigger forward retry failed: requestId={}, tenantId={}, jobCode={},"
                + " retry={}/{} — {}",
            entity.getRequestId(),
            entity.getTenantId(),
            entity.getJobCode(),
            nextCount,
            MAX_RETRIES,
            e.getMessage());
      }
    }
  }

  private TriggerType resolveTriggerType(String code) {
    for (TriggerType type : TriggerType.values()) {
      if (type.code().equalsIgnoreCase(code)) {
        return type;
      }
    }
    return TriggerType.API;
  }
}
