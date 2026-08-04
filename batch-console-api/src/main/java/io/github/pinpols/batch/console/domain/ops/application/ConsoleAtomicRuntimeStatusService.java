package io.github.pinpols.batch.console.domain.ops.application;

import io.github.pinpols.batch.common.resilience.DownstreamFallback;
import io.github.pinpols.batch.console.domain.ops.infrastructure.AtomicRuntimeStatusPayload;
import io.github.pinpols.batch.console.domain.ops.infrastructure.AtomicWorkerInternalRestClient;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleAtomicRuntimeStatusResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Round-3 #8(Round-2 §4 P0 #8):Console 反向 HTTP 拉取 batch-worker-atomic Actuator 端点 {@code
 * /actuator/atomicruntime},组装成给 FE 的 {@link ConsoleAtomicRuntimeStatusResponse}。
 *
 * <p>反向通道未启用(properties.enabled=false)或 atomic worker 不可达时,降级返 {@code available=false},不抛 5xx ——
 * 仪表盘是辅助 UI, 主链路不依赖。日志走 {@link DownstreamFallback} 统一格式 + metrics(service=atomic-worker,
 * op=runtime-status)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsoleAtomicRuntimeStatusService {

  private static final String SVC = "atomic-worker";
  private static final String OP = "runtime-status";
  private static final String ENDPOINT_URI = "/actuator/atomicruntime";

  private final AtomicWorkerInternalRestClient atomicClient;
  private final DownstreamFallback downstreamFallback;

  public ConsoleAtomicRuntimeStatusResponse fetch() {
    if (!atomicClient.isEnabled()) {
      return ConsoleAtomicRuntimeStatusResponse.unavailable(
          "batch.console.atomic-worker.enabled=false");
    }
    return downstreamFallback.callOrFallback(
        SVC,
        OP,
        () -> {
          AtomicRuntimeStatusPayload payload = atomicClient
              .build()
              .get()
              .uri(ENDPOINT_URI)
              .retrieve()
              .body(new ParameterizedTypeReference<AtomicRuntimeStatusPayload>() {});
          return toResponse(payload);
        },
        ex -> ConsoleAtomicRuntimeStatusResponse.unavailable("atomic worker unreachable"));
  }

  static ConsoleAtomicRuntimeStatusResponse toResponse(AtomicRuntimeStatusPayload payload) {
    if (payload == null) {
      return ConsoleAtomicRuntimeStatusResponse.unavailable("empty response from atomic worker");
    }
    return new ConsoleAtomicRuntimeStatusResponse(
        true,
        null,
        payload.workerCode(),
        payload.workerType(),
        payload.shell(),
        payload.sql(),
        payload.http(),
        payload.storedProc());
  }
}
