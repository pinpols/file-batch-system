package com.example.batch.console.domain.entity;

import com.example.batch.common.i18n.LocalizedErrorCarrier;
import java.time.Instant;
import lombok.Data;

@Data
public class WorkflowNodeRunEntity implements LocalizedErrorCarrier {

  private Long id;
  private Long workflowRunId;
  private String nodeCode;
  private String nodeType;
  private Integer runSeq;
  private String nodeStatus;
  private Integer retryCount;
  private String errorCode;
  private String errorMessage;

  /** i18n message key,V77+ 写入;读路径按当前 Locale 渲染时优先于 errorMessage。 */
  private String errorKey;

  /** i18n 占位符参数 JSON 数组,与 errorKey 一起支持历史日志按 Locale 重渲染。 */
  private String errorArgs;

  private Instant startedAt;
  private Instant finishedAt;
  private Long durationMs;
}
