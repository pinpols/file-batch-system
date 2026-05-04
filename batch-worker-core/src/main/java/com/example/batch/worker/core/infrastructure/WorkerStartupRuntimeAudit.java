package com.example.batch.worker.core.infrastructure;

import com.example.batch.common.utils.Texts;
import com.example.batch.worker.core.config.WorkerConfiguration;
import com.example.batch.worker.core.config.WorkerExecutionTimeoutProperties;
import com.example.batch.worker.core.domain.WorkerRegistration;
import com.example.batch.worker.core.reportoutbox.WorkerReportOutboxProperties;
import com.example.batch.worker.core.reportoutbox.WorkerReportOutboxRepository;
import com.example.batch.worker.core.reportoutbox.WorkerReportOutboxStats;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Worker 进程启动后的统一业务运行态审计。只读打印，不执行修复。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerStartupRuntimeAudit {

  private final ObjectProvider<WorkerConfiguration> workerConfigurationProvider;
  private final WorkerRuntimeState workerRuntimeState;
  private final WorkerExecutionTimeoutProperties executionProperties;
  private final WorkerReportOutboxProperties reportOutboxProperties;
  private final ObjectProvider<WorkerReportOutboxRepository> reportOutboxRepositoryProvider;
  private final ObjectProvider<WorkerStartupAuditContributor> contributorProvider;
  private final Environment environment;

  @Order(Ordered.LOWEST_PRECEDENCE)
  @EventListener(ApplicationReadyEvent.class)
  public void auditOnReady() {
    Map<String, Object> core = auditCore();
    List<String> unhealthy = new ArrayList<>();
    if (Boolean.FALSE.equals(core.get("healthy"))) {
      unhealthy.add("worker-core");
    }
    Map<String, Object> contributors = auditContributors(unhealthy);
    boolean healthy = unhealthy.isEmpty();
    if (healthy) {
      log.info("worker startup runtime audit OK: core={}, contributors={}", core, contributors);
    } else {
      log.warn(
          "worker startup runtime audit WARN: unhealthy={}, core={}, contributors={}",
          unhealthy,
          core,
          contributors);
    }
  }

  Map<String, Object> auditCore() {
    Map<String, Object> details = new LinkedHashMap<>();
    List<String> issues = new ArrayList<>();
    List<WorkerConfiguration> configurations = workerConfigurationProvider.stream().toList();
    put(details, "configurationCount", configurations.size());
    put(details, "configurations", auditConfigurations(configurations, issues));
    if (configurations.isEmpty()) {
      issues.add("workerConfiguration missing");
    }
    Collection<WorkerRegistration> registrations = workerRuntimeState.snapshot();
    put(details, "registeredWorkers", registrations.size());
    put(
        details,
        "registeredWorkerIds",
        registrations.stream().map(WorkerRegistration::getWorkerId).toList());
    if (registrations.isEmpty()) {
      issues.add("no registered worker in runtime state");
    }
    long decommissioned =
        registrations.stream()
            .filter(r -> "DECOMMISSIONED".equalsIgnoreCase(nullToEmpty(r.getStatus())))
            .count();
    put(details, "decommissionedRegistrations", decommissioned);
    if (decommissioned > 0) {
      issues.add("registered worker status is DECOMMISSIONED");
    }
    int maxConcurrentTasks =
        environment.getProperty("batch.worker.max-concurrent-tasks", Integer.class, 8);
    put(details, "maxConcurrentTasks", maxConcurrentTasks);
    put(details, "executionPoolSize", executionProperties.getPoolSize());
    if (maxConcurrentTasks <= 0) {
      issues.add("maxConcurrentTasks<=0");
    }
    if (executionProperties.getPoolSize() < maxConcurrentTasks) {
      issues.add("execution poolSize < maxConcurrentTasks");
    }
    put(details, "reportOutbox", auditReportOutbox(issues));
    put(details, "issues", issues);
    put(details, "healthy", issues.isEmpty());
    return details;
  }

  private List<Map<String, Object>> auditConfigurations(
      List<WorkerConfiguration> configurations, List<String> issues) {
    List<Map<String, Object>> results = new ArrayList<>();
    for (WorkerConfiguration cfg : configurations) {
      Map<String, Object> item = new LinkedHashMap<>();
      put(item, "tenantId", cfg.tenantId());
      put(item, "workerType", cfg.workerType());
      put(item, "topic", cfg.topic());
      put(item, "consumerGroupId", cfg.consumerGroupId());
      put(item, "heartbeatIntervalMillis", cfg.heartbeatIntervalMillis());
      put(item, "capabilityTags", cfg.capabilityTags());
      requireText(issues, cfg.workerType(), "tenantId", cfg.tenantId());
      requireText(issues, cfg.workerType(), "workerType", cfg.workerType());
      requireText(issues, cfg.workerType(), "topic", cfg.topic());
      requireText(issues, cfg.workerType(), "consumerGroupId", cfg.consumerGroupId());
      if (cfg.heartbeatIntervalMillis() != null && cfg.heartbeatIntervalMillis() <= 0) {
        issues.add(issuePrefix(cfg.workerType()) + "heartbeatIntervalMillis<=0");
      }
      if (cfg.capabilityTags().stream().anyMatch(tag -> !Texts.hasText(tag))) {
        issues.add(issuePrefix(cfg.workerType()) + "capabilityTags contains blank tag");
      }
      results.add(item);
    }
    return results;
  }

  private Map<String, Object> auditReportOutbox(List<String> issues) {
    Map<String, Object> details = new LinkedHashMap<>();
    put(details, "enabled", reportOutboxProperties.isEnabled());
    put(details, "storage", reportOutboxProperties.getStorage());
    put(details, "maxPublishAttempts", reportOutboxProperties.getMaxPublishAttempts());
    put(
        details,
        "publishingStaleRecoverAfterMillis",
        reportOutboxProperties.getPublishingStaleRecoverAfterMillis());
    if (!reportOutboxProperties.isEnabled()) {
      return details;
    }
    WorkerReportOutboxRepository repository = reportOutboxRepositoryProvider.getIfAvailable();
    put(details, "repositoryPresent", repository != null);
    if (repository == null) {
      issues.add("report outbox enabled but repository missing");
      return details;
    }
    try {
      long cutoff =
          System.currentTimeMillis()
              - reportOutboxProperties.getPublishingStaleRecoverAfterMillis();
      WorkerReportOutboxStats stats = repository.stats(cutoff);
      put(details, "newCount", stats.newCount());
      put(details, "publishingCount", stats.publishingCount());
      put(details, "giveUpCount", stats.giveUpCount());
      put(details, "stalePublishingCount", stats.stalePublishingCount());
      if (stats.stalePublishingCount() > 0) {
        issues.add("report outbox has stale PUBLISHING rows");
      }
      if (stats.giveUpCount() > 0) {
        issues.add("report outbox has GIVE_UP rows");
      }
    } catch (RuntimeException ex) {
      put(details, "statsError", ex.getMessage());
      issues.add("report outbox stats query failed");
    }
    return details;
  }

  private Map<String, Object> auditContributors(List<String> unhealthy) {
    Map<String, Object> results = new LinkedHashMap<>();
    contributorProvider
        .orderedStream()
        .forEach(
            contributor -> {
              String name = contributor.name();
              try {
                WorkerStartupAuditContributor.WorkerStartupAuditResult result = contributor.audit();
                put(
                    results,
                    name,
                    auditContributorDetails(result.healthy(), result.details(), null));
                if (!result.healthy()) {
                  unhealthy.add(name);
                }
              } catch (RuntimeException ex) {
                put(results, name, auditContributorDetails(false, Map.of(), ex.getMessage()));
                unhealthy.add(name);
              }
            });
    return results;
  }

  private void requireText(List<String> issues, String workerType, String field, String value) {
    if (!Texts.hasText(value)) {
      issues.add(issuePrefix(workerType) + field + " blank");
    }
  }

  private String issuePrefix(String workerType) {
    return Texts.hasText(workerType) ? workerType + ": " : "";
  }

  private void put(Map<String, Object> map, String key, Object value) {
    map.put(key, value == null ? "" : value);
  }

  private String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private Map<String, Object> auditContributorDetails(
      boolean healthy, Map<String, Object> details, String error) {
    Map<String, Object> result = new LinkedHashMap<>();
    put(result, "healthy", healthy);
    put(result, "details", details == null ? Map.of() : details);
    if (error != null) {
      put(result, "error", error);
    }
    return result;
  }
}
