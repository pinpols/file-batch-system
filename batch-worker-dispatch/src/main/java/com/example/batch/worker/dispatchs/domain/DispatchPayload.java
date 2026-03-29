package com.example.batch.worker.dispatchs.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

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
        java.util.Map<String, Object> metadata
) {
}
