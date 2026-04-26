package com.example.batch.trigger.config;

import com.example.batch.common.config.ShedLockProviderFactory;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Trigger 集群 ShedLock 配置 — 让所有 {@code @SchedulerLock} 注解真正生效。
 *
 * <p><b>历史 bug</b>:trigger 模块原本无 ShedLockConfiguration / EnableSchedulerLock, 导致 {@code
 * TriggerForwardRetry} / {@code TriggerReconciler} / {@code WheelTriggerReconciler} / {@code
 * wheel.slidingWindow} / {@code wheel.releaseStaleMarkers} / {@code MisfirePendingExpire} / {@code
 * BatchDayCutoff} / {@code TriggerSchedulerFacade.registerAll} 等 8 处 {@code @SchedulerLock} 注解全部不生效
 * — 集群部署多实例都各跑各的(Quartz 模式下 Quartz cluster lock 兜住调度本身, 但 reconciler / forward retry / cutoff
 * 等辅助任务有重复跑风险)。
 *
 * <p>采用 JDBC 实现(`batch.shedlock` 表),复用 {@link ShedLockProviderFactory},跟 worker 模块
 * 风格一致;orchestrator 用 Redis 实现是因为它有完整 Redis 基础设施 + 动态分片调度的需要, trigger 没有这些 → JDBC 更轻。
 */
@Slf4j
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT2M")
public class ShedLockConfiguration {

  @Value("${batch.shedlock.auto-create:false}")
  private boolean autoCreateTable;

  @Bean
  public LockProvider lockProvider(DataSource dataSource) {
    LockProvider provider =
        ShedLockProviderFactory.jdbcTemplateLockProvider(dataSource, autoCreateTable);
    log.info(
        "trigger ShedLock LockProvider initialized: type={}, autoCreate={}",
        provider.getClass().getSimpleName(),
        autoCreateTable);
    return provider;
  }
}
