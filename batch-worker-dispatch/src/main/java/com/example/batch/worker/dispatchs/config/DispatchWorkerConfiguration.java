package com.example.batch.worker.dispatchs.config;

import com.example.batch.worker.core.config.WorkerConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 分发 Worker 配置，绑定 {@code batch.worker.dispatch} 前缀属性。 */
@ConfigurationProperties(prefix = "batch.worker.dispatch")
public record DispatchWorkerConfiguration(
    String workerCode,
    String workerType,
    String tenantId,
    Long heartbeatIntervalMillis,
    String topic,
    String consumerGroupId)
    implements WorkerConfiguration {}
