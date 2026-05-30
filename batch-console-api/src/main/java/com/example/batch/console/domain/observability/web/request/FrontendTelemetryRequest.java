package com.example.batch.console.domain.observability.web.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * P2-2(2026-05-16):增加 userId / sessionId / ts / page / props 的大小约束, 避免登录用户用大 payload 灌爆
 * Loki/日志存储,或把敏感字段塞进 props。 Bean Validation 在 @Valid 注解的 controller 入口自动生效,超出即 400。
 */
public record FrontendTelemetryRequest(
    @NotBlank @Size(max = 50) String app,
    @Size(max = 64) String userId,
    @Size(max = 128) String sessionId,
    @NotEmpty @Size(max = 50) List<@Valid Event> events) {
  public record Event(
      @NotBlank @Size(max = 20) String type,
      @NotBlank @Size(max = 200) String name,
      @Size(max = 32) String ts,
      @Size(max = 256) String page,
      // props 全局大小限制:在 ConsoleTelemetryController 序列化后做总字节数检查,
      // 因为 Bean Validation 不便对深 Map 做 size cap。同时 controller 不再把 props 整体
      // 序列化进结构化日志,只记 type+name+page。
      Map<String, Object> props) {}
}
