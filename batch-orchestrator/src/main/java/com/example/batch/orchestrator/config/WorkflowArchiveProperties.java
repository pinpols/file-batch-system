package com.example.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * P3-3 workflow archive 自动化配置（{@code batch.workflow.archive}）。
 *
 * <p>{@code enabled=true}（默认）周期清理终结态 workflow_run / workflow_node_run； {@code retentionDays}
 * 控制保留窗口（默认 30 天）。Cron 默认每天凌晨 04:15 跑（与 file-governance / quota reset 错峰）。
 *
 * <p>对应人工兜底脚本：{@code scripts/db/cleanup-workflow-runs.sql}（同一删除语义，可手工补刀）。
 */
@Data
@ConfigurationProperties(prefix = "batch.workflow.archive")
public class WorkflowArchiveProperties {

  private boolean enabled = true;

  /** 保留天数：终结态（SUCCESS/FAILED/TERMINATED）超过此天数的 workflow_run 被清。 */
  private int retentionDays = 30;

  /** 单次扫描批量上限：避免一次删几十万行长事务锁表；超出当前阈值剩余条目下次 tick 继续清。 */
  private int batchSize = 5_000;

  /** 调度间隔；默认每天 04:15（不和 file-governance archive / quota reset 撞峰）。 */
  private String cron = "0 15 4 * * *";
}
