package io.github.pinpols.batch.worker.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Worker 运行实例身份配置。池代码负责稳定路由，实例 ID 只负责区分同一池中的进程或 Pod。 */
@Data
@ConfigurationProperties(prefix = "batch.worker.identity")
public class WorkerIdentityProperties {

  /**
   * 当前运行实例的唯一后缀。Kubernetes 通过 Downward API 注入 Pod UID；为空时保留旧的单实例 workerCode
   * 行为，兼容本地开发、Sim 和旧部署。
   */
  private String instanceId = "";
}
