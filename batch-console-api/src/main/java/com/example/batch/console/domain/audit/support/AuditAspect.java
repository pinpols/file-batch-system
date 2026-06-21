package com.example.batch.console.domain.audit.support;

import com.example.batch.common.utils.Hashes;
import com.example.batch.console.domain.audit.mapper.OperationAuditMapper;
import com.example.batch.console.domain.rbac.support.ConsolePrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.expression.spel.support.DataBindingPropertyAccessor;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
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
  private final PlatformTransactionManager transactionManager;

  private final ExpressionParser spel = new SpelExpressionParser();
  private final ParameterNameDiscoverer paramNameDiscoverer = new DefaultParameterNameDiscoverer();

  /**
   * P1(2026-05-23 audit):REQUIRES_NEW 模板 @PostConstruct 一次性构建复用, 避免每次失败路径在 {@link
   * #recordInNewTransaction} 内反复 {@code new TransactionTemplate}(切面单例)。
   */
  private TransactionTemplate requiresNewTemplate;

  @PostConstruct
  void initTransactionTemplate() {
    TransactionTemplate tmpl = new TransactionTemplate(transactionManager);
    tmpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.requiresNewTemplate = tmpl;
  }

  @Around("@annotation(com.example.batch.console.domain.audit.support.AuditAction)")
  public Object wrap(ProceedingJoinPoint pjp) throws Throwable {
    MethodSignature sig = (MethodSignature) pjp.getSignature();
    Method method = sig.getMethod();
    AuditAction ann = method.getAnnotation(AuditAction.class);
    if (ann == null) return pjp.proceed();

    String aggregateId = resolveAggregateId(ann, method, pjp.getArgs());
    // P0(2026-06-03 audit-tenant):ROLE_ADMIN 跨租操作 principal.tenantId() 为 null,
    // 之前默认回退到 "system",导致审计行 tenant_id="system" 而非目标租户,取证查询漏。
    // 在调用 record 前先按 targetTenantParam 解析目标租户,作为最高优先级覆盖。
    String targetTenantId = resolveTargetTenant(ann, method, pjp.getArgs());
    // 敏感操作(login / API Key create)显式 recordParams=false 跳过 params 写入数据库,
    // 避免 password / 加密载荷 / 明文密钥泄露到审计表
    String paramsJson = ann.recordParams() ? serializeParams(method, pjp.getArgs()) : null;

    try {
      Object result = pjp.proceed();
      record(ann, aggregateId, targetTenantId, paramsJson, null, null);
      return result;
    } catch (Throwable ex) {
      // 业务事务回滚会带走同事务 audit。用 REQUIRES_NEW 新事务写 FAILED 留痕,保证合规取证可见。
      // 写失败本身只 warn,不影响业务 exception 继续抛出。
      String errorCode = resolveErrorCode(ex);
      String errorMsg = ex.getMessage();
      recordInNewTransaction(ann, aggregateId, targetTenantId, paramsJson, errorCode, errorMsg);
      throw ex;
    }
  }

  /**
   * 用 PROPAGATION_REQUIRES_NEW 新开事务写 FAILED 审计,避免被业务事务回滚带走。 失败本身捕获并抑制(warn) — 业务异常已在 catch 外被
   * rethrow,审计写不写都不影响业务路径。
   */
  private void recordInNewTransaction(
      AuditAction ann,
      String aggregateId,
      String targetTenantId,
      String paramsJson,
      String errorCode,
      String errorMsg) {
    try {
      requiresNewTemplate.executeWithoutResult(
          status -> record(ann, aggregateId, targetTenantId, paramsJson, errorCode, errorMsg));
    } catch (Exception writeFail) {
      log.warn(
          "[audit] FAILED audit write also failed action={} aggregateId={}",
          ann.action(),
          aggregateId,
          writeFail);
    }
  }

  /** 业务异常抽取 errorCode:BizException 走 ResultCode.name(),其他 → 异常类 simpleName。 */
  private static String resolveErrorCode(Throwable ex) {
    if (ex instanceof com.example.batch.common.exception.BizException biz
        && biz.getCode() != null) {
      return biz.getCode().name();
    }
    return ex.getClass().getSimpleName();
  }

  private void record(
      AuditAction ann,
      String aggregateId,
      String targetTenantId,
      String paramsJson,
      String errorCode,
      String errorMsg) {
    try {
      OperatorInfo op = currentOperator(targetTenantId);
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
      // P0 安全:对齐 ConsoleCacheInvalidationAspect.evaluateSpel(),用 SimpleEvaluationContext
      // + DataBindingPropertyAccessor 做最小权限上下文。SimpleEvaluationContext 禁止
      // T(System).exit(0) 类型方法 / Type 引用 / bean 引用,仅允许属性 / 索引 / 算术 / 实例方法。
      SimpleEvaluationContext ctx =
          SimpleEvaluationContext.forPropertyAccessors(
                  DataBindingPropertyAccessor.forReadOnlyAccess())
              .withInstanceMethods()
              .build();
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
      if (json.length() <= MAX_PARAMS_JSON_BYTES) {
        return json;
      }
      // 超长截断:原 substring 拼 `","_trunc":1}` 可能截在字符串转义/数组/对象中间,
      // 写 jsonb 直接 invalid_text_representation → 审计静默丢失。
      // 改为构造合法 JSON,保留 preview(纯文本字段)+ 显式标记 truncated=true
      int previewLen = Math.max(64, MAX_PARAMS_JSON_BYTES - 64);
      String preview = json.substring(0, Math.min(previewLen, json.length()));
      Map<String, Object> truncated = new LinkedHashMap<>();
      truncated.put("_truncated", true);
      truncated.put("_originalLength", json.length());
      truncated.put("_preview", preview);
      truncated.put("_keys", new ArrayList<>(snapshot.keySet()));
      return objectMapper.writeValueAsString(truncated);
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

  /**
   * console_operation_audit.tenant_id NOT NULL。
   *
   * <p>解析链(从高到低):
   *
   * <ol>
   *   <li>{@code targetTenantId} —— {@link AuditAction#targetTenantParam()} 显式声明的目标租户,适用于
   *       ROLE_ADMIN 跨租操作(如 {@code ConsoleTenantController.update(tenantId, ...)}),保证审计行 tenant_id
   *       是被改的租户而非 "system"
   *   <li>{@code principal.tenantId()} —— 普通租户用户的会话租户
   *   <li>MDC tenant —— 异步上下文 / TenantGuard 注入的回退
   *   <li>{@code "system"} 字面值回退 —— 真正无租路径(login/logout/系统级 healthcheck),避免 NOT NULL 约束拒绝丢失审计行
   * </ol>
   */
  private OperatorInfo currentOperator(String targetTenantId) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof ConsolePrincipal p)) {
      return new OperatorInfo(null, null, resolveTenantFallback(targetTenantId, null));
    }
    String role =
        p.authorities() == null || p.authorities().isEmpty()
            ? null
            : p.authorities().iterator().next();
    return new OperatorInfo(
        p.username(), role, resolveTenantFallback(targetTenantId, p.tenantId()));
  }

  private static String resolveTenantFallback(String targetTenantId, String principalTenantId) {
    if (targetTenantId != null && !targetTenantId.isBlank()) {
      return targetTenantId;
    }
    if (principalTenantId != null && !principalTenantId.isBlank()) {
      return principalTenantId;
    }
    String mdcTenant = MDC.get("tenant");
    if (mdcTenant != null && !mdcTenant.isBlank()) {
      return mdcTenant;
    }
    return "system";
  }

  /**
   * 按 {@link AuditAction#targetTenantParam()} 求值得到目标租户 ID。支持两种写法:
   *
   * <ul>
   *   <li>SpEL 表达式(以 {@code #} 开头):{@code "#tenantId"} / {@code "#request.tenantId"}
   *   <li>纯参数名(无 {@code #}):{@code "tenantId"} —— 直接按参数名取入参值
   * </ul>
   *
   * <p>留空 / 求值失败 / 求值为 null → 返回 null(由 {@link #resolveTenantFallback} 继续往下找 principal/MDC/system)。
   * 失败只 warn 不抛,绝不拖垮业务路径。
   */
  private String resolveTargetTenant(AuditAction ann, Method method, Object[] args) {
    String expr = ann.targetTenantParam();
    if (expr == null || expr.isEmpty()) return null;
    try {
      String[] names = paramNameDiscoverer.getParameterNames(method);
      if (expr.startsWith("#")) {
        SimpleEvaluationContext ctx =
            SimpleEvaluationContext.forPropertyAccessors(
                    DataBindingPropertyAccessor.forReadOnlyAccess())
                .withInstanceMethods()
                .build();
        if (names != null) {
          for (int i = 0; i < names.length && i < args.length; i++) {
            ctx.setVariable(names[i], args[i]);
          }
        }
        Object v = spel.parseExpression(expr).getValue(ctx);
        return v == null ? null : String.valueOf(v);
      }
      // 纯参数名形式:按名匹配方法参数
      if (names != null) {
        for (int i = 0; i < names.length && i < args.length; i++) {
          if (expr.equals(names[i])) {
            return args[i] == null ? null : String.valueOf(args[i]);
          }
        }
      }
      return null;
    } catch (Exception ex) {
      log.warn("[audit] targetTenantParam '{}' eval failed: {}", expr, ex.getMessage());
      return null;
    }
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
        ipHash = Hashes.sha256Short(req.getRemoteAddr());
        uaHash = Hashes.sha256Short(req.getHeader("User-Agent"));
      }
    } catch (Exception ignored) {
      // request context 取不到不影响审计写入
    }
    return new RequestInfo(traceId, requestId, ipHash, uaHash);
  }

  private record OperatorInfo(String operatorId, String operatorRole, String tenantId) {}

  private record RequestInfo(String traceId, String requestId, String ipHash, String uaHash) {}
}
