package com.example.batch.orchestrator.infrastructure;

import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

/**
 * Orchestrator 优雅停机：ContextClosedEvent 时设置 draining 标志，
 * 后续调度/派发循环检查此标志以停止接受新请求。
 * <p>OutboxPollScheduler 已通过 {@code @PreDestroy} 停止 executor，此处仅提供全局 draining 状态。
 */
@Slf4j
@Component
public class OrchestratorGracefulShutdown implements ApplicationListener<ContextClosedEvent> {

    private static final AtomicBoolean DRAINING = new AtomicBoolean(false);

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        log.info("Orchestrator graceful shutdown initiated — setting draining flag");
        DRAINING.set(true);
    }

    /** 是否正在 drain，供调度/派发循环检查。 */
    public static boolean isDraining() {
        return DRAINING.get();
    }
}
