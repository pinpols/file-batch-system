package com.example.batch.console.domain.observability.realtime;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 控制台实时实例标识。
 *
 * <p>用于标记 Redis Pub/Sub 中事件的来源实例，避免同一实例处理自己刚写入的广播消息时重复推送。
 */
@Component
@Slf4j
public class ConsoleRealtimeInstanceIdProvider {

  private final String instanceId;

  public ConsoleRealtimeInstanceIdProvider(Environment environment) {
    this.instanceId = resolveInstanceId(environment);
  }

  public String instanceId() {
    return instanceId;
  }

  private String resolveInstanceId(Environment environment) {
    String configured =
        firstNonBlank(
            environment.getProperty("batch.console.instance-id"),
            environment.getProperty("BATCH_CONSOLE_INSTANCE_ID"));
    if (configured != null) {
      return configured;
    }
    String generated = UUID.randomUUID().toString();
    log.warn(
        "BATCH_CONSOLE_INSTANCE_ID is not configured; generated random console realtime"
            + " instance id={}",
        generated);
    return generated;
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return null;
  }
}
