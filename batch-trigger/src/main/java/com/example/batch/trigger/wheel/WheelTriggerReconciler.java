package com.example.batch.trigger.wheel;

import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.persistence.entity.TriggerRuntimeStateEntity;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.trigger.domain.TriggerDefinitionLoader;
import com.example.batch.trigger.mapper.TriggerRuntimeStateMapper;
import com.example.batch.trigger.support.TriggerDescriptor;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Wheel 模式下的 trigger reconciler — 周期对账 {@code job_definition} 与 {@code trigger_runtime_state}。详见
 * quartz-replacement-design.md §10。
 *
 * <p>对账逻辑:
 *
 * <ul>
 *   <li>DB enabled trigger 但 state 不存在 → INSERT runtime_state(next_fire_time = cron.next(now))
 *   <li>DB 禁用 / 不存在 但 state 存在 → DELETE runtime_state(级联清理 misfire_pending)
 *   <li>cron 表达式或时区漂移 → 重算 next_fire_time + 释放 marker(rescheduleNextFireTime)
 *   <li>cron 表达式不变,只更新 next_fire_time(non-impact)
 * </ul>
 *
 * <p><b>未做的事</b>(留待 CRUD 联动):wheel 内 task cancel — 当前依赖 stale marker 自然过期 + advanceAfterFire 找不到
 * state 时静默失败,不影响业务正确性。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "batch.trigger.scheduler-impl", havingValue = "wheel")
@RequiredArgsConstructor
public class WheelTriggerReconciler {

  private final TriggerDefinitionLoader definitionLoader;
  private final TriggerRuntimeStateMapper stateMapper;
  private final CronExpressionAdapter cronAdapter;
  private final BatchTimezoneProvider timezoneProvider;

  /**
   * 每个 (tenant, jobCode, expr) 唯一组合的"已警告过"记录。同一坏 cron 在 30s/次的 reconcile 循环里 只在<b>首次发现 +
   * 表达式变更</b>时打 WARN，避免上千条相同日志噪音。
   *
   * <p>当 trigger 被改正或禁用，键自然失效（不再出现在循环里就停止累计）。本 Map 不限大小： 平台 trigger 总数 ≪ 10k，单进程内存影响可忽略。
   */
  private final ConcurrentHashMap<String, String> warnedInvalidCron = new ConcurrentHashMap<>();

  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    log.info("wheel trigger reconciler initial run on application ready");
    reconcile();
  }

  @Scheduled(fixedDelayString = "${batch.trigger.reconcile-interval-millis:30000}")
  // lockAtLeastFor PT1S:reconcile 是幂等扫描,允许多实例间快速接管;不需要长持锁防 thrashing
  @SchedulerLock(name = "wheel_trigger_reconciler", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
  public void reconcile() {
    try {
      doReconcile();
    } catch (RuntimeException e) {
      log.warn("wheel reconcile pass failed, will retry next cycle: {}", e.getMessage());
    }
  }

  /**
   * Public 让 IT 直接调,绕开 {@code @SchedulerLock} proxy(IT 不验证 lock 行为)。生产代码不应调用此方法,走 {@link
   * #reconcile()}。
   */
  public void doReconcile() {
    List<TriggerDescriptor> dbDescriptors = definitionLoader.loadAll();
    // R-arch-audit-2026-05-23 P1: HashMap 预分配容量，避免大租户 (1k+ triggers) 场景下多次扩容。
    // 默认 load factor 0.75 → 用 size * 2 兜底，足够容纳全部 enabled CRON descriptor 不触发 resize。
    Map<Long, TriggerDescriptor> wantedById = new HashMap<>(dbDescriptors.size() * 2);
    for (TriggerDescriptor d : dbDescriptors) {
      if (!d.isEnabled() || d.getJobDefinitionId() == null) {
        continue;
      }
      // Wheel scheduler 只处理 CRON 类型（FIXED_RATE / MANUAL / EVENT 走其它路径）。
      // 非 CRON 直接跳过，不打日志（不是错误，正常分流）。
      if (!"CRON".equalsIgnoreCase(d.getScheduleType())) {
        continue;
      }
      // R7 log-audit-bug R3：早期前置校验，避免 cron 表达式格式错（比如 Linux 5 字段 "0 3 * * *"，
      // Quartz 要 6/7 字段）导致 computeNext 抛 IllegalArgumentException 让 reconcile pass 失败。
      // 同一坏 cron 只在首次发现 / 表达式变更时打 WARN，避免 30s 循环堆出千条相同日志（之前 5 个
      // e2e 测试 trigger 累计 4800 行 WARN）。
      if (!cronAdapter.isValid(d.getScheduleExpression())) {
        String key = d.getTenantId() + ":" + d.getJobCode();
        String previousExpr = warnedInvalidCron.put(key, d.getScheduleExpression());
        if (!d.getScheduleExpression().equals(previousExpr)) {
          log.warn(
              "wheel reconcile skip trigger with invalid CRON expression: tenantId={} jobCode={}"
                  + " expr=[{}] (Quartz needs 6 or 7 fields; Linux 5-field form rejected)",
              d.getTenantId(),
              d.getJobCode(),
              d.getScheduleExpression());
        }
        continue;
      }
      // 表达式已恢复 → 清掉去重缓存（下次再失效时仍能打 WARN）
      warnedInvalidCron.remove(d.getTenantId() + ":" + d.getJobCode());
      wantedById.put(d.getJobDefinitionId(), d);
    }

    int inserted = 0;
    int updated = 0;
    int deleted = 0;

    // 1) 同步 INSERT + 漂移检测
    for (TriggerDescriptor d : wantedById.values()) {
      TriggerRuntimeStateEntity existing =
          stateMapper.selectByJobDefinitionId(d.getJobDefinitionId());
      if (existing == null) {
        // 单条 trigger 处理失败时不应中断整个 pass，吞异常 + WARN
        try {
          if (insertRuntimeState(d)) {
            inserted++;
          }
        } catch (RuntimeException single) {
          log.warn(
              "wheel reconcile single trigger insert failed (jobCode={}): {}",
              d.getJobCode(),
              single.getMessage());
        }
        continue;
      }
      if (hasScheduleDrift(existing, d)) {
        Instant next = computeNext(d, BatchDateTimeSupport.utcNow());
        if (next != null) {
          ZoneId zone = timezoneProvider.resolveOrDefault(d.getTimezone());
          ZonedDateTime zdt = next.atZone(zone);
          stateMapper.rescheduleNextFireTime(
              existing.getTenantId(),
              existing.getId(),
              next,
              zone.getId(),
              zdt.toLocalDate(),
              zdt.toLocalTime());
          updated++;
          log.info(
              "wheel reschedule due to drift: jobDefId={} jobCode={} newNextFireTime={} zone={}",
              d.getJobDefinitionId(),
              d.getJobCode(),
              next,
              zone.getId());
        }
      }
    }

    // 2) 全表扫,删 DB 里已禁用 / 不存在的 state
    //    用全表扫而非按 tenant 扫,因为 loadAll 只返回 enabled,无法识别 disabled 的 tenant
    List<TriggerRuntimeStateEntity> allStates = stateMapper.selectAllJobDefinitionIds();
    for (TriggerRuntimeStateEntity state : allStates) {
      if (!wantedById.containsKey(state.getJobDefinitionId())) {
        stateMapper.deleteByJobDefinitionId(state.getJobDefinitionId());
        // P1: trigger 被禁用 / 删除 → 同步释放 warnedInvalidCron 里 (tenantId, jobCode)
        // 条目;否则该坏 cron 的"已警告过"标记会永驻进程,trigger 重建用同 jobCode + 同坏
        // cron 时反而被静默吞掉首次 WARN。
        warnedInvalidCron.remove(state.getTenantId() + ":" + state.getJobCode());
        deleted++;
        log.info(
            "wheel delete runtime_state: jobDefId={} jobCode={} (DB disabled or removed)",
            state.getJobDefinitionId(),
            state.getJobCode());
      }
    }

    if (inserted > 0 || updated > 0 || deleted > 0) {
      log.info(
          "wheel reconcile drift resolved: inserted={} updated={} deleted={} expectedTotal={}",
          inserted,
          updated,
          deleted,
          wantedById.size());
    }
  }

  private boolean insertRuntimeState(TriggerDescriptor d) {
    Instant next = computeNext(d, BatchDateTimeSupport.utcNow());
    if (next == null) {
      log.warn(
          "trigger has no future fire time, skip INSERT runtime_state: jobCode={}", d.getJobCode());
      return false;
    }
    ZoneId zone = timezoneProvider.resolveOrDefault(d.getTimezone());
    ZonedDateTime zdt = next.atZone(zone);
    TriggerRuntimeStateEntity entity = new TriggerRuntimeStateEntity();
    entity.setJobDefinitionId(d.getJobDefinitionId());
    entity.setTenantId(d.getTenantId());
    entity.setJobCode(d.getJobCode());
    entity.setNextFireTime(next);
    entity.setScheduleTimezone(zone.getId());
    entity.setScheduledLocalDate(zdt.toLocalDate());
    entity.setScheduledLocalTime(zdt.toLocalTime());
    entity.setFireSequence(1);
    try {
      stateMapper.insertOnReconcile(entity);
      return true;
    } catch (DuplicateKeyException dup) {
      SwallowedExceptionLogger.info(
          WheelTriggerReconciler.class, "catch:DuplicateKeyException", dup);

      // 并发 reconciler / 其他 leader 已 INSERT,幂等
      return false;
    }
  }

  private boolean hasScheduleDrift(TriggerRuntimeStateEntity state, TriggerDescriptor d) {
    if (!cronAdapter.isValid(d.getScheduleExpression())) {
      return false; // 表达式无效,跳过 drift 检查(下个 reconcile 等运维修)
    }
    if (state.getNextFireTime() == null) {
      return true; // DB next_fire_time 缺失 → 需要 reconcile 填上
    }
    // 用 state.nextFireTime 本身做锚点:若 cron 不变,cron.next(nextFireTime - 1ms) 应该 == nextFireTime。
    // 不用 lastFireTime 作基线 — 上次 fire 失败 → lastFireTime 不更新 → 每轮误判 drift →
    // 强行 rescheduleNextFireTime(now) 把原本待 fire 的那次跳过。
    // 锚点固定在 DB 现存 nextFireTime 上,只检测 cron expression / timezone 是否真的变了。
    Instant anchor = state.getNextFireTime().minusMillis(1);
    Instant expectedNext = computeNext(d, anchor);
    if (expectedNext == null) {
      return false;
    }
    // 容忍 1 秒误差(避免边界场景的 noise)
    return Math.abs(expectedNext.toEpochMilli() - state.getNextFireTime().toEpochMilli()) > 1000L;
  }

  private Instant computeNext(TriggerDescriptor d, Instant after) {
    return cronAdapter.next(
        d.getScheduleExpression(), timezoneProvider.resolveOrDefault(d.getTimezone()), after);
  }
}
