package com.example.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * P3-3 archive 系列：outbox_event 自动归档配置（{@code batch.outbox.archive}）。
 *
 * <p>对应人工兜底脚本 {@code scripts/db/cleanup-outbox-events.sql}（同删除语义，可手工补充清理）。
 *
 * <p>策略：
 *
 * <ul>
 *   <li>{@code PUBLISHED} 已成功投递事件保留 {@link #publishedRetentionDays} 天（默认 7）
 *   <li>{@code GIVE_UP} 重试耗尽放弃事件保留 {@link #giveUpRetentionDays} 天（默认 30，便于事故复盘）
 *   <li>{@code NEW / FAILED / PUBLISHING} 永远不归档（活跃事件）
 *   <li>关联 {@code event_delivery_log} 走 FK 级联同步删（不能反过来：FK 限制 outbox 必须后删）
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "batch.outbox.archive")
public class OutboxArchiveProperties {

  private boolean enabled = true;

  private int publishedRetentionDays = 7;

  private int giveUpRetentionDays = 30;

  /** 单批上限：每次最多删 N 条 outbox + 关联 delivery log，防长事务锁表。 */
  private int batchSize = 5_000;

  /** 默认每天 03:30；与 quota reset / file archive / workflow archive (04:15) 错峰。 */
  private String cron = "0 30 3 * * *";
}
