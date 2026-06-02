package com.example.batch.console.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Console 反向访问 batch-worker-atomic Actuator 端点的客户端配置(Round-3 #8)。
 *
 * <p>本配置目的:Console 在 {@code /ops/atomic-runtime} 菜单里展示 atomic worker 4 个 executor 的 effective
 * 安全门控配置, 数据源是 atomic worker 暴露的 {@code /actuator/atomicruntime} 端点。
 *
 * <p>{@link #baseUrl} 默认本地 18087(application.yml 中 atomic worker 默认端口),生产可通过 {@code
 * BATCH_WORKER_ATOMIC_BASE_URL} 注入 K8s service DNS。{@link #enabled}=false 时 Console controller 返回
 * "未配置 atomic worker 反向地址"的友好响应,不抛 500。
 */
@Data
@ConfigurationProperties(prefix = "batch.console.atomic-worker")
public class ConsoleAtomicWorkerClientProperties {

  /** 是否启用 Console 反向 HTTP 拉取 atomic worker 运行时快照。默认 true。 */
  private boolean enabled = true;

  /** atomic worker 的 base URL(含 scheme + host + port),用于拼接 {@code /actuator/atomicruntime}。 */
  private String baseUrl = "http://localhost:18087";
}
