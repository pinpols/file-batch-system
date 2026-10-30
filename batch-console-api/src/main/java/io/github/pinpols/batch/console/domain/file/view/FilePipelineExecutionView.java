package io.github.pinpols.batch.console.domain.file.view;

import lombok.Data;

/** 仅暴露流水线运行标识和当前状态，不读取业务载荷。 */
@Data
public class FilePipelineExecutionView {
  private Long pipelineInstanceId;
  private String runStatus;
  private String currentStage;
  private String systemPipelineCode;
  private Integer systemPipelineVersion;
}
