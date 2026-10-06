package io.github.pinpols.batch.worker.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Worker 自注册启动行为配置（{@code batch.worker.registry}）。
 *
 * <p>把原先 {@code AbstractWorkerLoop} 里的
 * {@code @Value("${batch.worker.registry.fail-fast-on-startup:true}")} 收敛为类型安全配置，保留原有 key。
 *
 * <p>同前缀的 {@code batch.worker.registry.max-per-tenant} 由 orchestrator 侧的
 * {@code orchestrator.config.WorkerRegistryProperties} 绑定（注册准入上限），两者分属不同进程上下文，
 * 各自只绑定自己声明的字段。
 */
@Data
@ConfigurationProperties(prefix = "batch.worker.registry")
@SuppressWarnings("ConfigurationProperties") // 与 WorkerRegistryProperties 共享前缀，子键互不重叠。
public class WorkerRegistryStartupProperties {

  /** worker 启动期自注册失败时是否快速失败；false 时降级为告警并继续启动。 */
  private boolean failFastOnStartup = true;
}
