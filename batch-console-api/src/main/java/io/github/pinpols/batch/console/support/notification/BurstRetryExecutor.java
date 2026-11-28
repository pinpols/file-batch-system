package io.github.pinpols.batch.console.support.notification;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 进程内瞬时重试：固定尝试次数 + 指数退避（起点后逐次翻倍）。
 *
 * <p>只负责「尝试 → 判定 → 退避」。成功/失败后的投递日志、审计与告警仍由调用方在拿到 {@link Outcome} 后处理，
 * 这样 webhook 与 subscription-rule 两条链路共用退避口径，又不会把各自的持久化语义塞进工具。
 *
 * <p>之所以不直接套 {@code resilience4j-retry}：三个调用点的判定对象是投递结果对象而非异常，
 * 且每次尝试后都要写不同的投递日志；用返回值表达的窄接口比「信号异常 + 事件监听」更少假设。
 *
 * <p>中断语义与原实现一致：退避被打断时记录并恢复中断标志，退避立即返回，后续尝试仍会执行。
 */
public final class BurstRetryExecutor {

    private final int maxAttempts;
    private final long initialBackoffMillis;
    private final LongConsumer sleeper;

    public BurstRetryExecutor(int maxAttempts, long initialBackoffMillis) {
        this(maxAttempts, initialBackoffMillis, BurstRetryExecutor::sleepQuietly);
    }

    BurstRetryExecutor(int maxAttempts, long initialBackoffMillis, LongConsumer sleeper) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoffMillis = Math.max(0L, initialBackoffMillis);
        this.sleeper = sleeper;
    }

    /** 按退避序列重复尝试，直到成功或耗尽次数；返回实际尝试次数与最后一次结果。 */
    public <T> Outcome<T> execute(Supplier<T> attempt, Predicate<T> success) {
        long backoffMillis = initialBackoffMillis;
        T lastResult = null;
        for (int attemptNo = 1; attemptNo <= maxAttempts; attemptNo++) {
            lastResult = attempt.get();
            if (success.test(lastResult)) {
                return new Outcome<>(attemptNo, lastResult, true);
            }
            if (attemptNo < maxAttempts) {
                sleeper.accept(backoffMillis);
                backoffMillis *= 2;
            }
        }
        return new Outcome<>(maxAttempts, lastResult, false);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            SwallowedExceptionLogger.info(BurstRetryExecutor.class, "catch:InterruptedException", ex);
            Thread.currentThread().interrupt();
        }
    }

    /** 一次重试链的结果：实际尝试次数、最后一次结果与是否成功。 */
    public record Outcome<T>(int attempts, T result, boolean success) {}
}
