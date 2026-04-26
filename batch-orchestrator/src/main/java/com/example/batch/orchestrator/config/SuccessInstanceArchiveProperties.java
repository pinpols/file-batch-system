package com.example.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * P3-3 archive 系列：SUCCESS / PARTIAL_FAILED job_instance 自动归档配置 （{@code
 * batch.job-instance.archive}）。
 *
 * <p>对应人工兜底脚本 {@code scripts/db/cleanup-success-instances.sql}（同删除语义、同级联顺序）。
 *
 * <p>注：FAILED / CANCELLED / TERMINATED 实例由 {@code cleanup-historical-failures.sql}（手工脚本）
 * 处理；调度器自动化暂只覆盖 SUCCESS / PARTIAL_FAILED（保留 30 天的长归档场景）。
 */
@Data
@ConfigurationProperties(prefix = "batch.job-instance.archive")
public class SuccessInstanceArchiveProperties {

  private boolean enabled = true;

  /** SUCCESS / PARTIAL_FAILED 实例保留天数。默认 30 天。 */
  private int retentionDays = 30;

  /**
   * 单批 instance id 上限。每批要级联删 12+ 张表，batch 太大会拖长事务锁表。 默认 1000；按 instance 平均 5 partition × 5 step × 5
   * task 估算，单批 ~75k 行删除。
   */
  private int batchSize = 1_000;

  /** 默认每周日 04:30 跑（与 file-archive、quota-reset、workflow-archive 错峰）。 */
  private String cron = "0 30 4 * * SUN";
}
