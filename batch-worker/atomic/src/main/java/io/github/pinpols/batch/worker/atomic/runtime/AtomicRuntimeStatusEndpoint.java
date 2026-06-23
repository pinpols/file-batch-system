package io.github.pinpols.batch.worker.atomic.runtime;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

/**
 * Spring Boot Actuator 自定义只读端点 {@code /actuator/atomicruntime}:暴露 4 个 executor 的 effective 配置快照。
 *
 * <p>Round-3 #8(Round-2 §4 P0 #8):承接 #252-K1 的隐式 prod 默认 — Console 通过反向 HTTP 拉取本端点, 在
 * /ops/atomic-runtime 菜单展示运维仪表盘,堵住"灰度切错 profile 时白名单失效但无任何启动期可见信号"的盲区。
 *
 * <p>用 Actuator 而非 {@code @RestController}:复用 Actuator 现有的 management-port 隔离 + 鉴权链 (避免新增专用
 * SecurityFilter)。management.endpoints.web.exposure.include 默认就含本端点。
 */
@Component
@Endpoint(id = "atomicruntime")
@RequiredArgsConstructor
public class AtomicRuntimeStatusEndpoint {

  private final AtomicRuntimeStatusService runtimeStatusService;

  @ReadOperation
  public AtomicRuntimeStatus status() {
    return runtimeStatusService.snapshot();
  }
}
