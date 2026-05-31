package com.example.batch.sdk.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * Body for {@code POST /internal/workers/{workerCode}/heartbeat}.
 *
 * <p>字段集与 {@link RegisterRequest} 相同 —— 平台侧两个端点共用 {@code WorkerHeartbeatDto},SDK 侧也保持两个独立 record
 * 以利后续协议演进(心跳可能携带 directive 而注册不需要)。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HeartbeatRequest(
    String tenantId,
    String workerCode,
    String workerGroup,
    String status,
    String hostName,
    String hostIp,
    String processId,
    Instant heartbeatAt,
    List<String> capabilityTags,
    Integer currentLoad) {}
