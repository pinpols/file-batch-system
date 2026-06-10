package com.example.batch.orchestrator.application.trigger;

import com.example.batch.common.constants.BatchStatusConstants;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.persistence.entity.TriggerRequestEntity;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.application.service.task.PartitionDispatchService;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Conservative recovery for launch T1/T2 split failures.
 *
 * <p>DefaultLaunchService first commits job_instance=CREATED in T1, then creates partitions/tasks
 * and marks RUNNING in T2. If the process dies between those transactions, the instance has no
 * executable children and Kafka lag stays at zero. This scheduler only re-drives T2 for
 * non-workflow instances that are still CREATED, still tied to ACCEPTED trigger_request, and have
 * zero partition/task rows.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "batch.trigger.launch.created-recovery",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class StaleCreatedLaunchRecoveryScheduler {

  private static final String METRIC_RECOVERED = "batch.trigger.launch.created_recovered.total";
  private static final String METRIC_FAILED = "batch.trigger.launch.created_recovery_failed.total";

  private final JobInstanceMapper jobInstanceMapper;
  private final TriggerRequestMapper triggerRequestMapper;
  private final PartitionDispatchService partitionDispatchService;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final MeterRegistry meterRegistry;

  @Value("${batch.trigger.launch.created-recovery.min-age-seconds:60}")
  private long minAgeSeconds;

  @Value("${batch.trigger.launch.created-recovery.batch-size:50}")
  private int batchSize;

  @Scheduled(
      fixedDelayString = "${batch.trigger.launch.created-recovery.poll-interval-millis:60000}")
  @SchedulerLock(
      name = "trigger_launch_created_recovery",
      lockAtMostFor = "PT5M",
      lockAtLeastFor = "PT5S")
  public void recover() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    Instant olderThan = BatchDateTimeSupport.utcNow().minusSeconds(Math.max(minAgeSeconds, 0));
    int limit = Math.max(batchSize, 1);
    List<JobInstanceEntity> candidates =
        jobInstanceMapper.selectStaleCreatedLaunchCandidates(olderThan, limit);
    if (candidates.isEmpty()) {
      return;
    }
    int recovered = 0;
    int failed = 0;
    for (JobInstanceEntity jobInstance : candidates) {
      try {
        if (recoverOne(jobInstance)) {
          recovered++;
        }
      } catch (RuntimeException ex) {
        failed++;
        log.warn(
            "stale CREATED launch recovery failed: tenantId={} jobInstanceId={} jobCode={}"
                + " error={}",
            jobInstance.getTenantId(),
            jobInstance.getId(),
            jobInstance.getJobCode(),
            ex.getMessage());
        counter(METRIC_FAILED, "tenant", jobInstance.getTenantId()).increment();
      }
    }
    log.info(
        "stale CREATED launch recovery scanned={} recovered={} failed={} olderThan={}",
        candidates.size(),
        recovered,
        failed,
        olderThan);
  }

  private boolean recoverOne(JobInstanceEntity jobInstance) {
    TriggerRequestEntity triggerRequest =
        triggerRequestMapper.selectById(
            jobInstance.getTenantId(), jobInstance.getTriggerRequestId());
    if (triggerRequest == null
        || !BatchStatusConstants.ACCEPTED.equals(triggerRequest.getRequestStatus())) {
      return false;
    }

    Map<String, Object> effectiveParams = effectiveParams(jobInstance);
    LaunchRequest request =
        LaunchRequest.builder()
            .tenantId(jobInstance.getTenantId())
            .jobCode(jobInstance.getJobCode())
            .bizDate(jobInstance.getBizDate())
            .triggerType(TriggerType.valueOf(jobInstance.getTriggerType()))
            .requestId(triggerRequest.getRequestId())
            .traceId(jobInstance.getTraceId())
            .params(effectiveParams)
            .dataIntervalStart(jobInstance.getDataIntervalStart())
            .dataIntervalEnd(jobInstance.getDataIntervalEnd())
            .replaySessionId(jobInstance.getReplaySessionId())
            .dryRun(Boolean.TRUE.equals(jobInstance.getDryRun()))
            .build();

    partitionDispatchService.dispatch(
        PartitionDispatchService.DispatchContext.of(
            new PartitionDispatchService.DispatchRequest(
                request, effectiveParams, jobInstance.getTraceId()),
            new PartitionDispatchService.DispatchRuntime(
                jobInstance, null, List.of(), BatchDateTimeSupport.utcNow())));
    // dispatch(自带事务)提交后任务已实际恢复;reconcileLaunched 在其事务外,失败不能把本次
    // 恢复记成 failed(误导监控)——trigger_request 滞留 ACCEPTED 会由 TriggerRequestLaunchReconciler
    // (ADR-010,扫"ACCEPTED 且已有 job_instance")下一轮自愈,此处降级为 WARN。
    try {
      triggerRequestMapper.reconcileLaunched(
          triggerRequest.getTenantId(), triggerRequest.getRequestId(), jobInstance.getId());
    } catch (RuntimeException ex) {
      log.warn(
          "stale CREATED launch recovered but reconcileLaunched failed (will self-heal via"
              + " TriggerRequestLaunchReconciler): tenantId={} requestId={} jobInstanceId={}"
              + " error={}",
          triggerRequest.getTenantId(),
          triggerRequest.getRequestId(),
          jobInstance.getId(),
          ex.getMessage());
    }
    counter(METRIC_RECOVERED, "tenant", jobInstance.getTenantId()).increment();
    return true;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> effectiveParams(JobInstanceEntity jobInstance) {
    Map<String, Object> snapshot =
        JsonUtils.fromJson(
            jobInstance.getParamsSnapshot(), new TypeReference<Map<String, Object>>() {});
    Object effective = snapshot.get("effectiveParams");
    if (effective instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    Object request = snapshot.get("requestParams");
    if (request instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    return Map.of();
  }

  private Counter counter(String name, String... tagPairs) {
    return Counter.builder(name).tags(Tags.of(tagPairs)).register(meterRegistry);
  }
}
