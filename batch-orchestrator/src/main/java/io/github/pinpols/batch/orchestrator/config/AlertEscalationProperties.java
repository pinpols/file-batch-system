package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 告警升级阶梯配置(运维告警闭环)。
 *
 * <p>OPEN 告警长期无人 ack 时,sweep 逐级抬升 escalation_tier 放大可见度,避免静默淹没。
 *
 * <ul>
 *   <li>enabled:默认 true,开启周期 sweep
 *   <li>slaMinutes:每级静默阈值基数,第 N 级需静默 slaMinutes*N 分钟
 *   <li>maxTier:升到此 tier 后停止(防无限升级)
 *   <li>batchLimit:单次 sweep 最多处理条数,控制单事务规模
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
