package com.example.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 告警升级阶梯配置(运维告警闭环)。
 *
 * <p>OPEN 告警若长期无人 ack(状态停在 {@code OPEN}),由 {@code AlertEscalationScheduler} 周期性 sweep 逐级抬升
 * {@code escalation_tier},每升一级打一条 ERROR 日志 + {@code batch.alert.escalations} 计数,供日志/指标侧的
 * 运维告警链路放大可见度——避免卡住的告警静默无声。
 *
 * <ul>
 *   <li>{@code enabled=true}(默认):开启周期 sweep
 *   <li>{@code slaMinutes}:每级静默阈值基数,第 N 级需静默 {@code slaMinutes*N} 分钟(越高级越慢)
 *   <li>{@code maxTier}:最高升到第几级后停止(防止无限升级)
 *   <li>{@code batchLimit}:单次 sweep 最多处理多少条,控制单事务规模
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "batch.alert.escalation")
public class AlertEscalationProperties {

  private boolean enabled = true;
  private int slaMinutes = 30;
  private int maxTier = 3;
  private int batchLimit = 200;
}
