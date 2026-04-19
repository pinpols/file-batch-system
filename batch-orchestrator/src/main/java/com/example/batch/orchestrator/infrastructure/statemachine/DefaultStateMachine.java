package com.example.batch.orchestrator.infrastructure.statemachine;

import com.example.batch.orchestrator.domain.statemachine.StateMachine;
import com.example.batch.orchestrator.domain.statemachine.StateTransition;
import com.example.batch.orchestrator.domain.statemachine.Stateful;
import java.lang.reflect.Method;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.example.batch.common.utils.Texts;

/**
 * 默认状态机实现。
 *
 * <p>将任意领域对象的当前状态与触发事件映射为目标状态三元组（{@link StateTransition}）。
 * 状态解析优先级：{@code String} → {@code Enum.name()} → {@link Stateful#getStatus()} →
 * 反射调用 {@code getInstanceStatus/getPartitionStatus/getTaskStatus/getRunStatus/getNodeStatus/getStatus}；
 * 若均无法解析则快速失败抛出 {@link IllegalStateException}，避免状态静默损坏。
 * 事件到目标状态的映射在 {@code resolveToState} 中以 switch 表达式集中维护，
 * 未知事件保持原状态不变（NOOP 语义）。
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
      return null;
    }
  }

  private String normalizeEvent(String event) {
    return Texts.hasText(event) ? event.trim().toUpperCase() : "NOOP";
  }

  private String resolveToState(String fromState, String event) {
    return switch (event) {
      case "READY" -> "READY";
      case "START", "CLAIM", "RUN", "DISPATCH", "RETRYING" -> "RUNNING";
      case "SUCCESS", "SUCCEED", "COMPLETE", "FINISH" -> "SUCCESS";
      case "PARTIAL_FAILED" -> "PARTIAL_FAILED";
      case "FAIL", "FAILED", "ERROR", "REJECT" -> "FAILED";
      case "TERMINATE", "CANCEL" -> "TERMINATED";
      case "SKIP", "SKIPPED" -> "SKIPPED";
      case "NOOP" -> fromState;
      // A-3.3: 未知事件保留 NOOP 语义不变（向后兼容），但记 WARN 日志暴露拼写错误
      // （如 "SUCESS" vs "SUCCESS"）。生产环境检索此日志可快速定位状态机误用。
      default -> {
        log.warn(
            "state machine NOOP on unknown event: fromState={}, event={} — check for typo",
            fromState,
            event);
        yield fromState;
      }
    };
  }
}
