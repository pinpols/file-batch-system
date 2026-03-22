package com.example.batch.worker.dispatchs.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.worker.dispatch.receipt-poll")
public class DispatchReceiptPollProperties {

    private boolean enabled = true;
    private long intervalMillis = 60_000L;
    private int batchSize = 50;
}
