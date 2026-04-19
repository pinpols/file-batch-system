package com.example.batch.orchestrator.infrastructure.startup;

import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Orchestrator 启动后租约审计：读取 worker_registry / job_partition / outbox_event 里
 * <b>已经过期但仍未被回收</b> 的记录并以 WARN 日志告知运维。
 *
 * <p>审计范围：
 *
 * <ul>
 *   <li>{@code worker_registry} 处于 DRAINING 且 {@code drain_deadline_at < now()} —— 正常情况由
 *       {@code WorkerDrainTimeoutScheduler} 接管，非 0 说明上次调度挂了/未来得及跑；
 *   <li>{@code job_partition} 处于分配态且 {@code lease_expire_at < now()} —— 正常情况由
 *       {@code PartitionLeaseReclaimScheduler} 回收，非 0 说明租约没及时释放；
 *   <li>{@code outbox_event} 卡在 PUBLISHING 且 updated_at 超 10 分钟 —— 正常情况由 OutboxPoll
 *       {@code resetStalePublishing} 每轮清 0，非 0 说明轮询本身挂了。
 * </ul>
 *
 * <p>本组件 <b>只告警不修复</b>：修复交给各自的定时调度器（启动后几十秒内会自动跑第一轮）。
 * 价值在于"新启动的 Pod 立即给出一个健康快照"，便于排查"刚拉起就已有残留"这类场景。
 */
@Slf4j
@Component
public class OrchestratorStartupLeaseAudit {

  private final DataSource dataSource;

  public OrchestratorStartupLeaseAudit(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void audit() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    try {
      long drainingStale =
          firstNonNull(
              jdbc.queryForObject(
                  "select count(*) from batch.worker_registry "
                      + "where status = 'DRAINING' and drain_deadline_at is not null "
                      + "and drain_deadline_at < current_timestamp",
                  Long.class));
      long leasesExpired =
          firstNonNull(
              jdbc.queryForObject(
                  "select count(*) from batch.job_partition "
                      + "where lease_expire_at is not null "
                      + "and lease_expire_at < current_timestamp",
                  Long.class));
      long outboxStuck =
          firstNonNull(
              jdbc.queryForObject(
                  "select count(*) from batch.outbox_event "
                      + "where publish_status = 'PUBLISHING' "
                      + "and updated_at < current_timestamp - interval '10 minutes'",
                  Long.class));

      if (drainingStale == 0 && leasesExpired == 0 && outboxStuck == 0) {
        log.info("启动租约审计通过（orchestrator）：无残留 drain / 过期租约 / 卡死 PUBLISHING");
        return;
      }

      log.warn(
          "启动租约审计发现残留（orchestrator）：drainingStale={}, leasesExpired={}, outboxStuck={}"
              + "—— 本次审计仅告警，修复交给 WorkerDrainTimeoutScheduler / PartitionLeaseReclaimScheduler"
              + " / OutboxPollScheduler 的第一轮执行自动完成；如非预期请排查对应调度器状态。",
          drainingStale,
          leasesExpired,
          outboxStuck);
    } catch (RuntimeException ex) {
      // 审计失败不阻塞应用启动；可能是首次启动时部分表未建（Flyway 还没跑完）
      log.warn("启动租约审计执行失败（不影响启动）：{}", ex.getMessage());
    }
  }

  private static long firstNonNull(Long v) {
    return v == null ? 0L : v;
  }
}
