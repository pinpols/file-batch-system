package io.github.pinpols.batch.console.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Console 实时实例标识配置（{@code batch.console.instance-id}）。
 *
 * <p>用于标记 Redis Pub/Sub 中事件的来源实例，多副本部署必须唯一（Helm 通过 downward API 注入 pod 名）。
 * 前缀为 {@code batch.console} 以保持历史 key {@code batch.console.instance-id} 不变，本类只绑定该单一字段。
 */
@Data
@ConfigurationProperties(prefix = "batch.console")
public class ConsoleInstanceIdProperties {

  /** 实例标识；空时由调用方生成随机 UUID 并告警。 */
  private String instanceId = "";
}
