package io.github.pinpols.batch.sdk.dispatcher;

import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig;
import io.github.pinpols.batch.sdk.internal.PlatformHttpClient;
import io.github.pinpols.batch.sdk.internal.PlatformHttpException;
import io.github.pinpols.batch.sdk.internal.ThrottledLogger;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * SDK 控制面调用的重试与故障分类协作者。
 *
 * <p>CLAIM 和 REPORT 都面对同一类平台瞬时故障，但它们的结果不能混为一谈：CLAIM 的 409 表示任务已被其他 worker
 * 占有，REPORT 的 409 则交回调用方按已处理语义记录。这里集中维护 5xx/传输错误的有界退避、鉴权失败熔断和连续
 * 4xx 契约错误计数，避免 {@link TaskDispatcher} 的任务执行流程夹杂 HTTP 治理细节。
 */
@Slf4j
final class TaskDispatcherRetryCoordinator {

  private final BatchPlatformClientConfig config;
  private final PlatformHttpClient httpClient;
  private final ThrottledLogger throttledLog;
  private final AtomicBoolean fatal = new AtomicBoolean(false);
  private final AtomicInteger consecutiveClientErrors = new AtomicInteger(0);

  TaskDispatcherRetryCoordinator(
      BatchPlatformClientConfig config,
      PlatformHttpClient httpClient,
      ThrottledLogger throttledLog) {
    this.config = config;
    this.httpClient = httpClient;
    this.throttledLog = throttledLog;
  }

  boolean isFatal() {
    return fatal.get();
  }

  int consecutiveClientErrors() {
    return consecutiveClientErrors.get();
  }

  void resetClientErrorStreak() {
    consecutiveClientErrors.set(0);
  }

  /** CLAIM 遇到平台瞬时错误时有限重试；鉴权错误和已被竞争者占有的任务直接结束。 */
  ClaimResult claimWithRetry(
      TaskDispatchMessage message, String idempotencyKey, Map<String, Object> body) {
    int maxRetries = Math.max(0, config.getClaimMax5xxRetries());
    long baseDelayMs = Math.max(0L, config.getClaimRetryBaseDelay().toMillis());
    int attempt = 0;
    while (true) {
      try {
        PlatformHttpClient.TaskClaimResponse response =
            httpClient.claim(message.taskId(), idempotencyKey, body);
        resetClientErrorStreak();
        return new ClaimResult(
            true, response == null ? new PlatformHttpClient.TaskClaimResponse(null) : response);
      } catch (PlatformHttpException httpEx) {
        if (httpEx.isAuthError()) {
          markFatal(
              "CLAIM auth failed (HTTP {}) for taskId={}, marking dispatcher FATAL — "
                  + "check apiKey / tenant ACL; SDK will reject subsequent dispatches",
              httpEx.statusCode(),
              message.taskId());
          return ClaimResult.notClaimed();
        }
        if (httpEx.isConflict()) {
          log.info("CLAIM 409 for taskId={} (taken by peer), skipping", message.taskId());
          return ClaimResult.notClaimed();
        }
        if (httpEx.isServerError()) {
          if (attempt >= maxRetries) {
            throttledLog.warn(
                "claim_5xx_exhausted",
                "CLAIM 5xx (HTTP {}) for taskId={} exhausted {} retries, giving up "
                    + "(orchestrator will redispatch on lease timeout)",
                httpEx.statusCode(),
                message.taskId(),
                maxRetries);
            return ClaimResult.notClaimed();
          }
          long delayMs = backoffWithJitter(baseDelayMs, attempt);
          log.info(
              "CLAIM 5xx (HTTP {}) for taskId={} attempt={} retry in {}ms",
              httpEx.statusCode(),
              message.taskId(),
              attempt + 1,
              delayMs);
          if (!sleepInterruptible(delayMs)) {
            return ClaimResult.notClaimed();
          }
          attempt++;
          continue;
        }
        throttledLog.warn(
            "claim_client_error_" + httpEx.statusCode(),
            "CLAIM client error (HTTP {}) for taskId={}, giving up: {}",
            httpEx.statusCode(),
            message.taskId(),
            httpEx.getMessage());
        recordClientError(httpEx.statusCode(), message.taskId(), "CLAIM");
        return ClaimResult.notClaimed();
      } catch (IOException ioEx) {
        if (attempt >= maxRetries) {
          throttledLog.warn(
              "claim_transport_exhausted",
              "CLAIM transport error for taskId={} exhausted {} retries, giving up: {}",
              message.taskId(),
              maxRetries,
              ioEx.getMessage());
          return ClaimResult.notClaimed();
        }
        long delayMs = backoffWithJitter(baseDelayMs, attempt);
        log.info(
            "CLAIM transport error for taskId={} attempt={} retry in {}ms: {}",
            message.taskId(),
            attempt + 1,
            delayMs,
            ioEx.getMessage());
        if (!sleepInterruptible(delayMs)) {
          return ClaimResult.notClaimed();
        }
        attempt++;
      }
    }
  }

  /** REPORT 仅对 5xx 与传输错误重试；失败交回任务协调层，由 lease 超时触发平台重派。 */
  void reportWithRetry(Long taskId, String idempotencyKey, Map<String, Object> body)
      throws IOException {
    int maxRetries = Math.max(0, config.getClaimMax5xxRetries());
    long baseDelayMs = Math.max(0L, config.getClaimRetryBaseDelay().toMillis());
    int attempt = 0;
    while (true) {
      try {
        httpClient.report(taskId, idempotencyKey, body);
        return;
      } catch (PlatformHttpException httpEx) {
        if (httpEx.isAuthError()) {
          markFatal(
              "REPORT auth failed (HTTP {}) for taskId={}, marking dispatcher FATAL — "
                  + "check apiKey / tenant ACL; SDK will reject subsequent dispatches",
              httpEx.statusCode(),
              taskId);
          throw httpEx;
        }
        if (!httpEx.isServerError()) {
          throw httpEx;
        }
        if (attempt >= maxRetries) {
          throttledLog.warn(
              "report_5xx_exhausted",
              "REPORT 5xx (HTTP {}) for taskId={} exhausted {} retries, giving up "
                  + "(orchestrator will reclaim on lease timeout)",
              httpEx.statusCode(),
              taskId,
              maxRetries);
          throw httpEx;
        }
        long delayMs = backoffWithJitter(baseDelayMs, attempt);
        log.info(
            "REPORT 5xx (HTTP {}) for taskId={} attempt={} retry in {}ms",
            httpEx.statusCode(),
            taskId,
            attempt + 1,
            delayMs);
        if (!sleepInterruptible(delayMs)) {
          throw new IOException("report retry interrupted for taskId=" + taskId);
        }
        attempt++;
      } catch (IOException ioEx) {
        if (attempt >= maxRetries) {
          throttledLog.warn(
              "report_transport_exhausted",
              "REPORT transport error for taskId={} exhausted {} retries, giving up: {}",
              taskId,
              maxRetries,
              ioEx.getMessage());
          throw ioEx;
        }
        long delayMs = backoffWithJitter(baseDelayMs, attempt);
        log.info(
            "REPORT transport error for taskId={} attempt={} retry in {}ms: {}",
            taskId,
            attempt + 1,
            delayMs,
            ioEx.getMessage());
        if (!sleepInterruptible(delayMs)) {
          throw new IOException("report retry interrupted for taskId=" + taskId, ioEx);
        }
        attempt++;
      }
    }
  }

  void recordClientError(int statusCode, long taskId, String operation) {
    int threshold = config.getClientErrorFailFastThreshold();
    int count = consecutiveClientErrors.incrementAndGet();
    if (threshold > 0 && count >= threshold && fatal.compareAndSet(false, true)) {
      log.error(
          "{} client error (HTTP {}) for taskId={} reached {} consecutive 4xx — marking dispatcher"
              + " FATAL; likely SDK/contract mismatch, SDK will reject subsequent dispatches and"
              + " report unhealthy for K8s restart",
          operation,
          statusCode,
          taskId,
          count);
    }
  }

  /** 指数退避并加入 0～10% jitter，避免多个 worker 在同一时间再次撞击控制面。 */
  static long backoffWithJitter(long baseDelayMs, int attempt) {
    if (baseDelayMs <= 0L) {
      return 0L;
    }
    long safeAttempt = Math.min(attempt, 30);
    long exponentialMs = baseDelayMs << safeAttempt;
    long jitterCeilExclusive = Math.max(1L, exponentialMs / 10L);
    long jitterMs = ThreadLocalRandom.current().nextLong(0L, jitterCeilExclusive);
    return exponentialMs + jitterMs;
  }

  private void markFatal(String message, Object... args) {
    fatal.set(true);
    log.error(message, args);
  }

  private static boolean sleepInterruptible(long millis) {
    if (millis <= 0L) {
      return true;
    }
    try {
      Thread.sleep(millis);
      return true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  record ClaimResult(boolean claimed, PlatformHttpClient.TaskClaimResponse response) {
    static ClaimResult notClaimed() {
      return new ClaimResult(false, new PlatformHttpClient.TaskClaimResponse(null));
    }
  }
}
