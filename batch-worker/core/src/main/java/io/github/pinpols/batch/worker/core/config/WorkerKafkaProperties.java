package io.github.pinpols.batch.worker.core.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Worker Kafka 属性绑定；保留既有 spring.kafka 键名、毫秒单位与默认值，不启用额外自动配置。 */
@Data
@Validated
@ConfigurationProperties(prefix = "spring.kafka")
public class WorkerKafkaProperties {

  @NotBlank(message = "worker Kafka bootstrap servers must not be blank")
  private String bootstrapServers;

  @Valid
  private final Consumer consumer = new Consumer();

  @Valid
  private final Listener listener = new Listener();

  @Data
  public static class Consumer {
    @NotBlank(message = "worker Kafka auto offset reset must not be blank")
    private String autoOffsetReset = "latest";

    @Min(value = 1, message = "worker Kafka max poll records must be positive")
    private int maxPollRecords = 20;

    @Min(value = 0, message = "worker Kafka fetch min size must not be negative")
    private int fetchMinSize = 1024;

    @Min(value = 0, message = "worker Kafka fetch max wait must not be negative")
    private int fetchMaxWait = 500;

    /** 必须大于单批最长执行时延；跨字段背压约束仍由容器工厂校验。 */
    @Min(value = 1, message = "worker Kafka max poll interval must be positive")
    private int maxPollIntervalMs = 600_000;

    /** PATTERN 订阅每 30 秒发现新增 topic，避免 Kafka 默认五分钟刷新影响任务接入。 */
    @Min(value = 0, message = "worker Kafka metadata max age must not be negative")
    private int metadataMaxAgeMs = 30_000;
  }

  @Data
  public static class Listener {
    @Min(value = 1, message = "worker Kafka listener concurrency must be positive")
    private int concurrency = 1;
  }
}
