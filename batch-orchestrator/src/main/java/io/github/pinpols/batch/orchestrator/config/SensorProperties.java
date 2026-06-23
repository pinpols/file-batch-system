package io.github.pinpols.batch.orchestrator.config;

import java.time.Duration;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** ADR-028 Sensor SPI 配置：HTTP / DB sensor 的安全边界与超时。 */
@Data
@ConfigurationProperties(prefix = "batch.sensor")
public class SensorProperties {

  /** SensorPollScheduler 总开关；false 时不调度，仅 SPI bean 可被人工/测试调用。 */
  private boolean enabled = true;

  /** SensorPollScheduler 周期；默认 10s（轮询所有 RUNNING WAIT 节点）。 */
  private Duration scanInterval = Duration.ofSeconds(10);

  /** HTTP_POLL 单次请求超时；默认 10s。 */
  private Duration httpRequestTimeout = Duration.ofSeconds(10);

  /** HTTP_POLL 连接池最大连接数；默认 100。 */
  private int httpMaxConnections = 100;

  /** DB_ROW_EXISTS 允许访问的 schema 白名单（lower-case 比较）。默认仅 biz。 */
  private List<String> dbAllowedSchemas = List.of("biz");

  /** DB_ROW_EXISTS 单条 SQL 最大执行时长；默认 5s。 */
  private Duration dbQueryTimeout = Duration.ofSeconds(5);

  /** Kafka admin 调用超时；默认 5s。 */
  private Duration kafkaAdminTimeout = Duration.ofSeconds(5);
}
