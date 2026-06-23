package io.github.pinpols.batch.common.lock;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 业务方法级分布式锁注解,基于 ShedLock {@code LockingTaskExecutor} 实现。
 *
 * <p>ShedLock 自带 {@code @SchedulerLock} 只能加在 {@code @Scheduled} 任务上;业务路径(下单 / 防并发 RERUN /
 * 资源占用)拿不到。本注解填这个空白,**无新依赖**,复用既有 LockProvider(JDBC 默认,orchestrator 可覆盖为 Redis)。
 *
 * <p>用法:
 *
 * <pre>{@code
 * @DistributedLock(key = "'tenant:' + #tenantId + ':job:' + #jobCode", leaseSeconds = 60)
 * public void claimJob(String tenantId, String jobCode) { ... }
 * }</pre>
 *
 * <p>key 支持 SpEL,以方法参数名为根(`#tenantId` 等);求值失败用方法签名 fallback 防止误锁。
 *
 * <p>抢锁失败时:
 *
 * <ul>
 *   <li>{@link #throwOnFailure} = true(默认):抛 {@link DistributedLockAcquireException},调用方 catch 回退
 *   <li>{@link #throwOnFailure} = false:silent 跳过(返回 void / null),适合「重复执行也无害」的幂等业务
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {

  /** 锁 key 的 SpEL 表达式;以方法参数为根。空字符串 = 使用方法签名(类名#方法名)作 key。 */
  String key() default "";

  /** key 前缀,避免不同业务域 key 撞车。默认按方法所在类的简单名。 */
  String prefix() default "";

  /** 持有锁的最大时长(秒);超时自动释放,防止业务卡住永久持锁。默认 30 秒。 */
  long leaseSeconds() default 30;

  /** 至少持有锁的时长(秒);ShedLock 防止任务还没真做完锁就被另一节点抢走。默认 0(等于实际方法执行时长)。 */
  long atLeastSeconds() default 0;

  /** 抢锁失败时是否抛异常。false = 静默跳过(仅适合幂等任务)。 */
  boolean throwOnFailure() default true;
}
