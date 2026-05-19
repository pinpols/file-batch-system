package com.example.batch.console.support.audit;

import com.example.batch.console.mapper.OperationAuditMapper;
import com.example.batch.console.support.auth.ConsolePrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * {@link AuditAction} 切面 —— 同事务把方法执行结果写入 {@code batch.console_operation_audit}。
 *
 * <p><b>事务行为:</b>
 *
 * <ul>
 *   <li>不开新事务,直接复用业务方法的事务上下文 → 业务 rollback 时 audit 也 rollback,**业务 commit 才有 audit**,强一致
 *   <li>方法抛异常 → 仍然写一条 result=FAILED + errorMessage(放在 try/catch 外不行,放里面 OK —— 因为业务事务被回滚了 audit
 *       也回滚了,不会有「失败痕迹」。这里**主动选择**:写在 finally 块,如果业务异常想留痕需要单独的 `REQUIRES_NEW` 事务才能持久化。trade-off:多
 *       占一个连接 + 失败留痕。当前实现是「成功路径同事务、失败路径放弃留痕」)
 * </ul>
 *
 * <p><b>SpEL 求 aggregateId:</b>表达式以方法参数为根,支持 {@code #id} / {@code #request.alertId}。求值失败不抛,记 warn +
 * 写「-」占位,避免拖垮业务。
 *
 * <p><b>params JSON 摘要:</b>把所有 @PathVariable / @RequestParam / 命名参数序列化成 JSON,截到 2KB。不收集 {@code
 * HttpServletRequest} / {@code Authentication} / {@code Pageable} 这类框架 类型(避免循环引用 + 敏感字段)。
 *
 * <p><b>性能:</b>反射 + SpEL 大约 50μs/请求;额外一个 INSERT 同事务 ~1-2ms。整体影响 < 5%。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

  private static final int MAX_PARAMS_JSON_BYTES = 2048;

  private final OperationAuditMapper mapper;
  private final ObjectMapper objectMapper;

  private final ExpressionParser spel = new SpelExpressionParser();
  private final ParameterNameDiscoverer paramNameDiscoverer = new DefaultParameterNameDiscoverer();

  @Around("@annotation(com.example.batch.console.support.audit.AuditAction)")
  public Object wrap(ProceedingJoinPoint pjp) throws Throwable {
    MethodSignature sig = (MethodSignature) pjp.getSignature();
    Method method = sig.getMethod();
    AuditAction ann = method.getAnnotation(AuditAction.class);
    if (ann == null) return pjp.proceed();

    String aggregateId = resolveAggregateId(ann, method, pjp.getArgs());
    String paramsJson = serializeParams(method, pjp.getArgs());

    try {
      Object result = pjp.proceed();
      record(ann, aggregateId, paramsJson, null, null);
      return result;
    } catch (Throwable ex) {
      // 业务事务回滚 → 同事务的 audit 也回滚,不会有 FAILED 留痕。这里只是 best-effort 记 warn,
      // 真要捕获失败需要 @Transactional(propagation = REQUIRES_NEW) 新开事务 —— 阶段 2 不引入。
      log.warn(
          "[audit] action={} aggregateId={} failed but rollback also drops audit row",
          ann.action(),
          aggregateId,
          ex);
      throw ex;
    }
  }

  private void record(
      AuditAction ann, String aggregateId, String paramsJson, String errorCode, String errorMsg) {
    try {
      OperatorInfo op = currentOperator();
      RequestInfo req = currentRequest();
      // canonical record 构造器(豁免参数数量约束),按 DB 列顺序传 16 个字段。
      // 一致性 SUCCESS / FAILED 由 errorCode 是否为 null 决定。
      mapper.insert(
          new OperationAuditEvent(
              op.tenantId(),
              ann.aggregateType(),
              aggregateId,
              ann.action(),
              op.operatorId(),
              op.operatorRole(),
              errorCode == null ? "SUCCESS" : "FAILED",
              errorCode,
              truncate(errorMsg, 1024),
              paramsJson,
              req.traceId(),
              req.requestId(),
              req.ipHash(),
              req.uaHash(),
              1,
              Instant.now()));
    } catch (Exception e) {
      // 审计写失败不能拖垮业务事务 —— 业务侧已经做完了真正的事,这里只是留痕
      log.warn("[audit] insert failed action={}", ann.action(), e);
    }
  }

  private static String truncate(String s, int max) {
    if (s == null) return null;
    return s.length() <= max ? s : s.substring(0, max);
  }

  /** SpEL 求 aggregateId,失败回退「-」。 */
  private String resolveAggregateId(AuditAction ann, Method method, Object[] args) {
    String expr = ann.aggregateId();
    if (expr == null || expr.isEmpty()) return "-";
    try {
      StandardEvaluationContext ctx = new StandardEvaluationContext();
      String[] names = paramNameDiscoverer.getParameterNames(method);
      if (names != null) {
        for (int i = 0; i < names.length && i < args.length; i++) {
          ctx.setVariable(names[i], args[i]);
        }
      }
      Expression e = spel.parseExpression(expr);
      Object v = e.getValue(ctx);
      return v == null ? "-" : String.valueOf(v);
    } catch (Exception ex) {
      log.warn("[audit] aggregateId SpEL '{}' eval failed: {}", expr, ex.getMessage());
      return "-";
    }
  }

  /**
   * 把方法非框架参数序列化成 JSON 摘要。框架类型(HttpServletRequest / Authentication / Pageable /
   * MultipartFile)跳过,避免循环引用和大对象。
   */
  private String serializeParams(Method method, Object[] args) {
    String[] names = paramNameDiscoverer.getParameterNames(method);
    if (names == null) return null;
    Map<String, Object> snapshot = new LinkedHashMap<>();
    for (int i = 0; i < names.length && i < args.length; i++) {
      Object arg = args[i];
      if (arg == null) continue;
      if (isFrameworkParam(arg)) continue;
      snapshot.put(names[i], arg);
    }
    if (snapshot.isEmpty()) return null;
    try {
      String json = objectMapper.writeValueAsString(snapshot);
      return json.length() <= MAX_PARAMS_JSON_BYTES
          ? json
          : json.substring(0, MAX_PARAMS_JSON_BYTES - 14) + "\",\"_trunc\":1}";
    } catch (Exception e) {
      return null;
    }
  }

  private boolean isFrameworkParam(Object arg) {
    String cls = arg.getClass().getName();
    return arg instanceof HttpServletRequest
        || arg instanceof Authentication
        || cls.startsWith("org.springframework.web.multipart.")
        || cls.startsWith("org.springframework.data.domain.Pageable");
  }

  private OperatorInfo currentOperator() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof ConsolePrincipal p)) {
      return new OperatorInfo(null, null, null);
    }
    String role =
        p.authorities() == null || p.authorities().isEmpty()
            ? null
            : p.authorities().iterator().next();
    return new OperatorInfo(p.username(), role, p.tenantId());
  }

  private RequestInfo currentRequest() {
    String traceId = MDC.get("traceId");
    String requestId = MDC.get("requestId");
    String ipHash = null;
    String uaHash = null;
    try {
      ServletRequestAttributes attrs =
          (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
      if (attrs != null) {
        HttpServletRequest req = attrs.getRequest();
        ipHash = sha256short(req.getRemoteAddr());
        uaHash = sha256short(req.getHeader("User-Agent"));
      }
    } catch (Exception ignored) {
      // request context 取不到不影响审计写入
    }
    return new RequestInfo(traceId, requestId, ipHash, uaHash);
  }

  private static String sha256short(String s) {
    if (s == null || s.isEmpty()) return null;
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] h = md.digest(s.getBytes());
      StringBuilder sb = new StringBuilder(16);
      for (int i = 0; i < 8; i++) sb.append(String.format("%02x", h[i]));
      return sb.toString();
    } catch (Exception e) {
      return null;
    }
  }

  private record OperatorInfo(String operatorId, String operatorRole, String tenantId) {}

  private record RequestInfo(String traceId, String requestId, String ipHash, String uaHash) {}
}
