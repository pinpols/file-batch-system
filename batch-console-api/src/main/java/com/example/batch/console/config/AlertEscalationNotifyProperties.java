package com.example.batch.console.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 告警升级「最后一公里通知」调度参数。
 *
 * <p>orchestrator 侧的 {@code AlertEscalationScheduler} 只抬升 {@code escalation_tier} + 打日志/指标(放大可见度,
 * 不直接发通知)。本属性驱动 console-api 侧的 {@code AlertEscalationNotifier}:把「刚升级、还没通知过」的告警经现有 webhook
 * 投递链路推到订阅方,闭合升级→通知的回路。EMAIL/钉钉/企微 sender 仍未接通,故 v1 只覆盖 WEBHOOK 渠道。
 */
@Data
@ConfigurationProperties(prefix = "batch.alert.escalation.notify")
public class AlertEscalationNotifyProperties {

  /** 整体开关;{@code false} 时 notifier poll 直接短路(退化回 V176 的纯日志/指标放大)。 */
  private boolean enabled = true;

  /** 轮询间隔(ms)。默认 60 000(1 分钟),与 orchestrator 升级 sweep 周期对齐。 */
  private long pollIntervalMillis = 60_000L;

  /** 单批最多处理多少条待通知告警,控制单轮规模。默认 100。 */
  private int batchSize = 100;
}
