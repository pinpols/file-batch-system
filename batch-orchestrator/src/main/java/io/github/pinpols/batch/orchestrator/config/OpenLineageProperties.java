package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenLineage 血缘 emitter 配置。默认关闭 —— 仅在显式 {@code batch.openlineage.enabled=true} 且配了 {@code
 * endpoint} 时才真正向外发血缘事件。
 *
 * <p>workflow 终态经事务 Outbox 投递到独立 Kafka topic；血缘消费者仅在 endpoint 返回 2xx 后提交 offset。
 * endpoint 故障不会进入工作流状态事务，但会保留血缘积压等待恢复。
 */
@Data
@ConfigurationProperties(prefix = "batch.openlineage")
public class OpenLineageProperties {

  /** 总开关,默认关。 */
  private boolean enabled = false;

  /** 血缘事件接收端点(OpenLineage HTTP transport),如 Marquez 的 /api/v1/lineage。 */
  private String endpoint = "";

  /** OpenLineage namespace,血缘图里区分来源系统;默认 file-batch-system。 */
  private String namespace = "file-batch-system";

  /** producer URI,标识事件生产方(放进 RunEvent.producer)。 */
  private String producer = "https://github.com/pinpols/file-batch-system";

  /** HTTP 连接超时(毫秒)。 */
  private int connectTimeoutMs = 2000;

  /** HTTP 请求超时(毫秒)。 */
  private int requestTimeoutMs = 3000;

  /** 可靠消费者组。独立于业务消费者，端点失败只阻塞血缘 topic。 */
  private String consumerGroupId = "batch-openlineage-emitter";

  /** HTTP 发送失败后的 Kafka 分区暂停时间。 */
  private long retryBackoffMs = 5000L;
}
