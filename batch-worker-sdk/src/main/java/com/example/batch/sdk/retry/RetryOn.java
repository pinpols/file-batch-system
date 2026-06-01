package com.example.batch.sdk.retry;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A.4 — 声明式重试。标在 {@link com.example.batch.sdk.task.SdkTaskHandler} 实现类上,声明「整个 handler 执行遇到
 * 指定异常时按退避策略重试」。
 *
 * <p><b>语义层级</b>:这是 handler **内**的整体执行重试(SDK 进程内),与平台 lease 超时重新派单是**两层**—— 平台重试针对整个 task 重新
 * CLAIM,本注解针对一次执行内的瞬时失败(如外部 HTTP/DB 抖动)就地重试,不回平台。
 *
 * <p><b>织入方式</b>:与 {@link com.example.batch.sdk.idempotent.Idempotent} 一致,用显式装饰器 {@link
 * SdkRetryableHandler#wrap}(SDK core 禁 Spring,不走 AOP),复用 {@link SdkRetryPolicy} 退避逻辑。
 *
 * <p><b>匹配规则</b>:抛出的异常 {@code isInstanceOf} {@link #value()} 任一类型才重试(子类匹配);不匹配的异常**原样透传**,
 * 不重试、不吞。达到 {@link #maxAttempts()} 仍失败 → 抛最后一次异常(由上层 handler 模板兜底转 fail)。
 *
 * <p>例:{@code @RetryOn(value = IOException.class, maxAttempts = 3, initialDelayMillis = 200)}。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RetryOn {

  /** 触发重试的异常类型(子类也匹配)。至少填一个。 */
  Class<? extends Throwable>[] value();

  /** 最大尝试次数(含首次),默认 3;必须 {@code >= 1}。 */
  int maxAttempts() default 3;

  /** 首次重试前的退避毫秒,默认 200。 */
  long initialDelayMillis() default 200L;

  /** 退避封顶毫秒,默认 5000;{@link Backoff#EXPONENTIAL} 时生效。 */
  long maxDelayMillis() default 5_000L;

  /** 退避策略,默认指数。 */
  Backoff backoff() default Backoff.EXPONENTIAL;

  /** 退避策略枚举。 */
  enum Backoff {
    /** 固定间隔(delay 恒为 initialDelayMillis)。 */
    FIXED,
    /** 指数退避(delay = initial * 2^n,封顶 maxDelayMillis)。 */
    EXPONENTIAL
  }
}
