package com.example.batch.console.config;

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
 *
 * <p><b>⚠ 状态(截至 1.1.0):预留能力,零生产使用。</b>注解 + 切面 + 路由数据源三件齐全,且读写分离本身是 active 的,但**全仓没有任何方法标注它**——而且
 * force-primary 这条路<b>只有本切面会去 set</b>,所以目前 实际从不触发。这意味着 console 的只读查询全走默认路由(写走主、只读走副本),没有显式强制走主的点。
 *
 * <p>故标记为 {@link Deprecated}:**不是计划移除**,而是"读写分离的安全阀,备而未用"的信号。它关联的是 active 功能(读写分离),将来真出现
 * read-after-write 危险(同请求刚写完要读回最新值、主从延迟敏感的状态 推进)时,这是现成的阀门——届时给那个方法加 {@code @RouteToPrimary} 并在 PR
 * 里去掉本 {@code @Deprecated}。 删它等于砍掉读写分离的一个安全阀,故保留而非删除。
 *
 * @since 1.0.0
 * @deprecated 预留未采纳:定义完整、读写分离 active,但零方法标注 → force-primary 从不触发。保留作安全阀, 出现 read-after-write
 *     场景时采纳并移除本注解。
 */
@Deprecated
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RouteToPrimary {}
