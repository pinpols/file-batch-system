package com.example.batch.console.support.cache;

import com.example.batch.console.infrastructure.ConsoleConfigCacheInvalidationService;
import java.lang.reflect.Method;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.stereotype.Component;

/**
 * R-4.8：@{@link InvalidatesConsoleCache} 注解的 AOP 切面。
 *
 * <p>方法<b>成功返回</b>后（抛异常不触发 evict，避免异常回滚后误清有效缓存）， 根据 target + SpEL 表达式解析 tenantId / code，调 {@link
 * ConsoleConfigCacheInvalidationService} 对应的 evict 方法。evict 内部已是 afterCommit 注册，事务语义保持一致。
 */
@Slf4j
@Aspect
@Component
public class ConsoleCacheInvalidationAspect {

  private final ConsoleConfigCacheInvalidationService invalidationService;
  private final ExpressionParser spelParser = new SpelExpressionParser();
  private final ParameterNameDiscoverer paramNames = new DefaultParameterNameDiscoverer();

  public ConsoleCacheInvalidationAspect(ConsoleConfigCacheInvalidationService invalidationService) {
    this.invalidationService = invalidationService;
  }

  @Around("@annotation(annotation)")
  public Object aroundInvalidates(ProceedingJoinPoint pjp, InvalidatesConsoleCache annotation)
      throws Throwable {
    Object result = pjp.proceed();
    try {
      dispatchEvict(pjp, annotation);
    } catch (RuntimeException ex) {
      // evict 失败不应影响业务语义；记 warn 继续。下次触发还会重清。
      log.warn(
          "console cache invalidation aspect failed (method still succeeded): target={}, cause={}",
          annotation.target(),
          ex.getMessage());
    }
    return result;
  }

  private void dispatchEvict(ProceedingJoinPoint pjp, InvalidatesConsoleCache annotation) {
    MethodSignature sig = (MethodSignature) pjp.getSignature();
    Method method = sig.getMethod();
    Object[] args = pjp.getArgs();
    String tenantId = evaluateSpel(annotation.tenantSpel(), method, args);
    String code = evaluateSpel(annotation.codeSpel(), method, args);

    // P2：SpEL 解析失败返回 null 时不能继续——之前 configKey(null,...) 会把
    // key 退化为 "config:_:*:*" 模式，evictByPattern 会扫掉**所有租户**的缓存。
    // 这里显式拒绝：tenantId 必须非空；缺 code 的 target（ALL_* / META_OPTIONS）另判。
    if (tenantId == null || tenantId.isBlank()) {
      log.warn(
          "cache invalidation skipped: tenantSpel='{}' resolved to null/blank on method {}"
              + " — fix the annotation or ensure the argument is non-null",
          annotation.tenantSpel(),
          method.getName());
      return;
    }

    switch (annotation.target()) {
      case JOB_DEFINITION -> invalidationService.evictJobDefinition(tenantId, code);
      case ALL_JOB_DEFINITIONS_BY_TENANT -> invalidationService.evictAllJobDefinitions(tenantId);
      case WORKFLOW_DEFINITION -> invalidationService.evictWorkflowDefinition(tenantId, code);
      case BUSINESS_CALENDAR -> invalidationService.evictBusinessCalendar(tenantId, code);
      case BATCH_WINDOW -> invalidationService.evictBatchWindow(tenantId, code);
      case TENANT_QUOTA_POLICIES -> invalidationService.evictQuotaPolicies(tenantId);
      case META_OPTIONS -> invalidationService.evictMetaOptions(tenantId);
    }
  }

  private String evaluateSpel(String spel, Method method, Object[] args) {
    if (spel == null || spel.isBlank()) {
      return null;
    }
    StandardEvaluationContext ctx = new StandardEvaluationContext();
    // 按 index 绑定
    for (int i = 0; i < args.length; i++) {
      ctx.setVariable("a" + i, args[i]);
    }
    // 按参数名绑定（需要编译带 -parameters 或运行期可用；否则兜底用 arg0/arg1）
    String[] names = paramNames.getParameterNames(method);
    if (names != null) {
      for (int i = 0; i < names.length && i < args.length; i++) {
        if (names[i] != null) {
          ctx.setVariable(names[i], args[i]);
        }
      }
    }
    Expression exp = spelParser.parseExpression(spel);
    Object v = exp.getValue(ctx);
    return v == null ? null : String.valueOf(v);
  }
}
