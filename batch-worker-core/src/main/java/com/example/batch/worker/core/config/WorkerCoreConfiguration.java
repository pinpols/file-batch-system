package com.example.batch.worker.core.config;

import com.example.batch.worker.core.reportoutbox.WorkerReportOutboxConfiguration;
import com.example.batch.worker.core.reportoutbox.WorkerReportOutboxProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(WorkerReportOutboxConfiguration.class)
@EnableConfigurationProperties({
  OrchestratorWorkerClientProperties.class,
  OrchestratorTaskClientProperties.class,
  WorkerExecutionTimeoutProperties.class,
  WorkerReportOutboxProperties.class,
  WorkerLeaseProperties.class
})
public class WorkerCoreConfiguration {}
