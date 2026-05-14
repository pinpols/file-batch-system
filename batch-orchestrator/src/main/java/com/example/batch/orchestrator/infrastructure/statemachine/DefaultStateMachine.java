package com.example.batch.orchestrator.infrastructure.statemachine;

import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.domain.statemachine.StateMachine;
import com.example.batch.orchestrator.domain.statemachine.StateTransition;
import com.example.batch.orchestrator.domain.statemachine.Stateful;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 默认状态机实现。
 *
 * <p>将任意领域对象的当前状态与触发事件映射为目标状态三元组（{@link StateTransition}）。 状态解析优先级：{@code String} → {@code
 * Enum.name()} → {@link Stateful#getStatus()} → 反射调用 {@code
 * getInstanceStatus/getPartitionStatus/getTaskStatus/getRunStatus/getNodeStatus/getStatus}；
 * 若均无法解析则快速失败抛出 {@link IllegalStateException}，避免状态静默损坏。 事件到目标状态的映射在 {@code resolveToState} 中以
 * switch 表达式集中维护， 未知事件保持原状态不变（NOOP 语义）。
 */
@Slf4j
@Component
public class DefaultStateMachine<T> implements StateMachine<T> {

  @Override
  public StateTransition transition(T target, String event) {
    String fromState = resolveState(target);
    String normalizedEvent = normalizeEvent(event);
    return new StateTransition(
        fromState, normalizedEvent, resolveToState(fromState, normalizedEvent));
  }

  private String resolveState(T target) {
    if (target == null) {
      return "UNKNOWN";
    }
    if (target instanceof String status) {
      return status;
    }
    if (target instanceof Enum<?> enumValue) {
      return enumValue.name();
    }
    // 编译期安全路径：实现 Stateful 的实体不经反射解析。
    if (target instanceof Stateful stateful) {
      String status = stateful.getStatus();
      if (Texts.hasText(status)) {
        return status;
      }
    }
    // 尚未实现 Stateful 的类型走反射兜底。
    for (String methodName :
        List.of(
            "getInstanceStatus",
            "getPartitionStatus",
            "getTaskStatus",
            "getRunStatus",
            "getNodeStatus",
            "getStatus")) {
      String status = invokeStringGetter(target, methodName);
      if (Texts.hasText(status)) {
        return status;
      }
    }
    // M-1: 返回类名作为状态会静默损坏工作流状态，快速失败以暴露问题而非掩盖。
    throw new IllegalStateException(
        "Cannot resolve status from "
            + target.getClass().getName()
            + ": implement Stateful or expose a getStatus() / getXxxStatus() method");
  }

  private String invokeStringGetter(T target, String methodName) {
    try {
      Method method = target.getClass().getMethod(methodName);
      Object value = method.invoke(target);
      return value instanceof String status ? status : null;
    } catch (ReflectiveOperationException exception) {
      SwallowedExceptionLogger.info(
          DefaultStateMachine.class, "catch:ReflectiveOperationException", exception);

      return null;
    }
  }

  private String normalizeEvent(String event) {
    return Texts.hasText(event) ? event.trim().toUpperCase() : "NOOP";
  }

  /** 终态集合：到达任意一个后业务认为该实体生命周期结束。 终态收到非自回边事件需要保留原状态 + WARN，避免静默"复活"或"切换终态"。 */
  private static final Set<String> TERMINAL_STATES =
      Set.of("SUCCESS", "FAILED", "PARTIAL_FAILED", "CANCELLED", "TERMINATED", "SKIPPED");

  private String resolveToState(String fromState, String event) {
    String candidate =
        switch (event) {
          case "READY" -> "READY";
          case "START", "CLAIM", "RUN", "DISPATCH", "RETRYING", "RUNNING" -> "RUNNING";
          case "SUCCESS", "SUCCEED", "COMPLETE", "FINISH" -> "SUCCESS";
          case "PARTIAL_FAILED" -> "PARTIAL_FAILED";
          case "FAIL", "FAILED", "ERROR", "REJECT" -> "FAILED";
          case "TERMINATE", "CANCEL", "TERMINATED", "CANCELLED" -> "TERMINATED";
          case "SKIP", "SKIPPED" -> "SKIPPED";
          case "WAITING", "CREATED", "PENDING", "NOOP" -> fromState;
          // A-3.3: 未知事件保留 NOOP 语义不变（向后兼容）。
          // 自回边（event == fromState）属幂等重复上报，DEBUG 即可；
          // 其他未知事件（如 "SUCESS" 拼错）保留 WARN 以便诊断状态机误用。
          default -> {
            if (fromState != null && fromState.equalsIgnoreCase(event)) {
              log.debug("state machine self-transition noop: state={}", fromState);
            } else {
              log.warn(
                  "state machine NOOP on unknown event: fromState={}, event={} — check for typo",
                  fromState,
                  event);
            }
            yield fromState;
          }
        };
    return guardTerminal(fromState, event, candidate);
  }

  /**
   * 终态守护：fromState 已是终态时，event 不应再驱动状态变化（除自回边 / NOOP）。
   *
   * <p>历史上各 mapper SQL 都自带 expectedStatus CAS 兜底；但状态机层做开放映射意味着， 任何未通过那些 mapper 的直接 UPDATE
   * 路径（或测试代码、迁移脚本）都能用本方法构造非法转移 （TERMINATED → SUCCESS 等"复活"）。这里在上游堵住，让"非法转移"在状态机层就退化为 NOOP +
   * WARN，更易诊断。
   */
  private String guardTerminal(String fromState, String event, String candidate) {
    if (fromState == null || candidate == null) {
      return candidate;
    }
    if (!TERMINAL_STATES.contains(fromState)) {
      return candidate;
    }
    if (candidate.equals(fromState)) {
      // 终态自回边（同终态重复上报）属幂等，保持
      return candidate;
    }
    log.warn(
        "state machine refuses illegal transition from terminal: fromState={}, event={},"
            + " refusedTarget={} — staying in fromState",
        fromState,
        event,
        candidate);
    return fromState;
  }
}
