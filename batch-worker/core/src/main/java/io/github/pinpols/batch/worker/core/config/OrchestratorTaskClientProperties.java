package io.github.pinpols.batch.worker.core.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Worker → Orchestrator 任务执行（{@code claim} / {@code report} / {@code renew}）的 HTTP 客户端配置。
 *
 * <p>默认值适用于生产安全超时；可通过 {@code batch.worker.task-client.*}（YAML 或环境变量）覆盖。
 */
@Data
@ConfigurationProperties(prefix = "batch.worker.task-client")
public class OrchestratorTaskClientProperties {

  private String baseUrl;

  /**
   * 可选的 Orchestrator 地址列表。非空时优先于 {@link #baseUrl}，task-client 按请求轮转；瞬态失败重试会选择
   * 下一个地址。Kubernetes 应优先配置单个 Service 地址，本配置用于 VM、裸机或本地容量画像的多实例直连。
   */
  private List<String> baseUrls = new ArrayList<>();

  private int batchSize = 10;

  /** 建立到 Orchestrator TCP 连接的连接超时。 */
  private int connectTimeoutMillis = 5_000;

  /** 等待 Orchestrator HTTP 响应体的读取超时。 */
  private int readTimeoutMillis = 30_000;

  /** {@code POST /report} 的最大尝试次数（含首次调用）；5xx 和 I/O 错误按退避重试。 */
  private int reportMaxAttempts = 4;

  private int reportInitialBackoffMillis = 200;
  private int reportMaxBackoffMillis = 5_000;

  /** {@code claim} / {@code renew} 在上游瞬态错误（5xx、超时）时的最大尝试次数。 */
  private int claimMaxAttempts = 4;

  private int claimInitialBackoffMillis = 200;
  private int claimMaxBackoffMillis = 5_000;
}
