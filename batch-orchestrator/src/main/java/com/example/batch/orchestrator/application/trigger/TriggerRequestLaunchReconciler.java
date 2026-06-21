package com.example.batch.orchestrator.application.trigger;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.domain.entity.TriggerRequestLaunchReconcileRow;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ADR-010 Stage 6: trigger_request 状态机闭环回退。
 *
 * <p><b>背景</b>:async launch 路径下,trigger 端 {@code persistAndForward} 把 trigger_request 推到 {@code
 * ACCEPTED} 即返回; orchestrator 端 {@link TriggerLaunchConsumer} 消费成功 launch 后会 best-effort 回写 {@code
 * LAUNCHED + relatedJobInstanceId}, 但回写本身可能因 DB 瞬时异常 / 事务隔离失败 — 主路径已 ack 不会 retry,trigger_request
 * 会留在 ACCEPTED, 审计 / SLA 报表无法判定"job 是否真跑了"。
 *
 * <p><b>本 reconciler</b>:周期 60s 扫一批"卡 ACCEPTED + 已有 job_instance 写入数据库 + 未回写 jobInstanceId" 的
 * trigger_request,按 (tenant_id, dedup_key) JOIN 到对应 job_instance.id, 走 CAS update 把状态推到
 * LAUNCHED。仅做"已发生事实的事后回写" — 不会引发新 launch、 不会改业务语义,失败也只是下一轮再试,绝对幂等。
 *
 * <p><b>静默期</b> {@code min-age-seconds}(默认 300s):刚 ack 的请求让 consumer writeBack 自己处理, reconciler 不参与
 * 抢同一行;过 5 min 未闭环再回退,避免 reconciler 与 consumer 在 ms 级窗口内并发写。
 *
 * <p><b>不解决的问题</b>(交给 ops backlog):trigger_request 卡 ACCEPTED 但 job_instance 始终没出现 (consumer 异常 /
 * launch 失败) — 需要单独的"过期 ACCEPTED"告警 + 人工排查,本 reconciler 不会自动 GIVE_UP。
 *
 * <p><b>HIGH-1 audit fix(2026-05)</b>:launch 的 T1(建 instance=CREATED)与 T2(派发 + 推进状态)是两段独立事务,T2 崩溃会让
 * instance 滞留 {@code CREATED} 且零 partition。本 reconciler 的扫描 SQL 已加 {@code ji.instance_status <>
 * 'CREATED'} 守护:此类<strong>从未真正派发</strong>的滞留实例不再被回写成 {@code LAUNCHED}(那会谎报成功、掩盖静默丢作业), 而是保留为陈旧
 * {@code ACCEPTED}、归入上述"过期 ACCEPTED"告警 + 恢复路径。自动重驱 T2 的恢复调度器另行设计(dispatch 非幂等,需集成测验证),不在本
 * reconciler 职责内。
 *
 * <p>ADR-010 固化路径，无条件启用（2026-05-02 同步 HTTP 路径已删除）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TriggerRequestLaunchReconciler {

  private static final String METRIC_RECONCILED = "batch.trigger.launch.reconciled.total";
  private static final String METRIC_SKIPPED_CAS = "batch.trigger.launch.reconciled.skipped.total";

  private final TriggerRequestMapper triggerRequestMapper;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final MeterRegistry meterRegistry;
  private final TriggerLaunchReconcilerProperties properties;

  @Scheduled(fixedDelayString = "${batch.trigger.launch.reconcile.poll-interval-millis:60000}")
  @SchedulerLock(
      name = "trigger_launch_reconciler",
      lockAtMostFor = "PT5M",
      lockAtLeastFor = "PT10S")
  public void reconcile() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    Instant olderThan =
        BatchDateTimeSupport.utcNow().minusSeconds(Math.max(properties.getMinAgeSeconds(), 0));
    int batch = Math.max(properties.getBatchSize(), 1);
    List<TriggerRequestLaunchReconcileRow> rows;
    try {
      rows = triggerRequestMapper.selectStaleAcceptedWithJobInstance(olderThan, batch);
    } catch (RuntimeException ex) {
      log.warn(
          "trigger launch reconciler 扫描失败,下轮重试: olderThan={} batchSize={} error={}",
          olderThan,
          batch,
          ex.getMessage());
      return;
    }
    if (rows.isEmpty()) {
      return;
    }
    int reconciled = 0;
    int skipped = 0;
    for (TriggerRequestLaunchReconcileRow row : rows) {
      try {
        int updated =
            triggerRequestMapper.reconcileLaunched(
                row.getTenantId(), row.getRequestId(), row.getJobInstanceId());
        if (updated > 0) {
          reconciled++;
          counter(METRIC_RECONCILED, "tenant", row.getTenantId()).increment();
        } else {
          // CAS miss = 并发已被 consumer writeBack / 运维改走,正常情况
          skipped++;
          counter(METRIC_SKIPPED_CAS, "tenant", row.getTenantId()).increment();
        }
      } catch (RuntimeException ex) {
        log.warn(
            "trigger launch reconciler 单行回写失败: tenantId={} requestId={} jobInstanceId={} error={}",
            row.getTenantId(),
            row.getRequestId(),
            row.getJobInstanceId(),
            ex.getMessage());
      }
    }
    log.info(
        "trigger launch reconciled: scanned={} updated={} skipped_cas={} olderThan={}",
        rows.size(),
        reconciled,
        skipped,
        olderThan);
  }

  private Counter counter(String name, String... tagPairs) {
    return Counter.builder(name).tags(Tags.of(tagPairs)).register(meterRegistry);
  }
}
