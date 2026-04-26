package com.example.batch.console.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注某个 Controller 方法 <b>必须</b> 带 {@code Idempotency-Key} 请求头。
 *
 * <p>{@link ConsoleIdempotencyInterceptor} 对所有 POST {@code /api/console/**} 的请求都会读取该头，
 * 有则去重、没有则放行（opt-in）。加 {@code @Idempotent} 的方法转为 <b>fail-close</b>：缺失 header 直接返回
 * 400，避免客户端遗漏导致"首次成功第二次又成功"的重复执行。
 *
 * <p>适用场景：副作用不可回滚的 POST（如手工补偿、强制下线、死信重放、触发重新执行等）。
 *
 * <p>参考：<a href="https://stripe.com/docs/api/idempotent_requests">Stripe Idempotent Requests</a>
 * 语义。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

  /** 当前只有"要求存在"一种语义；未来可扩展为"幂等策略"（如允许 N 分钟窗口 / 仅限指定方法等）。 */
  boolean required() default true;
}
