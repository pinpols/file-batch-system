package io.github.pinpols.batch.orchestrator.application.trigger;

import io.github.pinpols.batch.common.dto.LaunchEnvelope;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.service.task.LaunchApplicationService;
import io.github.pinpols.batch.orchestrator.domain.entity.TriggerLaunchRecoveryEntity;
import io.github.pinpols.batch.orchestrator.domain.param.TriggerLaunchRecoveryAttemptParam;
import io.github.pinpols.batch.orchestrator.domain.param.TriggerLaunchRecoveryRetryParam;
import io.github.pinpols.batch.orchestrator.mapper.TriggerLaunchRecoveryMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Kafka 消费有限重试耗尽后的持久恢复。
 *
 * <p>触发消费端在 {@code retryAttempts} 次后本来会跳过记录（提交 offset）；若该请求尚未写出任何持久等待/prepare
 * 记录，消息是唯一载体，被跳过后请求永久失去执行机会。这里把原始 envelope 以密文落库，再由
 * {@link TriggerLaunchRecoveryScheduler} 幂等重投；重投有上限并保留最后错误码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TriggerLaunchRecoveryService {

  private static final String STATUS_DONE = "DONE";
  private static final String STATUS_PENDING = "PENDING";
  private static final String STATUS_DEAD = "DEAD";
  private static final String STATUS_REJECTED = "REJECTED";
  private static final String METRIC_PARKED = "batch.trigger.launch.recovery.parked.total";
  private static final String METRIC_RECOVERED = "batch.trigger.launch.recovery.recovered.total";

  private final TriggerLaunchRecoveryMapper mapper;
  private final SecretPayloadProtector payloadProtector;
  private final LaunchApplicationService launchApplicationService;
  private final TriggerLaunchRecoveryProperties properties;
  private final MeterRegistry meterRegistry;

  /**
   * 消费端重试耗尽时登记恢复锚点；任何解析/写库失败都只返回 false，不影响原始日志与指标路径。
   */
  @Transactional
  public boolean park(String envelopeJson, String traceId) {
    if (!Texts.hasText(envelopeJson)) {
      return false;
    }
    LaunchEnvelope envelope;
    try {
      envelope = JsonUtils.fromJson(envelopeJson, LaunchEnvelope.class);
    } catch (RuntimeException ex) {
      log.warn(
          "trigger launch recovery cannot parse envelope; nothing to park: cause={}",
          SwallowedExceptionLogger.summary(ex));
      return false;
    }
    if (EmptyChecks.isNull(envelope) || EmptyChecks.isNull(envelope.launchRequest())) {
      return false;
    }
    LaunchRequest request = envelope.launchRequest();
    if (!Texts.hasText(request.tenantId()) || !Texts.hasText(request.requestId())) {
      return false;
    }
    int inserted = mapper.insertPending(
        request.tenantId(),
        request.requestId(),
        payloadProtector.protect(envelopeJson),
        Texts.hasText(traceId) ? traceId : request.traceId());
    ParkOutcome outcome =
        inserted == 1 ? ParkOutcome.PARKED : handleExistingAnchor(request, envelope);
    if (outcome == ParkOutcome.UNSAFE) {
      return false;
    }
    counter(METRIC_PARKED, "outcome", outcome == ParkOutcome.PARKED ? "parked" : "already_terminal")
        .increment();
    log.warn(
        "trigger launch retries exhausted; recovery anchor accepted: tenantId={}, requestId={}, outcome={}",
        request.tenantId(),
        request.requestId(),
        outcome);
    return true;
  }

  private ParkOutcome handleExistingAnchor(LaunchRequest request, LaunchEnvelope incoming) {
    TriggerLaunchRecoveryEntity existing =
        mapper.selectByIdentityForUpdate(request.tenantId(), request.requestId());
    if (EmptyChecks.isNull(existing)) {
      return ParkOutcome.UNSAFE;
    }
    LaunchEnvelope stored;
    try {
      String plaintext = payloadProtector.reveal(existing.getEnvelopeCiphertext());
      stored = JsonUtils.fromJson(plaintext, LaunchEnvelope.class);
    } catch (RuntimeException invalid) {
      return ParkOutcome.UNSAFE;
    }
    if (!Objects.equals(incoming, stored)) {
      return ParkOutcome.UNSAFE;
    }
    String status = existing.getRecoveryStatus();
    if (STATUS_DEAD.equals(status)) {
      return mapper.requeueDead(request.tenantId(), request.requestId(), STATUS_PENDING) == 1
          ? ParkOutcome.PARKED
          : ParkOutcome.UNSAFE;
    }
    if (STATUS_PENDING.equals(status)) {
      return ParkOutcome.PARKED;
    }
    return STATUS_DONE.equals(status) || STATUS_REJECTED.equals(status)
        ? ParkOutcome.ALREADY_TERMINAL
        : ParkOutcome.UNSAFE;
  }

  private enum ParkOutcome {
    PARKED,
    ALREADY_TERMINAL,
    UNSAFE
  }

  /** 单轮扫描：只处理 PENDING 且到期的记录；单条失败不影响其它记录。 */
  public int recoverDue(int limit) {
    List<TriggerLaunchRecoveryEntity> due =
        mapper.selectDue(BatchDateTimeSupport.utcNow(), Math.max(1, limit));
    int recovered = 0;
    for (TriggerLaunchRecoveryEntity row : due) {
      if (recoverOne(row)) {
        recovered++;
      }
    }
    return recovered;
  }

  private boolean recoverOne(TriggerLaunchRecoveryEntity row) {
    String tenantId = row.getTenantId();
    String requestId = row.getRequestId();
    try {
      String envelopeJson = payloadProtector.reveal(row.getEnvelopeCiphertext());
      LaunchEnvelope envelope = JsonUtils.fromJson(envelopeJson, LaunchEnvelope.class);
      LaunchRequest request = envelope.launchRequest();
      // 与消费端一致：绑定租户 RLS 后再走可信队列启动，requestId 唯一键保证重复重投幂等。
      RlsTenantContextHolder.runWithTenant(
          tenantId, () -> launchApplicationService.launchFromTrustedQueue(request));
      mapper.markDone(tenantId, requestId, STATUS_DONE);
      counter(METRIC_RECOVERED, "outcome", "ok").increment();
      log.info("trigger launch recovery succeeded: tenantId={}, requestId={}", tenantId, requestId);
      return true;
    } catch (ResponseStatusException ex) {
      // 状态码不能证明请求已完成：启动后的事件预期核对等也可能返回 409。
      return retryOrGiveUp(row, "HTTP_" + ex.getStatusCode().value());
    } catch (BizException ex) {
      // 业务级拒绝（jobCode 不存在/跨租等）不可靠重投恢复，直接终态并保留错误码。
      int updated = mapper.markAttempt(TriggerLaunchRecoveryAttemptParam.builder()
          .tenantId(tenantId)
          .requestId(requestId)
          .recoveryGeneration(row.getRecoveryGeneration())
          .status(STATUS_REJECTED)
          .errorCode(String.valueOf(ex.getCode()))
          .build());
      if (updated == 1) {
        counter(METRIC_RECOVERED, "outcome", "rejected").increment();
        log.warn(
            "trigger launch recovery rejected by business rule: tenantId={}, requestId={}, code={}",
            tenantId,
            requestId,
            String.valueOf(ex.getCode()));
      }
      return false;
    } catch (RuntimeException ex) {
      log.warn(
          "trigger launch recovery attempt failed: tenantId={}, requestId={}, cause={}",
          tenantId,
          requestId,
          SwallowedExceptionLogger.summary(ex));
      return retryOrGiveUp(row, ex.getClass().getSimpleName());
    }
  }

  private boolean retryOrGiveUp(TriggerLaunchRecoveryEntity row, String errorCode) {
    String status = mapper.markRetryOrDead(TriggerLaunchRecoveryRetryParam.builder()
        .tenantId(row.getTenantId())
        .requestId(row.getRequestId())
        .recoveryGeneration(row.getRecoveryGeneration())
        .errorCode(errorCode)
        .now(BatchDateTimeSupport.utcNow())
        .maxAttempts(properties.getMaxAttempts())
        .baseBackoffSeconds(properties.getBaseBackoffSeconds())
        .maxBackoffSeconds(Math.max(1L, properties.getMaxBackoffSeconds()))
        .build());
    if (STATUS_DEAD.equals(status)) {
      counter(METRIC_RECOVERED, "outcome", "dead").increment();
      log.error(
          "trigger launch recovery exhausted: tenantId={}, requestId={}, lastError={}",
          row.getTenantId(),
          row.getRequestId(),
          errorCode);
    } else if (STATUS_PENDING.equals(status)) {
      counter(METRIC_RECOVERED, "outcome", "retry").increment();
    }
    return false;
  }

  private Counter counter(String name, String... tagPairs) {
    return Counter.builder(name).tags(Tags.of(tagPairs)).register(meterRegistry);
  }
}
