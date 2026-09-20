package io.github.pinpols.batch.orchestrator.infrastructure.statemachine;

import io.github.pinpols.batch.common.persistence.Stateful;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.domain.statemachine.LifecycleEventMapper;
import io.github.pinpols.batch.orchestrator.domain.statemachine.LifecycleStatusCatalog;
import io.github.pinpols.batch.orchestrator.domain.statemachine.StateTransition;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 默认生命周期事件映射器。
 *
 * <p>本组件只把业务事件翻译成候选状态，不冒充拥有全部实体迁移矩阵的通用状态机。调用方持久化时必须继续携带
 * expected-status CAS；具有完整显式迁移矩阵的领域（例如文件生命周期）使用自己的类型化状态机。
 */
@Slf4j
@Component
public class DefaultLifecycleEventMapper<T> implements LifecycleEventMapper<T> {

  private static final Set<String> TERMINAL_STATES = LifecycleStatusCatalog.allTerminalStates();

  @Override
  public StateTransition map(T target, String event) {
    String fromState = resolveState(target);
    String normalizedEvent = normalizeEvent(event);
    return new StateTransition(
        fromState, normalizedEvent, resolveToState(fromState, normalizedEvent));
  }

  private String resolveState(T target) {
    if (target instanceof String status && Texts.hasText(status)) {
      return status;
    }
    if (target instanceof Enum<?> enumValue) {
      return enumValue.name();
    }
    if (target instanceof Stateful stateful && Texts.hasText(stateful.getStatus())) {
      return stateful.getStatus();
    }
    String targetType = target == null ? "null" : target.getClass().getName();
    throw new IllegalArgumentException(
        "Cannot resolve lifecycle status from " + targetType + ": use String, Enum or Stateful");
  }

  private String normalizeEvent(String event) {
    return Texts.hasText(event) ? event.trim().toUpperCase(Locale.ROOT) : "NOOP";
  }

  private String resolveToState(String fromState, String event) {
    String candidate =
        switch (event) {
          case "READY" -> "READY";
          case "START", "CLAIM", "RUN", "DISPATCH", "RETRYING", "RUNNING" -> "RUNNING";
          case "SUCCESS", "SUCCEED", "COMPLETE", "FINISH" -> "SUCCESS";
          case "SUCCESS_DRY_RUN" -> "SUCCESS_DRY_RUN";
          case "PARTIAL_FAILED" -> "PARTIAL_FAILED";
          case "FAIL", "FAILED", "ERROR", "REJECT" -> "FAILED";
          case "FAILED_DRY_RUN" -> "FAILED_DRY_RUN";
          case "TERMINATE", "CANCEL", "TERMINATED", "CANCELLED" -> "TERMINATED";
          case "SKIP", "SKIPPED" -> "SKIPPED";
          case "WAITING", "CREATED", "PENDING", "NOOP" -> fromState;
          default -> resolveSelfTransitionOrFail(fromState, event);
        };
    return guardTerminal(fromState, event, candidate);
  }

  private String resolveSelfTransitionOrFail(String fromState, String event) {
    if (fromState.equalsIgnoreCase(event)) {
      return fromState;
    }
    throw new IllegalArgumentException(
        "Unsupported lifecycle event '" + event + "' from state '" + fromState + "'");
  }

  private String guardTerminal(String fromState, String event, String candidate) {
    if (!TERMINAL_STATES.contains(fromState) || candidate.equals(fromState)) {
      return candidate;
    }
    log.warn(
        "lifecycle mapper refuses transition from terminal: fromState={}, event={},"
            + " refusedTarget={}",
        fromState,
        event,
        candidate);
    return fromState;
  }
}
