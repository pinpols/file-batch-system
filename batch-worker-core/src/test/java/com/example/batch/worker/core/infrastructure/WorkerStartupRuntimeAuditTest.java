package com.example.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.worker.core.config.WorkerConfiguration;
import com.example.batch.worker.core.config.WorkerExecutionTimeoutProperties;
import com.example.batch.worker.core.domain.WorkerRegistration;
import com.example.batch.worker.core.reportoutbox.WorkerReportOutboxProperties;
import com.example.batch.worker.core.reportoutbox.WorkerReportOutboxRepository;
import com.example.batch.worker.core.reportoutbox.WorkerReportOutboxStats;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

class WorkerStartupRuntimeAuditTest {

  @Test
  void auditCoreReportsRegisteredWorkerAndOutboxStats() {
    WorkerRuntimeState runtimeState = new WorkerRuntimeState();
    WorkerRegistration registration = new WorkerRegistration();
    registration.setWorkerId("worker-1");
    registration.setStatus("ONLINE");
    runtimeState.put(registration);
    WorkerExecutionTimeoutProperties execution = new WorkerExecutionTimeoutProperties();
    execution.setPoolSize(4);
    WorkerReportOutboxProperties outbox = new WorkerReportOutboxProperties();
    outbox.setEnabled(true);
    outbox.setPublishingStaleRecoverAfterMillis(120_000);
    WorkerReportOutboxRepository repository = mock(WorkerReportOutboxRepository.class);
    when(repository.stats(anyLong())).thenReturn(new WorkerReportOutboxStats(2, 1, 0, 0));
    WorkerStartupRuntimeAudit audit =
        new WorkerStartupRuntimeAudit(
            provider(workerConfiguration()),
            runtimeState,
            execution,
            outbox,
            provider(repository),
            provider(List.of()),
            new MockEnvironment().withProperty("batch.worker.max-concurrent-tasks", "4"));

    Map<String, Object> details = audit.auditCore();

    assertThat(details.get("healthy")).isEqualTo(true);
    assertThat(details.get("registeredWorkers")).isEqualTo(1);
    @SuppressWarnings("unchecked")
    Map<String, Object> reportOutbox = (Map<String, Object>) details.get("reportOutbox");
    assertThat(reportOutbox).containsEntry("newCount", 2L).containsEntry("publishingCount", 1L);
  }

  @Test
  void auditCoreWarnsWhenExecutionPoolIsSmallerThanConcurrency() {
    WorkerRuntimeState runtimeState = new WorkerRuntimeState();
    WorkerRegistration registration = new WorkerRegistration();
    registration.setWorkerId("worker-1");
    runtimeState.put(registration);
    WorkerExecutionTimeoutProperties execution = new WorkerExecutionTimeoutProperties();
    execution.setPoolSize(1);
    WorkerStartupRuntimeAudit audit =
        new WorkerStartupRuntimeAudit(
            provider(workerConfiguration()),
            runtimeState,
            execution,
            new WorkerReportOutboxProperties(),
            absentProvider(),
            provider(List.of()),
            new MockEnvironment().withProperty("batch.worker.max-concurrent-tasks", "2"));

    Map<String, Object> details = audit.auditCore();

    assertThat(details.get("healthy")).isEqualTo(false);
    @SuppressWarnings("unchecked")
    List<String> issues = (List<String>) details.get("issues");
    assertThat(issues).contains("execution poolSize < maxConcurrentTasks");
  }

  private WorkerConfiguration workerConfiguration() {
    return new WorkerConfiguration() {
      @Override
      public String workerCode() {
        return "worker-1";
      }

      @Override
      public String workerType() {
        return "IMPORT";
      }

      @Override
      public String tenantId() {
        return "t1";
      }

      @Override
      public Long heartbeatIntervalMillis() {
        return 15_000L;
      }

      @Override
      public String topic() {
        return "batch.import.tasks";
      }

      @Override
      public String consumerGroupId() {
        return "batch-worker-import";
      }

      @Override
      public List<String> capabilityTags() {
        return List.of("tag-a");
      }
    };
  }

  @SuppressWarnings("unchecked")
  private <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(value);
    when(provider.stream())
        .thenReturn(
            value == null ? java.util.stream.Stream.empty() : java.util.stream.Stream.of(value));
    return provider;
  }

  @SuppressWarnings("unchecked")
  private <T> ObjectProvider<T> absentProvider() {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    when(provider.stream()).thenReturn(java.util.stream.Stream.empty());
    return provider;
  }

  @SuppressWarnings("unchecked")
  private ObjectProvider<WorkerStartupAuditContributor> provider(List<?> values) {
    ObjectProvider<WorkerStartupAuditContributor> provider = mock(ObjectProvider.class);
    when(provider.orderedStream()).thenReturn((java.util.stream.Stream) values.stream());
    return provider;
  }
}
