package io.github.pinpols.batch.console.domain.ops.web.response;

import java.util.Map;

/**
 * Console 暴露给 FE 的 atomic worker 运行时状态(Round-3 #8)。
 *
 * <p>四个 executor 的开关 + 安全门控字段(白名单大小 / dialect / enforceAllowlist 来源)。{@link #available}=false
 * 表示反向通道未配置或不可达,FE 应显示降级 banner 而非真实数据。
 */
public record ConsoleAtomicRuntimeStatusResponse(
    boolean available,
    String unavailableReason,
    String workerCode,
    String workerType,
    Map<String, Object> shell,
    Map<String, Object> sql,
    Map<String, Object> http,
    Map<String, Object> storedProc) {

  public static ConsoleAtomicRuntimeStatusResponse unavailable(String reason) {
    return new ConsoleAtomicRuntimeStatusResponse(
        false, reason, null, null, null, null, null, null);
  }
}
