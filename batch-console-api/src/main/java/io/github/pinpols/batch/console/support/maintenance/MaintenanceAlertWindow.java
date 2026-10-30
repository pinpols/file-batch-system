package io.github.pinpols.batch.console.support.maintenance;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.support.maintenance.MaintenanceStateHolder.MaintenanceState;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** 维护通知窗口：只识别固定应用编码，未知公告标记不扩大抑制范围。 */
public record MaintenanceAlertWindow(Instant startsAt, Instant endsAt, Set<String> jobs) {

  public static final List<String> APPLICATION_JOBS = List.of(
      "batch-console-api",
      "batch-trigger",
      "batch-orchestrator",
      "batch-worker-import",
      "batch-worker-export",
      "batch-worker-process",
      "batch-worker-dispatch",
      "batch-worker-atomic");
  private static final long MAX_WINDOW_MINUTES = 1440;

  public MaintenanceAlertWindow {
    jobs = Set.copyOf(jobs);
  }

  /** 缺少 ETA 时使用有限默认窗口；轮询、重启和重试均不从当前时间延长窗口。 */
  public static MaintenanceAlertWindow from(MaintenanceState state, int defaultMinutes) {
    Instant start = EmptyChecks.isNull(state.startsAt()) ? state.updatedAt() : state.startsAt();
    if (EmptyChecks.isNull(start)) {
      return new MaintenanceAlertWindow(Instant.EPOCH, Instant.EPOCH, Set.of());
    }
    long boundedMinutes = Math.max(1L, Math.min(MAX_WINDOW_MINUTES, defaultMinutes));
    Instant maximumEnd = start.plus(Duration.ofMinutes(MAX_WINDOW_MINUTES));
    Instant end = state.etaAt();
    if (EmptyChecks.isNull(end)) {
      Duration defaultWindow = Duration.ofMinutes(boundedMinutes);
      end = start.plus(defaultWindow);
    }
    if (end.isAfter(maximumEnd)) {
      end = maximumEnd;
    }
    Set<String> jobs = new TreeSet<>();
    List<String> services = state.affectedServices();
    if (EmptyChecks.isEmpty(services)) {
      jobs.add("batch-console-api");
    } else {
      for (String service : services) {
        if (EmptyChecks.isNull(service)) {
          continue;
        }
        String job = service.startsWith("batch-") ? service : "batch-" + service;
        if (APPLICATION_JOBS.contains(job)) {
          jobs.add(job);
        }
      }
    }
    return new MaintenanceAlertWindow(start, end, jobs);
  }

  public boolean activeAt(Instant now) {
    return !now.isBefore(startsAt) && now.isBefore(endsAt);
  }
}
