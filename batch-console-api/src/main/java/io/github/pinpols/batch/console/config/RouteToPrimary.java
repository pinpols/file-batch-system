package io.github.pinpols.batch.console.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 强制本方法的查询走主库（绕过 readOnly→replica 默认路由）。
 *
 * <p>典型场景：
 *
 * <ul>
 *   <li>read-after-write：刚 commit 一个写事务，紧接着同一个 web 请求里要读回最新值
 *   <li>跨主从延迟敏感的 ops 操作（如审批流转、状态机推进）
 * </ul>
 *
 * <p>用法：方法上加 {@code @RouteToPrimary}（与 {@code @Transactional(readOnly = true)} 不冲突，本注解优先）。
 *
 * <p>实现上由 {@link RouteToPrimaryAspect} 切面把 {@link RoutingHints} ThreadLocal 设为 force-primary，
 * {@link ReadReplicaRoutingDataSource} 在 {@code determineCurrentLookupKey} 检测到 hint 后返回 PRIMARY。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RouteToPrimary {}
