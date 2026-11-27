package io.github.pinpols.batch.console.support.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("瞬时重试执行器: 尝试次数、退避序列、耗尽结果与中断语义")
class BurstRetryExecutorTest {

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    @DisplayName("首次成功: 只尝试一次，不退避")
    void shouldStopAfterFirstAttempt_whenFirstAttemptSucceeds() {
        List<Long> slept = new ArrayList<>();
        BurstRetryExecutor executor = new BurstRetryExecutor(3, 250L, slept::add);
        AtomicInteger calls = new AtomicInteger();

        BurstRetryExecutor.Outcome<String> outcome =
                executor.execute(() -> calls.incrementAndGet() == 1 ? "ok" : "fail", "ok"::equals);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.attempts()).isEqualTo(1);
        assertThat(outcome.result()).isEqualTo("ok");
        assertThat(calls).hasValue(1);
        assertThat(slept).isEmpty();
    }

    @Test
    @DisplayName("重试后成功: 退避按 250、500 翻倍，返回实际尝试次数")
    void shouldDoubleBackoffBetweenAttempts_untilSuccess() {
        List<Long> slept = new ArrayList<>();
        BurstRetryExecutor executor = new BurstRetryExecutor(3, 250L, slept::add);
        List<String> results = List.of("fail", "fail", "ok");
        AtomicInteger index = new AtomicInteger();

        BurstRetryExecutor.Outcome<String> outcome =
                executor.execute(() -> results.get(index.getAndIncrement()), "ok"::equals);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.attempts()).isEqualTo(3);
        assertThat(slept).containsExactly(250L, 500L);
    }

    @Test
    @DisplayName("全部失败: 尝试次数打满，返回最后一次结果")
    void shouldReportLastResultAndAttempts_whenAllAttemptsFail() {
        List<Long> slept = new ArrayList<>();
        BurstRetryExecutor executor = new BurstRetryExecutor(3, 250L, slept::add);
        AtomicInteger calls = new AtomicInteger();

        BurstRetryExecutor.Outcome<Integer> outcome =
                executor.execute(() -> calls.incrementAndGet(), attempt -> attempt > 3);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.attempts()).isEqualTo(3);
        assertThat(outcome.result()).isEqualTo(3);
        assertThat(calls).hasValue(3);
        assertThat(slept).containsExactly(250L, 500L);
    }

    @Test
    @DisplayName("退避被中断: 恢复中断标志并继续后续尝试，与原实现一致")
    void shouldKeepInterruptFlagAndContinue_whenBackoffInterrupted() {
        List<Long> slept = new ArrayList<>();
        BurstRetryExecutor executor = new BurstRetryExecutor(2, 250L, millis -> {
            slept.add(millis);
            Thread.currentThread().interrupt();
        });
        AtomicInteger calls = new AtomicInteger();

        BurstRetryExecutor.Outcome<Integer> outcome =
                executor.execute(() -> calls.incrementAndGet(), attempt -> attempt > 1);

        assertThat(outcome.attempts()).isEqualTo(2);
        assertThat(outcome.success()).isTrue();
        assertThat(slept).containsExactly(250L);
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    @DisplayName("非法配置: 尝试次数小于 1 直接拒绝")
    void shouldRejectNonPositiveMaxAttempts() {
        assertThatIllegalArgumentException().isThrownBy(() -> new BurstRetryExecutor(0, 250L));
    }
}
