package com.example.batch.worker.exports.stage;

import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportStage;
import com.example.batch.worker.exports.domain.ExportStageResult;

/** 导出 pipeline 单个阶段步骤接口，各 stage 实现此接口并注册为 Spring Bean。 */
public interface ExportStageStep {

  /** 返回本步骤对应的导出阶段枚举值。 */
  ExportStage stage();

  /** 返回步骤代码，默认为 {@code EXPORT_<STAGE>}。 */
  default String stepCode() {
    return "EXPORT_" + stage().name();
  }

  /** 返回步骤名称，默认与步骤代码相同。 */
  default String stepName() {
    return stepCode();
  }

  /** 返回实现代码，默认与步骤代码相同。 */
  default String implCode() {
    return stepCode();
  }

  /**
   * 执行本阶段的业务逻辑。
   *
   * @param context 导出任务上下文
   * @return 本阶段执行结果
   */
  ExportStageResult execute(ExportJobContext context);
}
