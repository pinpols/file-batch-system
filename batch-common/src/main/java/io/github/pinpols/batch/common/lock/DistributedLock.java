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
 *
 * <p><b>⚠ 状态(截至 1.1.0):预留能力,零生产使用。</b>注解 + 切面({@link DistributedLockAspect})+ 自动配置({@link
 * BatchDistributedLockAutoConfiguration})三件齐全,但**全仓没有任何业务方法标注它**。
 * 业务级并发保护目前由其它手段承担,无人填补"业务方法级分布式锁"这个空白:
 *
 * <ul>
 *   <li>调度去重 / leader 选举 → ShedLock 原生 {@code @SchedulerLock}(48+ 处,活跃)
 *   <li>状态机并发推进 → PG 乐观锁 {@code version} CAS(20+ 处)+ {@code SELECT ... FOR UPDATE}
 *   <li>幂等 → {@code UNIQUE} 约束 + {@code ON CONFLICT}(56 处)
 *   <li>限流 / 派发热路 → Redis Lua 原子脚本
 * </ul>
 *
 * <p>故标记为 {@link Deprecated}:**不是计划移除**,而是提示"这是预留未采纳能力,默认不要伸手用"。
 * 真要给某个业务方法上分布式锁前,先确认上述既有手段确实覆盖不了(ShedLock 只锁 {@code @Scheduled}, 业务路径确有真实需求时本注解是现成的),并在采纳的 PR 里去掉本
 * {@code @Deprecated}。
 *
 * @since 1.0.0
 * @deprecated 预留未采纳:定义完整但零生产使用。保留备用,采纳前先评估既有锁手段(@SchedulerLock / version CAS / ON
 *     CONFLICT)是否已够;采纳时移除本注解。
 */
@Deprecated
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
