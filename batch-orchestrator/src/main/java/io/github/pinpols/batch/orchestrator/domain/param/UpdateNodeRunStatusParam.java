package io.github.pinpols.batch.orchestrator.domain.param;

import io.github.pinpols.batch.common.i18n.LocalizedErrorCarrier;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UpdateNodeRunStatusParam implements LocalizedErrorCarrier {
  private final Long id;
  private final String nodeStatus;
  private final String errorCode;
  private final String errorMessage;

  /** i18n message key,V77+ 写入 workflow_node_run.error_key。 */
  private final String errorKey;

  /** i18n 占位符参数 JSON 数组。 */
  private final String errorArgs;

  private final Long durationMs;
  private final Instant finishedAt;

  /**
   * ADR-009 Stage 1.2: 节点 SUCCESS 时由 worker 上报的产出 JSON(已序列化字符串),写入 workflow_node_run.output JSONB
   * 列,供下游节点 DSL 引用。null 表示无产出。
   */
  private final String output;
}
