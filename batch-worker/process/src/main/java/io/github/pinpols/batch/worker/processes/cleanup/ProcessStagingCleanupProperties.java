package io.github.pinpols.batch.worker.processes.cleanup;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * P1-7:`batch.process_staging` 孤儿清理调度配置,绑定 {@code batch.worker.process.staging-cleanup}。
 *
 * <p>用途:VALIDATE 失败保留 staging 是设计;重复 COMPUTE 会创建新 generation，旧 generation 在平台实例围栏内确认不可恢复后，
 * 按 {@code retentionHours} 保留期回收。最新 generation 不自动删除。
 */
@Data
@ConfigurationProperties(prefix = "batch.worker.process.staging-cleanup")
public class ProcessStagingCleanupProperties {

  /** 是否启用 orphan staging 清理。本地 / 单 worker 部署可关闭(false);多实例 / 生产建议开启。 */
  private boolean enabled = true;

  /**
   * 是否执行安全的旧 generation 回收。默认关闭，启用前仍需完成 PART-02 的全部生命周期与恢复验收。
   */
  private boolean deletionEnabled = false;

  /** 调度间隔。默认 15 分钟,实测可降到 1 小时。 */
  private Duration interval = Duration.ofMinutes(15);

  /**
   * generation 创建保留期(小时)。默认 24 小时;旧 generation 仍须由平台锁确认已被替代后才可回收。
   */
  private int retentionHours = 24;

  /** 单次 tick 最多删多少行(防大表全表删除阻塞业务库)。 */
  private int batchSize = 5000;

  /**
   * 预建未来日分区天数。每次 tick 确保 [今天, 今天+preCreateDays] 的日分区都存在,避免写入落到 default 回退分区。 默认 2 天冗余,容忍调度抖动 /
   * worker 短暂离线。
   */
  private int preCreateDays = 2;
}
