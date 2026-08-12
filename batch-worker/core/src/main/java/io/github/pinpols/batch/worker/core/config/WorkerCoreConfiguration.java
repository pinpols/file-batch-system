package io.github.pinpols.batch.worker.core.config;

import io.github.pinpols.batch.common.config.OrchestratorClientProperties;
import io.github.pinpols.batch.worker.core.reportoutbox.WorkerReportOutboxConfiguration;
import io.github.pinpols.batch.worker.core.reportoutbox.WorkerReportOutboxProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import({WorkerReportOutboxConfiguration.class, WorkerCoreAsyncConfiguration.class})
@EnableConfigurationProperties({
  OrchestratorClientProperties.class,
  OrchestratorTaskClientProperties.class,
  WorkerExecutionTimeoutProperties.class,
  WorkerReportOutboxProperties.class,
  WorkerLeaseProperties.class,
  WorkerWatchdogSchedulerProperties.class,
  WorkerCheckpointProperties.class,
  WorkerBatchClaimProperties.class
})
public class WorkerCoreConfiguration {}
