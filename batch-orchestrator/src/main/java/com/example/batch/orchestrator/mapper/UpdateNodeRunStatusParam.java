package com.example.batch.orchestrator.mapper;

import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UpdateNodeRunStatusParam {
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
}
