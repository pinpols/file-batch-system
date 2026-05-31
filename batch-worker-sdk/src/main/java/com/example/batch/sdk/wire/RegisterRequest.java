package com.example.batch.sdk.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * Body for {@code POST /internal/workers/register}.
 *
 * <p>字段集对齐 {@code com.example.batch.common.dto.WorkerHeartbeatDto}(平台侧 record),SDK 不直接 import 它以
 * 避免反向耦合。契约测试 {@code SdkWireContractTest} 通过"SDK→JSON→平台 DTO"反序列化路径校验字段集不漂移。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterRequest(
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
