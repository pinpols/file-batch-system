package com.example.batch.worker.core.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
  OrchestratorWorkerClientProperties.class,
  OrchestratorTaskClientProperties.class,
  WorkerExecutionTimeoutProperties.class
})
public class WorkerCoreConfiguration {}
