package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Job 实例运行树自动归档配置（{@code batch.job-instance.archive}）。
 *
 * <p>对到期终态实例先复制冷表并检查，再按依赖清理热表；独立的历史清理脚本不复制冷表。
 */
@Data
@ConfigurationProperties(prefix = "batch.job-instance.archive")
public class SuccessInstanceArchiveProperties {

  private boolean enabled = true;

  /** 终态实例热表保留天数。默认 30 天。 */
  private int retentionDays = 30;

  /**
   * 单批 instance id 上限。每批要级联删 12+ 张表，batch 太大会拖长事务锁表。 默认 1000；按 instance 平均 5 partition × 5 step × 5
   * task 估算，单批 ~75k 行删除。
   */
  private int batchSize = 1_000;

  /** 默认每周日 04:30 跑（与 file-archive、quota-reset、workflow-archive 错峰）。 */
  private String cron = "0 30 4 * * SUN";
}
