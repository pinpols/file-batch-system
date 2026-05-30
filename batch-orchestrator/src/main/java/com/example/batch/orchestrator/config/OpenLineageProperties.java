package com.example.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenLineage 血缘 emitter 配置。默认关闭 —— 仅在显式 {@code batch.openlineage.enabled=true} 且配了 {@code
 * endpoint} 时才真正向外发血缘事件。
 *
 * <p>v0.1 在 workflow_run 终态(SUCCESS / FAILED / TERMINATED)各 emit 一条 OpenLineage RunEvent (COMPLETE
 * / FAIL),fire-and-forget 异步 POST 到 {@code endpoint}(如 Marquez {@code
 * http://marquez:5000/api/v1/lineage}),失败 swallow,绝不阻塞工作流状态机主链。
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

  /** 异步发送线程池大小;血缘是 best-effort,池满即丢(不回压主链)。 */
  private int emitThreads = 2;
}
