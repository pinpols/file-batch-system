package io.github.pinpols.batch.common.spi.task;

import java.util.Set;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Worker 进程的 SPI 注册配置 — Phase 5 改造(见 {@code docs/design/task-spi-design.md} §Phase 5)。
 *
 * <p>支持"同一 worker 进程注册多个 taskType"的关键:用 {@link #enabledTaskTypes} 白名单过滤 {@link
 * BatchTaskExecutorRegistry} 的注册集合。
 *
 * <p>典型场景:把"业务 import + 通用 shell + HTTP 探针"3 种 task 跑在一个 worker 进程上(运维简化)。
 *
 * <p>配置示例:
 *
 * <pre>{@code
 * batch:
 *   worker:
 *     spi:
 *       enabled-task-types:
 *         - IMPORT
 *         - shell
 *         - http
 * }</pre>
 *
 * <p>**默认空集 = 不过滤(全注册)**,保持向后兼容(改前的行为)。
 */
@Data
@ConfigurationProperties(prefix = "batch.worker.atomic")
public class BatchWorkerAtomicProperties {

  /**
   * 启用的 taskType 白名单。
   *
   * <ul>
   *   <li>空集合(默认)= 不过滤,classpath 上所有 {@link BatchTaskExecutor} 实现都注册
   *   <li>非空 = 只注册其 {@code taskType()} 在本集合的实现;未启用的 bean 仍在 Spring 容器,但 {@link
   *       BatchTaskExecutorRegistry#find(String)} 找不到
   * </ul>
   */
  private Set<String> enabledTaskTypes = Set.of();
}
