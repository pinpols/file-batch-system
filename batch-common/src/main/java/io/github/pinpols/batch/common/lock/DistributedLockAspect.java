package io.github.pinpols.batch.common.lock;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

/**
 * {@link DistributedLock} 切面。复用 ShedLock {@link LockingTaskExecutor},LockProvider 由 batch-common
 * AutoConfiguration 提供(JDBC 默认 / Redis 可覆盖)。
 *
 * <p>SpEL key 求值参考 AuditAspect 同款实现(ParameterNameDiscoverer + StandardEvaluationContext)。
 *
 * <p>本切面是 {@link DistributedLock} 的配套机制;该注解目前是预留未采纳能力(见其 Javadoc),故对 deprecation
 * 告警显式抑制——切面合法实现注解的运行时行为,不是误用。
 */
@Slf4j
@Aspect
@RequiredArgsConstructor
@SuppressWarnings("deprecation") // implements the reserved-but-unadopted @DistributedLock machinery
public class DistributedLockAspect {

  private final LockingTaskExecutor lockingTaskExecutor;
  private final ExpressionParser parser = new SpelExpressionParser();
  private final ParameterNameDiscoverer paramNames = new DefaultParameterNameDiscoverer();

  @Around("@annotation(io.github.pinpols.batch.common.lock.DistributedLock)")
  public Object around(ProceedingJoinPoint pjp) throws Throwable {
    MethodSignature signature = (MethodSignature) pjp.getSignature();
    Method method = signature.getMethod();
    DistributedLock ann = method.getAnnotation(DistributedLock.class);
    String lockKey = resolveKey(ann, method, pjp.getArgs(), signature);

    Instant now = BatchDateTimeSupport.utcNow();
    LockConfiguration config =
        new LockConfiguration(
            now,
            lockKey,
            Duration.ofSeconds(ann.leaseSeconds()),
            Duration.ofSeconds(ann.atLeastSeconds()));

    Throwable[] thrown = new Throwable[1];
    Object[] result = new Object[1];
    LockingTaskExecutor.TaskResult<Object> taskResult =
        lockingTaskExecutor.executeWithLock(
            (LockingTaskExecutor.TaskWithResult<Object>)
                () -> {
                  try {
                    result[0] = pjp.proceed();
                    return result[0];
                  } catch (Throwable t) {
                    thrown[0] = t;
                    return null;
                  }
                },
            config);

    if (thrown[0] != null) {
      throw thrown[0];
    }
    if (!taskResult.wasExecuted()) {
      if (ann.throwOnFailure()) {
        throw new DistributedLockAcquireException(lockKey);
      }
      log.debug("DistributedLock skipped (already held): {}", lockKey);
      return defaultReturn(method.getReturnType());
    }
    return result[0];
  }

  private String resolveKey(
      DistributedLock ann, Method method, Object[] args, MethodSignature signature) {
    String prefix =
        ann.prefix().isEmpty() ? method.getDeclaringClass().getSimpleName() : ann.prefix();
    if (ann.key().isEmpty()) {
      return prefix + ":" + method.getName();
    }
    try {
      StandardEvaluationContext ctx = new StandardEvaluationContext();
      String[] names = paramNames.getParameterNames(method);
      if (names != null) {
        for (int i = 0; i < names.length && i < args.length; i++) {
          ctx.setVariable(names[i], args[i]);
        }
      }
      Expression expr = parser.parseExpression(ann.key());
      Object value = expr.getValue(ctx);
      return prefix + ":" + (value == null ? "null" : value);
    } catch (Exception e) {
      // SpEL 求值失败用方法签名回退,避免误锁全局
      log.warn(
          "DistributedLock SpEL eval failed, falling back to signature key: method={}, key={},"
              + " error={}",
          method.getName(),
          ann.key(),
          e.getMessage());
      return prefix + ":" + method.getName();
    }
  }

  private Object defaultReturn(Class<?> type) {
    if (type == void.class || type == Void.class) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type.isPrimitive()) {
      return 0;
    }
    return null;
  }

  // SimpleLock 类型显式 import(IDE 不会标红),用于编译期校验 ShedLock SPI 存在
  @SuppressWarnings("unused")
  private static final Class<?> SHEDLOCK_API_GUARD = SimpleLock.class;
}
