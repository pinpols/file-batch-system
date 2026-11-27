package io.github.pinpols.batch.console.support.ratelimit;

import io.github.pinpols.batch.console.config.ConsoleRateLimitProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Redis 限流依赖的本地慢故障短路器。
 *
 * <p>限流在 Redis 故障时仍保持 fail-open，但连续故障后不再让每个请求同步等待 Redis。冷却期只允许一个
 * 请求探测恢复，其余请求直接放行。状态仅用于保护当前进程，不承担跨实例的安全决策。
 *
 * <p>状态机交给 Resilience4j：与 orchestrator 的限流熔断器和 batch-common 的
 * {@code DownstreamFallback} 使用同一套 OPEN→HALF_OPEN→CLOSED 语义与共享 {@link CircuitBreakerRegistry}，
 * 不再手写计数、冷却与单探针的 CAS。配置映射为
 * {@code slidingWindowSize = minimumNumberOfCalls = redisFailureThreshold} +
 * {@code failureRateThreshold = 100%}，等价于「连续 N 次失败才打开」。
 */
@Component("consoleRedisRateLimitCircuitBreaker")
public class RedisRateLimitCircuitBreaker {

    /** R4J 实例名；同时用于共享 registry 与 {@code resilience4j.circuitbreaker.*} 埋点。 */
    static final String CIRCUIT_NAME = "consoleRateLimitRedis";

    /** 状态机只关心调用结果，不统计耗时。 */
    private static final long CALL_NANOS = 0L;

    /** 计入失败的信号异常；复用同一个实例，避免故障期间反复构造。 */
    private static final RuntimeException FAILURE_SIGNAL = new RedisFailureSignal();

    private final CircuitBreaker circuitBreaker;
    private final Counter failureCounter;
    private final Counter openCounter;

    @Autowired
    public RedisRateLimitCircuitBreaker(
            ConsoleRateLimitProperties properties,
            ObjectProvider<CircuitBreakerRegistry> circuitBreakerRegistryProvider,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(
                properties,
                // 优先复用 R4J autoconfig 的共享 registry；窄上下文单测没有 autoconfig 时退回内置默认 registry。
                circuitBreakerRegistryProvider.getIfAvailable(CircuitBreakerRegistry::ofDefaults),
                meterRegistryProvider.getIfAvailable());
    }

    /** 直连构造：单测可注入真实 registry 与 meter registry。 */
    RedisRateLimitCircuitBreaker(
            ConsoleRateLimitProperties properties, CircuitBreakerRegistry registry, MeterRegistry meterRegistry) {
        this.circuitBreaker = registry.circuitBreaker(CIRCUIT_NAME, buildConfig(properties));
        this.failureCounter = meterRegistry == null
                ? null
                : Counter.builder("batch.console.rate_limit.redis.failure").register(meterRegistry);
        this.openCounter = meterRegistry == null
                ? null
                : Counter.builder("batch.console.rate_limit.redis.circuit.open").register(meterRegistry);
        if (openCounter != null) {
            this.circuitBreaker.getEventPublisher().onStateTransition(event -> {
                if (event.getStateTransition().getToState() == CircuitBreaker.State.OPEN) {
                    openCounter.increment();
                }
            });
        }
        if (meterRegistry != null) {
            meterRegistry.gauge(
                    "batch.console.rate_limit.redis.circuit.opened", this, breaker -> breaker.isOpen() ? 1.0 : 0.0);
        }
    }

    /** 测试或无 Spring 容器场景使用的构造器，不注册指标。 */
    public static RedisRateLimitCircuitBreaker forTesting(ConsoleRateLimitProperties properties) {
        return new RedisRateLimitCircuitBreaker(properties, CircuitBreakerRegistry.ofDefaults(), null);
    }

    /** 单测用真实 registry，便于推进半开状态与核对事件。 */
    static RedisRateLimitCircuitBreaker forTesting(
            ConsoleRateLimitProperties properties, CircuitBreakerRegistry registry) {
        return new RedisRateLimitCircuitBreaker(properties, registry, null);
    }

    /** 当前请求是否可以访问 Redis；冷却期只放行一个探测请求。 */
    public boolean allowRedisCall() {
        return circuitBreaker.tryAcquirePermission();
    }

    public void recordSuccess() {
        circuitBreaker.onSuccess(CALL_NANOS, TimeUnit.NANOSECONDS);
    }

    public void recordFailure() {
        if (failureCounter != null) {
            failureCounter.increment();
        }
        circuitBreaker.onError(CALL_NANOS, TimeUnit.NANOSECONDS, FAILURE_SIGNAL);
    }

    public boolean isOpen() {
        CircuitBreaker.State state = circuitBreaker.getState();
        return state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN;
    }

    /** 不产出栈帧的信号异常：熔断只消费它的类型，栈信息对排障没有增益。 */
    private static final class RedisFailureSignal extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private RedisFailureSignal() {
            super("redis rate limit failure");
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    private static CircuitBreakerConfig buildConfig(ConsoleRateLimitProperties properties) {
        int threshold = Math.max(1, properties.getRedisFailureThreshold());
        long openSeconds = Math.max(1L, properties.getRedisCircuitOpenSeconds());
        return CircuitBreakerConfig.custom()
                // COUNT_BASED + size=minCalls=threshold + 100% 阈值 = 「连续 threshold 次全失败才打开」。
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(threshold)
                .minimumNumberOfCalls(threshold)
                .failureRateThreshold(100.0f)
                .waitDurationInOpenState(Duration.ofSeconds(openSeconds))
                .permittedNumberOfCallsInHalfOpenState(1)
                // 不后台自动转 HALF_OPEN：冷却到期后由下一个真实请求承载探测。
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .build();
    }
}
