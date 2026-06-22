package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.example.batch.worker.dispatchs.domain.DispatchPayload;
import java.util.Map;

/** 分发命令，封装一次分发所需的租户信息、文件记录、渠道配置及载荷。 */
public record DispatchCommand(
    String tenantId,
    String traceId,
    Map<String, Object> fileRecord,
    Map<String, Object> channelConfig,
    DispatchPayload payload) {}
