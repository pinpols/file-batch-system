package io.github.pinpols.batch.sdk.dispatcher;

import io.github.pinpols.batch.sdk.internal.EmptyChecks;
import java.util.List;
import java.util.Map;

/**
 * SDK Phase 2 §2.3:心跳回包里的"平台指令"投影 —— 对齐平台 {@code
 * io.github.pinpols.batch.common.dto.WorkerHeartbeatResponse}。
 *
 * <p>SDK 每次心跳后用本指令驱动 {@link WorkerRuntimeState} 4 态状态机,实现 console 暂停 / 排空 / 限流的秒级感知。
 *
 * @param platformStatus 平台期望态(NORMAL / DRAINING;PAUSED / DEGRADED 预留);null/未知当 NORMAL
 * @param desiredMaxConcurrent 平台希望的最大并发;null = 不下发,SDK 用本地配置
 * @param shouldDrain true = 停止认领新任务、跑完在手任务后下线
 * @param pausedTaskTypes 被暂停的 taskType(当前平台恒空,预留)
 * @param nextHeartbeatHint 建议下次心跳间隔(秒);null = 不下发
 */
public record HeartbeatDirective(
    String platformStatus,
    Integer desiredMaxConcurrent,
    boolean shouldDrain,
    List<String> pausedTaskTypes,
    Integer nextHeartbeatHint) {

  public static final String STATUS_NORMAL = "NORMAL";
  public static final String STATUS_DEGRADED = "DEGRADED";
  public static final String STATUS_PAUSED = "PAUSED";
  public static final String STATUS_DRAINING = "DRAINING";

  /** 仅供同包契约测试构造旧平台回包；生产 transport 直接反序列化为本 record。 */
  static HeartbeatDirective fromResponse(Map<String, Object> response) {
    if (EmptyChecks.isEmpty(response)) {
      return new HeartbeatDirective(STATUS_NORMAL, null, false, List.of(), null);
    }
    Object paused = response.get("pausedTaskTypes");
    return new HeartbeatDirective(
        response.get("platformStatus") == null
            ? STATUS_NORMAL
            : response.get("platformStatus").toString(),
        integerValue(response.get("desiredMaxConcurrent")),
        Boolean.TRUE.equals(response.get("shouldDrain")),
        paused instanceof List<?> values
            ? values.stream().map(String::valueOf).toList()
            : List.of(),
        integerValue(response.get("nextHeartbeatHint")));
  }

  private static Integer integerValue(Object value) {
    return value instanceof Number number ? number.intValue() : null;
  }

  /**
   * 指令映射到 worker 运行态:shouldDrain / DRAINING 最高优先 → DRAINING;PAUSED → PAUSED;DEGRADED →
   * DEGRADED;其余(含未知值,向后兼容)→ NORMAL。
   */
  public WorkerRuntimeState toRuntimeState() {
    if (shouldDrain || STATUS_DRAINING.equals(platformStatus)) {
      return WorkerRuntimeState.DRAINING;
    }
    if (STATUS_PAUSED.equals(platformStatus)) {
      return WorkerRuntimeState.PAUSED;
    }
    if (STATUS_DEGRADED.equals(platformStatus)) {
      return WorkerRuntimeState.DEGRADED;
    }
    return WorkerRuntimeState.NORMAL;
  }
}
