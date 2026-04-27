package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 批量生命周期公共状态码。
 *
 * <p>系统中并行存在 {@link JobStatus} / {@link JobInstanceStatus} / {@link PartitionStatus} / {@link
 * TaskStatus} / {@link StepInstanceStatus} 五个状态枚举，各自演化、跨层逻辑（"任一分区 失败 ⇒ 实例失败"等）需要 5 套映射，存在语义漂移风险。
 *
 * <p>本枚举提供唯一公共语义码，五个具体状态枚举通过自带的 {@code lifecycle()} 方法投影到这里。
 *
 * <p>这是**派生类型**，不直接落 DB；持久化仍用各具体枚举的 {@link #code()}。
 *
 * <p>状态机：
 *
 * <pre>
 *   CREATED → WAITING → READY → RUNNING ──► SUCCESS
 *                                       ├─► FAILED
 *                                       ├─► CANCELLED
 *                                       └─► TERMINATED
 * </pre>
 */
@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum BatchLifecycleStatus implements DictEnum {
  /** 已落库但未参与调度。 */
  CREATED("CREATED", "已创建"),
  /** 等待依赖 / 资源（DAG 上游、批次窗口、配额释放等）。 */
  WAITING("WAITING", "等待中"),
  /** 准备就绪，可被 worker 领取或调度器派发。 */
  READY("READY", "待执行"),
  /** 真正在跑（包含 RETRYING 等"还在推进中"的中间态）。 */
  RUNNING("RUNNING", "执行中"),
  /** 成功结束。 */
  SUCCESS("SUCCESS", "成功"),
  /** 失败结束（含 PARTIAL_FAILED 这类"部分失败但视为整体失败"的终态）。 */
  FAILED("FAILED", "失败"),
  /** 用户/系统取消。 */
  CANCELLED("CANCELLED", "已取消"),
  /** 强制终止（与 CANCELLED 区分语义：终止是不可恢复的 hard stop）。 */
  TERMINATED("TERMINATED", "已终止");

  private final String code;
  private final String label;

  /** 是否处于终态（不会再变化）。 */
  public boolean terminal() {
    return this == SUCCESS || this == FAILED || this == CANCELLED || this == TERMINATED;
  }
}
