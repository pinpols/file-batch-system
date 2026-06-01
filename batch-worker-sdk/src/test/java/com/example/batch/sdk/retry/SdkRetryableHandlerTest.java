package com.example.batch.sdk.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.sdk.handler.SdkAbstractTaskHandler;
import com.example.batch.sdk.handler.SdkRetryPolicy;
import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskResult;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A.4 声明式重试装饰器单测。delay 取 1ms 保持测试快。 */
class SdkRetryableHandlerTest {

  private static final SdkTaskContext CTX =
      new SdkTaskContext("t1", "job", "ti", 1L, "w1", Map.of(), Map.of());

  /** 裸 handler:前 N 次抛指定异常,第 N+1 次成功(用 RuntimeException 子类模拟匹配/不匹配)。 */
  @RetryOn(
      value = IllegalStateException.class,
      maxAttempts = 4,
      initialDelayMillis = 1,
      backoff = RetryOn.Backoff.FIXED)
  static class FlakyHandler implements SdkTaskHandler {
    final AtomicInteger calls = new AtomicInteger();
    final int failFirstN;
    final RuntimeException toThrow;

    FlakyHandler(int failFirstN, RuntimeException toThrow) {
      this.failFirstN = failFirstN;
      this.toThrow = toThrow;
    }

    @Override
    public String taskType() {
      return "flaky";
    }

    @Override
    public SdkTaskResult execute(SdkTaskContext ctx) {
      int n = calls.incrementAndGet();
      if (n <= failFirstN) {
        throw toThrow;
      }
      return SdkTaskResult.ok("ok after " + n);
    }
  }

  @Test
  @DisplayName("未标 @RetryOn → wrap 原样返回")
  void shouldReturnDelegate_whenNotAnnotated() {
    SdkTaskHandler plain =
        new SdkTaskHandler() {
          @Override
          public String taskType() {
            return "plain";
          }

          @Override
          public SdkTaskResult execute(SdkTaskContext ctx) {
            return SdkTaskResult.ok();
          }
        };
    assertThat(SdkRetryableHandler.wrap(plain)).isSameAs(plain);
  }

  @Test
  @DisplayName("前 2 次抛匹配异常、第 3 次成功 → 重试后成功,共 3 次调用")
  void shouldRetryAndSucceed_whenMatchingExceptionThenOk() {
    // arrange
    FlakyHandler handler = new FlakyHandler(2, new IllegalStateException("transient"));
    SdkTaskHandler wrapped = SdkRetryableHandler.wrap(handler);

    // act
    SdkTaskResult result = wrapped.execute(CTX);

    // assert
    assertThat(result.success()).isTrue();
    assertThat(result.message()).isEqualTo("ok after 3");
    assertThat(handler.calls).hasValue(3);
  }

  @Test
  @DisplayName("不匹配异常 → 不重试,原样透传抛出,仅 1 次调用")
  void shouldNotRetryAndRethrow_whenExceptionDoesNotMatch() {
    // arrange:抛 IllegalArgumentException(不在 value 内)
    FlakyHandler handler = new FlakyHandler(99, new IllegalArgumentException("nope"));
    SdkTaskHandler wrapped = SdkRetryableHandler.wrap(handler);

    // act + assert
    assertThatThrownBy(() -> wrapped.execute(CTX))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("nope");
    assertThat(handler.calls).hasValue(1);
  }

  @Test
  @DisplayName("一直抛匹配异常 → 达 maxAttempts 后透传最后异常,调用 maxAttempts 次")
  void shouldExhaustAndRethrow_whenAlwaysMatchingException() {
    // arrange:maxAttempts=4,全失败
    FlakyHandler handler = new FlakyHandler(99, new IllegalStateException("always"));
    SdkTaskHandler wrapped = SdkRetryableHandler.wrap(handler);

    // act + assert
    assertThatThrownBy(() -> wrapped.execute(CTX)).isInstanceOf(IllegalStateException.class);
    assertThat(handler.calls).hasValue(4);
  }

  /**
   * 模板基类 handler:doExecute 抛的异常被 {@link SdkAbstractTaskHandler} 吞成 fail 结果(error 挂在 result 上),
   * 验证装饰器按 {@code result.error()} 匹配重试路径(而非靠抛出)。
   */
  @RetryOn(value = IllegalStateException.class, maxAttempts = 3, initialDelayMillis = 1)
  static class TemplateFlakyHandler extends SdkAbstractTaskHandler {
    final AtomicInteger calls = new AtomicInteger();
    final int failFirstN;

    TemplateFlakyHandler(int failFirstN) {
      this.failFirstN = failFirstN;
    }

    @Override
    public String taskType() {
      return "template_flaky";
    }

    @Override
    protected SdkTaskResult doExecute(SdkTaskContext ctx) {
      int n = calls.incrementAndGet();
      if (n <= failFirstN) {
        throw new IllegalStateException("template transient");
      }
      return SdkTaskResult.ok("template ok " + n);
    }
  }

  @Test
  @DisplayName("模板 handler 把异常吞成 fail(error 挂 result)→ 按 result.error() 匹配重试")
  void shouldRetry_whenTemplateReturnsFailWithMatchingError() {
    // arrange:前 1 次失败,模板 catch 转 fail(error=IllegalStateException)
    TemplateFlakyHandler handler = new TemplateFlakyHandler(1);
    SdkTaskHandler wrapped = SdkRetryableHandler.wrap(handler);

    // act
    SdkTaskResult result = wrapped.execute(CTX);

    // assert
    assertThat(result.success()).isTrue();
    assertThat(handler.calls).hasValue(2);
  }

  /** 业务返回 fail 但无 Throwable(error()==null)的 handler;计数执行次数。 */
  @RetryOn(
      value = IllegalStateException.class,
      maxAttempts = 4,
      initialDelayMillis = 1,
      backoff = RetryOn.Backoff.FIXED)
  static class NoErrorFailHandler implements SdkTaskHandler {
    final AtomicInteger calls = new AtomicInteger();

    @Override
    public String taskType() {
      return "no_error_fail";
    }

    @Override
    public SdkTaskResult execute(SdkTaskContext ctx) {
      calls.incrementAndGet();
      return SdkTaskResult.fail("business says no"); // error() == null
    }
  }

  @Test
  @DisplayName("业务 fail 但 error()==null → isRetryable(null) 为 false,不重试,返该 fail 结果(仅 1 次调用)")
  void shouldNotRetry_whenFailResultHasNullError() {
    // arrange:fail("msg") 不挂 Throwable
    NoErrorFailHandler handler = new NoErrorFailHandler();
    SdkTaskHandler wrapped = SdkRetryableHandler.wrap(handler);

    // act
    SdkTaskResult result = wrapped.execute(CTX);

    // assert:failure==null → 不可重试 → 立即返回原 fail 结果,不进重试循环
    assertThat(result.success()).isFalse();
    assertThat(result.message()).isEqualTo("business says no");
    assertThat(handler.calls).hasValue(1);
  }

  @Test
  @DisplayName("EXPONENTIAL backoff 的 nextDelay 被 maxDelayMillis 截断(Math.min 封顶)")
  void shouldCapDelay_whenExponentialExceedsMaxDelay() {
    // arrange:initial=100ms,倍数 2,maxDelay=250ms
    SdkRetryPolicy policy =
        SdkRetryPolicy.exponential(10, Duration.ofMillis(100), Duration.ofMillis(250));

    // act + assert:100 -> 200(<250,不截) -> 400 截到 250 -> 500 仍截到 250
    Duration d1 = policy.nextDelay(Duration.ofMillis(100));
    assertThat(d1).isEqualTo(Duration.ofMillis(200));
    Duration d2 = policy.nextDelay(d1);
    assertThat(d2).isEqualTo(Duration.ofMillis(250)); // 400 封顶到 250
    Duration d3 = policy.nextDelay(d2);
    assertThat(d3).isEqualTo(Duration.ofMillis(250)); // 500 仍封顶
  }

  @Test
  @DisplayName("模板 handler 一直失败 → 达上限返回最后 fail 结果(不抛,保留 message)")
  void shouldReturnLastFailResult_whenTemplateAlwaysFails() {
    // arrange:全失败
    TemplateFlakyHandler handler = new TemplateFlakyHandler(99);
    SdkTaskHandler wrapped = SdkRetryableHandler.wrap(handler);

    // act
    SdkTaskResult result = wrapped.execute(CTX);

    // assert:模板把异常吞成 fail result,装饰器达上限后返回该 result(不重新抛)
    assertThat(result.success()).isFalse();
    assertThat(handler.calls).hasValue(3);
  }
}
