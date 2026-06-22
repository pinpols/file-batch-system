package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.worker.dispatchs.config.DispatchCircuitBreakerProperties;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * 按渠道维度统计失败次数：连续失败达到 {@link DispatchCircuitBreakerProperties#getFailureThreshold()} 次后， 在 {@link
 * DispatchCircuitBreakerProperties#getCooldownMillis()} 冷却期内熔断该渠道。
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
    if (BatchDateTimeSupport.utcNow().isBefore(until)) {
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
    int threshold = properties.getFailureThreshold();
    // 原子地累加并在达阈值时清零：compute 的 remapping function 在该 key 的 bin 锁内执行，
    // 与并发的 compute/merge 互斥，消除"incrementAndGet 后再 remove"两步之间的 check-then-act
    // 窗口（旧实现里 remove 与并发 computeIfAbsent 之间会丢失失败计数、熔断略延）。
    // 返回 null 表示从 map 删除该 entry（达阈值后清零计数）；否则保留累加后的计数器。
    AtomicInteger remaining =
        failures.compute(
            key,
            (k, current) -> {
              AtomicInteger counter = current == null ? new AtomicInteger() : current;
              if (counter.incrementAndGet() >= threshold) {
                return null;
              }
              return counter;
            });
    if (remaining == null) {
      openUntil.put(key, BatchDateTimeSupport.utcNow().plusMillis(properties.getCooldownMillis()));
    }
  }

  /** 返回当前处于熔断（冷却期未结束）状态的渠道数量。 */
  public int currentOpenCircuits() {
    Instant now = BatchDateTimeSupport.utcNow();
    return (int) openUntil.entrySet().stream().filter(e -> now.isBefore(e.getValue())).count();
  }
}
