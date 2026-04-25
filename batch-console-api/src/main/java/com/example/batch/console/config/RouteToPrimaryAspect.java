package com.example.batch.console.config;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@link RouteToPrimary} 注解切面：方法进入时 set ThreadLocal hint=force-primary，finally 还原。
 *
 * <p>{@link Order} 设为 {@code Ordered.HIGHEST_PRECEDENCE + 10}，**优先于** Spring 的
 * {@code TransactionInterceptor}（默认 {@code Ordered.LOWEST_PRECEDENCE}）。这样 ThreadLocal hint
 * 在 {@code @Transactional} 真正开事务前已经设好，{@link ReadReplicaRoutingDataSource}
 * 在 {@code determineCurrentLookupKey} 第一时间就能读到。
 *
 * <p>仅 {@code batch.console.read-replica.enabled=true} 时生效，未启用时切面不进入应用上下文，
 * 注解变成纯文档（保持代码可读性，不引入运行时开销）。
 */
@Aspect
@Component
@ConditionalOnProperty(name = "batch.console.read-replica.enabled", havingValue = "true")
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10)
public class RouteToPrimaryAspect {

  @Around("@annotation(com.example.batch.console.config.RouteToPrimary)")
  public Object wrap(ProceedingJoinPoint pjp) throws Throwable {
    Boolean prev = RoutingHints.enterForcePrimary();
    try {
      return pjp.proceed();
    } finally {
      RoutingHints.restore(prev);
    }
  }
}
