package com.example.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.worker.drain")
public class WorkerDrainProperties {

  /** Orchestrator 接管飞行中任务前的默认排空等待窗口。 */
  private int defaultTimeoutSeconds = 600;

  /** 轮询处于 DRAINING 状态的 Worker 是否超期的间隔。 */
  private long checkIntervalMillis = 15000L;

  private boolean enabled = true;

  /**
   * Worker 心跳超时阈值（秒）：{@code heartbeat_at} 超过该阈值未更新即视为失联， 由 {@code WorkerHeartbeatTimeoutScheduler}
   * 降级为 {@code OFFLINE}。
   */
  private int heartbeatTimeoutSeconds = 90;

  /**
   * 心跳判定的 grace period（秒）：实际超时阈值 = {@link #heartbeatTimeoutSeconds} + {@link
   * #heartbeatGraceSeconds}。
   *
   * <p>用于吸收 worker 长 GC pause、网络抖动、容器迁移等瞬时不可达，避免反复抖动导致的误判（standby/抢占触发）。 cutoff 已在 SQL 内由 DB {@code
   * current_timestamp} 计算（消除时钟漂移），grace 是业务侧的额外冗余。
   */
  private int heartbeatGraceSeconds = 30;

  /** Worker 心跳超时扫描间隔。 */
  private long heartbeatCheckIntervalMillis = 30000L;

  /**
   * 是否装载 {@link
   * com.example.batch.orchestrator.infrastructure.scheduler.WorkerHeartbeatTimeoutScheduler}。 默认为
   * true（生产必须开启）；integration profile 通常会关闭以免 seed worker 无续心跳误判离线。
   */
  private boolean heartbeatTimeoutSchedulerEnabled = true;
}
