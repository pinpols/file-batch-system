package io.github.pinpols.batch.orchestrator.application.readiness;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Trigger 内部调用配置（Orchestrator 主动驱动 readiness 恢复）。 */
@Data
@ConfigurationProperties(prefix = "batch.trigger.internal")
public class TriggerInternalProperties {

  /** Trigger 服务内部端点基址；仅用于 readiness 恢复这类内部驱动调用。 */
  private String baseUrl = "http://localhost:18081";

  /** 内部恢复请求连接超时。 */
  private Duration connectTimeout = Duration.ofSeconds(2);

  /** 单次恢复请求总超时, 避免阻塞后续等待项。 */
  private Duration requestTimeout = Duration.ofSeconds(5);
}
