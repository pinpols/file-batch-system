package io.github.pinpols.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.worker.core.config.WorkerConcurrencyProperties;
import io.github.pinpols.batch.worker.core.config.WorkerConfiguration;
import io.github.pinpols.batch.worker.core.config.WorkerExecutionTimeoutProperties;
import io.github.pinpols.batch.worker.core.domain.WorkerRegistration;
import io.github.pinpols.batch.worker.core.reportoutbox.WorkerReportOutboxProperties;
import io.github.pinpols.batch.worker.core.reportoutbox.WorkerReportOutboxRepository;
import io.github.pinpols.batch.worker.core.reportoutbox.WorkerReportOutboxStats;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Worker 启动运行时审计: 健康判定与启动日志内容边界")
class WorkerStartupRuntimeAuditTest {

  @Test
  @DisplayName("核心审计汇总已注册 Worker 数量与发件箱待投递计数")
  void shouldReportRegisteredWorkerAndOutboxStats_whenAuditingCore() {
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
    WorkerStartupRuntimeAudit audit = new WorkerStartupRuntimeAudit(
        provider(workerConfiguration()),
        runtimeState,
        execution,
        outbox,
        provider(repository),
        provider(List.of()),
        concurrencyProperties(4));

    Map<String, Object> details = audit.auditCore();

    assertThat(details).containsEntry("healthy", true);
    assertThat(details).containsEntry("registeredWorkers", 1);
    @SuppressWarnings("unchecked")
    Map<String, Object> reportOutbox = (Map<String, Object>) details.get("reportOutbox");
    assertThat(reportOutbox).containsEntry("newCount", 2L).containsEntry("publishingCount", 1L);
  }

  @Test
  @DisplayName("执行池容量小于任务并发上限时判定为不健康并给出问题项")
  void shouldMarkUnhealthy_whenExecutionPoolSmallerThanConcurrency() {
    WorkerRuntimeState runtimeState = new WorkerRuntimeState();
    WorkerRegistration registration = new WorkerRegistration();
    registration.setWorkerId("worker-1");
    runtimeState.put(registration);
    WorkerExecutionTimeoutProperties execution = new WorkerExecutionTimeoutProperties();
    execution.setPoolSize(1);
    WorkerStartupRuntimeAudit audit = new WorkerStartupRuntimeAudit(
        provider(workerConfiguration()),
        runtimeState,
        execution,
        new WorkerReportOutboxProperties(),
        absentProvider(),
        provider(List.of()),
        concurrencyProperties(2));

    Map<String, Object> details = audit.auditCore();

    assertThat(details).containsEntry("healthy", false);
    @SuppressWarnings("unchecked")
    List<String> issues = (List<String>) details.get("issues");
    assertThat(issues).contains("execution poolSize < maxConcurrentTasks");
  }

  @Test
  @DisplayName("启动健康时只输出汇总与贡献者名称, 不展开配置明细")
  void shouldLogSummaryWithoutContributorDetails_whenStartupHealthy(CapturedOutput output) {
    WorkerRuntimeState runtimeState = new WorkerRuntimeState();
    WorkerRegistration registration = new WorkerRegistration();
    registration.setWorkerId("worker-1");
    registration.setStatus("ONLINE");
    runtimeState.put(registration);
    WorkerExecutionTimeoutProperties execution = new WorkerExecutionTimeoutProperties();
    execution.setPoolSize(4);
    WorkerStartupAuditContributor contributor = mock(WorkerStartupAuditContributor.class);
    when(contributor.name()).thenReturn("dispatch-channel-health");
    when(contributor.audit())
        .thenReturn(WorkerStartupAuditContributor.WorkerStartupAuditResult.healthy(
            Map.of("channelSafetyProfiles", Map.of("API", "full profile details"))));
    WorkerStartupRuntimeAudit audit = new WorkerStartupRuntimeAudit(
        provider(workerConfiguration()),
        runtimeState,
        execution,
        new WorkerReportOutboxProperties(),
        absentProvider(),
        provider(List.of(contributor)),
        concurrencyProperties(4));

    audit.auditOnReady();

    assertThat(output.getOut())
        .contains("worker startup runtime audit OK: configurations=1, registeredWorkers=1")
        .contains("contributors=[dispatch-channel-health]")
        .doesNotContain("channelSafetyProfiles", "full profile details");
  }

  @Test
  @DisplayName("启动不健康时输出问题列表, 不打印完整核心快照")
  void shouldLogIssuesWithoutFullCoreSnapshot_whenStartupUnhealthy(CapturedOutput output) {
    WorkerExecutionTimeoutProperties execution = new WorkerExecutionTimeoutProperties();
    execution.setPoolSize(4);
    WorkerStartupRuntimeAudit audit = new WorkerStartupRuntimeAudit(
        provider(workerConfiguration()),
        new WorkerRuntimeState(),
        execution,
        new WorkerReportOutboxProperties(),
        absentProvider(),
        provider(List.of()),
        concurrencyProperties(4));

    audit.auditOnReady();

    assertThat(output.getOut())
        .contains("worker startup runtime audit WARN: unhealthy=[worker-core]")
        .contains("issues=[no registered worker in runtime state]")
        .doesNotContain("core={", "registeredWorkerIds=");
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
    when(provider.orderedStream())
        .thenReturn((java.util.stream.Stream<WorkerStartupAuditContributor>) values.stream());
    return provider;
  }

  private static WorkerConcurrencyProperties concurrencyProperties(int maxConcurrentTasks) {
    WorkerConcurrencyProperties properties = new WorkerConcurrencyProperties();
    properties.setMaxConcurrentTasks(maxConcurrentTasks);
    return properties;
  }
}
