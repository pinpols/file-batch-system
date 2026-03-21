package com.example.batch.orchestrator.infrastructure.statemachine;

import com.example.batch.orchestrator.domain.statemachine.StateMachine;
import com.example.batch.orchestrator.domain.statemachine.StateTransition;
import java.lang.reflect.Method;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DefaultStateMachine<T> implements StateMachine<T> {

    @Override
    public StateTransition transition(T target, String event) {
        String fromState = resolveState(target);
        String normalizedEvent = normalizeEvent(event);
        return new StateTransition(fromState, normalizedEvent, resolveToState(fromState, normalizedEvent));
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
        for (String methodName : List.of(
                "getInstanceStatus",
                "getPartitionStatus",
                "getTaskStatus",
                "getRunStatus",
                "getNodeStatus",
                "getStatus")) {
            String status = invokeStringGetter(target, methodName);
            if (StringUtils.hasText(status)) {
                return status;
            }
        }
        return target.getClass().getSimpleName().toUpperCase();
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
        return StringUtils.hasText(event) ? event.trim().toUpperCase() : "NOOP";
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
            default -> fromState;
        };
    }
}
