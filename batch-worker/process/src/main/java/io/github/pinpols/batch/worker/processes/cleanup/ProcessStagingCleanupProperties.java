package io.github.pinpols.batch.worker.processes.cleanup;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * P1-7:`batch.process_staging` 孤儿清理调度配置,绑定 {@code batch.worker.process.staging-cleanup}。
 *
 * <p>用途:VALIDATE 失败保留 staging 是设计;但 worker 在 COMMIT 后崩溃 / 网络分区导致 FEEDBACK 失败 / 历史 batchKey
 * 重跑后旧行未被覆盖,会留下孤儿 staging 行。本调度作为回退,按 {@code retentionHours} 删除超过保留期的 staging 行(以 {@code staged_at}
 * 为准)。
 */
@Data
@ConfigurationProperties(prefix = "batch.worker.process.staging-cleanup")
public class ProcessStagingCleanupProperties {

  /** 是否启用 orphan staging 清理。本地 / 单 worker 部署可关闭(false);多实例 / 生产建议开启。 */
  private boolean enabled = true;

  /** 调度间隔。默认 15 分钟,实测可降到 1 小时。 */
  private Duration interval = Duration.ofMinutes(15);

  /**
   * 保留期(小时)。staged_at 早于 now() - retentionHours 的行被认为是孤儿,删除。 默认 24 小时;若需要保留更长 forensics 窗口可调到 72。
   */
  private int retentionHours = 24;

  /** 单次 tick 最多删多少行(防大表全表删除阻塞业务库)。 */
  private int batchSize = 5000;

  /**
   * 分区保留天数。{@code process_staging} 按 {@code staged_at} 天级 RANGE 分区,早于 {@code now() - retentionDays}
   * 天的整个日分区被 DROP(瞬间还空间给 OS,根治堆膨胀)。staging 行本就短命(COMPUTE 写、FEEDBACK 清),3 天足够覆盖长期停滞的孤儿 run;
   * 调大仅增加磁盘占用上限。**0 或负数 = 关闭分区 DROP**(仅保留 orphan DELETE,用于不希望自动 DROP 分区的环境)。
   */
  private int retentionDays = 3;

  /**
   * 预建未来日分区天数。每次 tick 确保 [今天, 今天+preCreateDays] 的日分区都存在,避免写入落到 default 回退分区。 默认 2 天冗余,容忍调度抖动 /
   * worker 短暂离线。
   */
  private int preCreateDays = 2;
}
