package com.example.batch.console.web.response.ops;

import java.time.Instant;

public record ConsoleWorkerRegistryResponse(
    Long id,
    String tenantId,
    /** 存储中稳定的 Worker 注册编码。 */
    String workerCode,
    /** 调度与消费的分组键。 */
    String workerGroup,
    Object capabilityTags,
    String resourceTag,
    String status,
    Instant heartbeatAt,
    Integer currentLoad,
    Instant drainStartedAt,
    Instant drainDeadlineAt) {}
