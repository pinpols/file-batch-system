package io.github.pinpols.batch.console.domain.observability.realtime;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.config.ConsoleInstanceIdProperties;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 控制台实时实例标识。
 *
 * <p>用于标记 Redis Pub/Sub 中事件的来源实例，避免同一实例处理自己刚写入的广播消息时重复推送。实例标识从
 * {@link ConsoleInstanceIdProperties}（{@code batch.console.instance-id}）读取，未配置时生成随机 UUID 并告警。
 */
@Component
@Slf4j
public class ConsoleRealtimeInstanceIdProvider {

  private final String instanceId;

  public ConsoleRealtimeInstanceIdProvider(ConsoleInstanceIdProperties properties) {
    this.instanceId = resolveInstanceId(properties);
  }

  public String instanceId() {
    return instanceId;
  }

  private String resolveInstanceId(ConsoleInstanceIdProperties properties) {
    String configured = properties.getInstanceId();
    if (EmptyChecks.isNotBlank(configured)) {
      return configured.trim();
    }
    String generated = UUID.randomUUID().toString();
    log.warn(
        "BATCH_CONSOLE_INSTANCE_ID is not configured; generated random console realtime"
            + " instance id={}",
        generated);
    return generated;
  }
}
