package com.example.batch.sdk.dispatcher;

import java.util.List;
import java.util.Map;

/**
 * SDK Phase 2 §2.3:心跳回包里的"平台指令"投影 —— 对齐平台 {@code
 * com.example.batch.common.dto.WorkerHeartbeatResponse}。
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

  /**
   * 从心跳响应 JSON(已被 {@code PlatformHttpClient} 反序列化为 Map)解析指令。 老平台回包为空 Map / 缺字段时一律降级为
   * NORMAL、不暂停(向后兼容)。
   */
  @SuppressWarnings("unchecked")
  public static HeartbeatDirective fromResponse(Map<String, Object> resp) {
    if (resp == null || resp.isEmpty()) {
      return new HeartbeatDirective(STATUS_NORMAL, null, false, List.of(), null);
    }
    String status = asString(resp.get("platformStatus"));
    boolean drain = Boolean.TRUE.equals(resp.get("shouldDrain"));
    List<String> paused =
        resp.get("pausedTaskTypes") instanceof List<?> l
            ? l.stream().map(String::valueOf).toList()
            : List.of();
    return new HeartbeatDirective(
        status == null ? STATUS_NORMAL : status,
        asInteger(resp.get("desiredMaxConcurrent")),
        drain,
        paused,
        asInteger(resp.get("nextHeartbeatHint")));
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

  private static String asString(Object v) {
    return v == null ? null : String.valueOf(v);
  }

  private static Integer asInteger(Object v) {
    return v instanceof Number n ? n.intValue() : null;
  }
}
