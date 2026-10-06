package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Worker 注册表准入治理配置（{@code batch.worker.registry}）。
 *
 * <p>同前缀的 {@code batch.worker.registry.fail-fast-on-startup} 由 worker 侧的
 * {@code WorkerRegistryStartupProperties} 绑定；两者分属不同进程上下文，子键互不重叠。
 */
@Data
@ConfigurationProperties(prefix = "batch.worker.registry")
@SuppressWarnings("ConfigurationProperties") // 与 WorkerRegistryStartupProperties 共享前缀，子键互不重叠。
public class WorkerRegistryProperties {

  /**
   * 每租户允许注册的最大 worker 数量（按非 DECOMMISSIONED 计活跃）；{@code <=0} 表示不限（默认 opt-in）。
   *
   * <p>仅对<b>新</b> worker_code 生效；幂等重注册已存在的 worker_code 始终放行，防止现有 worker 重启时因到达上限而注册失败。
   */
  private int maxPerTenant = 0;
}
