package com.example.batch.worker.dispatchs.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * 分发任务载荷，携带文件 ID、渠道编码、接收方及回执策略等信息。
 */
public record DispatchPayload(
        String fileId,
        String fileCode,
        String channelCode,
        String dispatchTarget,
        String externalRequestId,
        String receiptCode,
        Boolean ackRequired,
        Boolean forceRetry,
        @JsonProperty("run_mode")
        @JsonAlias("runMode")
        String runMode,
        Map<String, Object> metadata
) {
}
