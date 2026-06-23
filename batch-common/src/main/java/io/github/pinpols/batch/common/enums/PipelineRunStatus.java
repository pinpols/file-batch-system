package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum PipelineRunStatus implements DictEnum {
  CREATED("CREATED", "已创建"),
  RUNNING("RUNNING", "执行中"),
  SUCCESS("SUCCESS", "成功"),
  FAILED("FAILED", "失败"),
  /**
   * ⚠️ 预留态,当前未实现:枚举值 + V6 DB CHECK 为前向兼容保留,但 worker 无任何写 {@code COMPENSATING} 的路径; pipeline stage
   * 失败直接落 {@code FAILED}(不做 stage 级反向补偿)。引入 stage 补偿前,运维不应假设 pipeline 会自动补偿删异常数据。 与 dispatch 域
   * {@code FileDispatchRunStatus.COMPENSATING}(真实现)不同,勿混淆。
   */
  COMPENSATING("COMPENSATING", "补偿中"),
  TERMINATED("TERMINATED", "已终止");

  private final String code;
  private final String label;
}
