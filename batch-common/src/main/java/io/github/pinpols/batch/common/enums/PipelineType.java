package io.github.pinpols.batch.common.enums;

import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * Worker 流水线视角的"任务类型"枚举,落 DB 字段 {@code pipeline_instance.pipeline_type}。
 *
 * <p>与 {@link JobType}(console 视角)平行演化,详见 {@code docs/design/batch-classification-and-gaps.md}
 * §3.2。 统一公共投影见 {@link BatchType};通过 {@link #batchType()} 反查后,worker 侧"按管道类型分阶段"的逻辑只依赖 BatchType,
 * 后续加 PROCESS / SYNC 时无需再改这里。
 */
@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum PipelineType implements DictEnum {
  IMPORT("IMPORT", "导入", List.of("RECEIVE", "PREPROCESS", "PARSE", "VALIDATE", "LOAD", "FEEDBACK")),
  EXPORT("EXPORT", "导出", List.of("PREPARE", "GENERATE", "STORE", "REGISTER", "COMPLETE")),
  PROCESS("PROCESS", "加工", List.of("PREPARE", "COMPUTE", "VALIDATE", "COMMIT", "FEEDBACK")),
  DISPATCH(
      "DISPATCH", "派发", List.of("PREPARE", "DISPATCH", "ACK", "RETRY", "COMPENSATE", "COMPLETE"));

  private final String code;
  private final String label;
  private final List<String> stages;

  public static List<String> stages(String code) {
    for (PipelineType type : values()) {
      if (type.code.equals(code)) {
        return type.stages;
      }
    }
    return List.of();
  }

  /** 投影到公共业务类型字典 {@link BatchType}。每个枚举值必须有非空映射。 */
  public BatchType batchType() {
    return switch (this) {
      case IMPORT -> BatchType.IMPORT;
      case EXPORT -> BatchType.EXPORT;
      case PROCESS -> BatchType.PROCESS;
      case DISPATCH -> BatchType.DISPATCH;
    };
  }
}
