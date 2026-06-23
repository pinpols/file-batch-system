package io.github.pinpols.batch.sdk.task;

import java.util.Map;

/**
 * ADR-037 决策三 — 协作式取消信号异常。
 *
 * <p>每次 {@link SdkTaskContext#commit(Map)} 成功提交后,若 {@link SdkTaskContext#isCancelled()} 为真,{@code
 * commit} 在<b>已提交的安全点</b>抛出本异常 —— 取消总是停在两个批次之间的边界,不会留半个批次的异常数据。模板顶层统一捕获并落 cancelled 终态。
 *
 * <p><b>约定:业务代码不得捕获并抑制本异常</b>(吞了就停不下来)。它继承 {@link RuntimeException} 以便穿透业务调用栈,但语义是「正常停止」而非「失败」,
 * 模板据此落 cancelled 而非 failed。{@link #breakPosition()} 携带停止时的断点,便于运维 / 续跑定位。
 */
public final class SdkTaskStoppedException extends RuntimeException {

  private final transient Map<String, Object> breakPosition;

  public SdkTaskStoppedException(Map<String, Object> breakPosition) {
    super("task stopped cooperatively at break position: " + breakPosition);
    this.breakPosition = breakPosition == null ? Map.of() : Map.copyOf(breakPosition);
  }

  /** 取消生效时已提交的断点坐标(续跑从此处往后)。 */
  public Map<String, Object> breakPosition() {
    return breakPosition;
  }
}
