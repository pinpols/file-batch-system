package io.github.pinpols.batch.worker.core.config;

import io.github.pinpols.batch.worker.core.reportoutbox.WorkerReportOutboxConfiguration;
import io.github.pinpols.batch.worker.core.reportoutbox.WorkerReportOutboxProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({WorkerReportOutboxConfiguration.class, WorkerCoreAsyncConfiguration.class})
@EnableConfigurationProperties({
  OrchestratorWorkerClientProperties.class,
  OrchestratorTaskClientProperties.class,
  WorkerExecutionTimeoutProperties.class,
  WorkerReportOutboxProperties.class,
  WorkerLeaseProperties.class,
  WorkerWatchdogSchedulerProperties.class,
  WorkerCheckpointProperties.class
})
public class WorkerCoreConfiguration {}
