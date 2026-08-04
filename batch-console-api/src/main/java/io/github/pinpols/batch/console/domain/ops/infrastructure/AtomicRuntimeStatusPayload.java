package io.github.pinpols.batch.console.domain.ops.infrastructure;

import java.util.Map;

/** Atomic worker 运行时状态的内部 HTTP 载荷。顶层字段固定，executor 细节允许扩展。 */
public record AtomicRuntimeStatusPayload(
    String workerCode,
    String workerType,
    Map<String, Object> shell,
    Map<String, Object> sql,
    Map<String, Object> http,
    Map<String, Object> storedProc) {}
