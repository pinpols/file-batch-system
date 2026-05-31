package com.example.batch.common.dto;

import com.example.batch.common.enums.WorkerRegistryStatus;
import java.util.List;

/**
 * SDK Phase 2 §2.3:心跳响应里的"平台指令"(platform directive)。
 *
 * <p>平台借每次心跳回包把"当前希望 worker 怎么干"下发给 SDK,SDK 据此驱动 4 态状态机 (NORMAL/DEGRADED/PAUSED/DRAINING),实现
 * console 暂停 / 排空 / 限流的秒级感知,而不必新开通道。
 *
 * <p>字段语义:
 *
 * <ul>
 *   <li>{@code platformStatus}:平台对该 worker 的期望态;当前取值 {@code NORMAL} / {@code DRAINING}
 *       (worker_registry.status 为 DRAINING/DECOMMISSIONED 时)。{@code PAUSED} / {@code DEGRADED}
 *       预留给后续租户级暂停 / 降级基础设施。
 *   <li>{@code desiredMaxConcurrent}:平台希望的最大并发(取自 worker_registry.max_concurrent,V87 反压字段); null =
 *       不下发,SDK 用本地配置。
 *   <li>{@code shouldDrain}:true = worker 应停止认领新任务、跑完在手任务后下线(DRAINING/DECOMMISSIONED)。
 *   <li>{@code pausedTaskTypes}:被暂停的 taskType 列表;当前恒空(无 taskType 级暂停基础设施),预留。
 *   <li>{@code nextHeartbeatHint}:平台建议的下次心跳间隔(秒);null = 不下发,SDK 用本地配置。
 * </ul>
 *
 * <p>向后兼容:老 SDK 忽略整个响应体(worker-core 用 {@code toBodilessEntity()},SDK 当前不消费心跳回包), 因此本响应从"返回
 * WorkerRegistryEntity"切到本 DTO 不破坏任何现有 worker。
 */
public record WorkerHeartbeatResponse(
    String platformStatus,
    Integer desiredMaxConcurrent,
    boolean shouldDrain,
    List<String> pausedTaskTypes,
    Integer nextHeartbeatHint) {

  /** 平台期望态:正常。 */
  public static final String STATUS_NORMAL = "NORMAL";

  /** 平台期望态:排空中。 */
  public static final String STATUS_DRAINING = "DRAINING";

  /**
   * 从 worker_registry 当前状态 + 并发上限构造指令。仅依赖 batch-common 内的 {@link WorkerRegistryStatus},不引
   * orchestrator 类型。
   */
  public static WorkerHeartbeatResponse fromWorkerState(
      String workerStatus, Integer maxConcurrent) {
    boolean draining =
        WorkerRegistryStatus.DRAINING.name().equals(workerStatus)
            || WorkerRegistryStatus.DECOMMISSIONED.name().equals(workerStatus);
    return new WorkerHeartbeatResponse(
        draining ? STATUS_DRAINING : STATUS_NORMAL, maxConcurrent, draining, List.of(), null);
  }
}
