package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.example.batch.worker.dispatchs.config.DispatchCircuitBreakerProperties;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Per-channel failure counting: after {@link DispatchCircuitBreakerProperties#getFailureThreshold()} consecutive
 * failed dispatches, short-circuit until {@link DispatchCircuitBreakerProperties#getCooldownMillis()} elapses.
 */
@Component
public class DispatchChannelCircuitBreaker {

    private final DispatchCircuitBreakerProperties properties;
    private final ConcurrentHashMap<String, AtomicInteger> failures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> openUntil = new ConcurrentHashMap<>();

    public DispatchChannelCircuitBreaker(DispatchCircuitBreakerProperties properties) {
        this.properties = properties;
    }

    public boolean allow(String key) {
        if (!properties.isEnabled()) {
            return true;
        }
        Instant until = openUntil.get(key);
        if (until == null) {
            return true;
        }
        if (Instant.now().isBefore(until)) {
            return false;
        }
        openUntil.remove(key);
        failures.remove(key);
        return true;
    }

    public void recordSuccess(String key) {
        failures.remove(key);
        openUntil.remove(key);
    }

    public void recordFailure(String key) {
        if (!properties.isEnabled()) {
            return;
        }
        int n = failures.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        if (n >= properties.getFailureThreshold()) {
            openUntil.put(key, Instant.now().plusMillis(properties.getCooldownMillis()));
            failures.remove(key);
        }
    }

    /**
     * Count of channels currently short-circuited (cooldown not yet elapsed).
     */
    public int currentOpenCircuits() {
        Instant now = Instant.now();
        return (int) openUntil.entrySet().stream().filter(e -> now.isBefore(e.getValue())).count();
    }
}
