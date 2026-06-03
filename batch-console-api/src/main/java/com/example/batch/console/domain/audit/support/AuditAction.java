package com.example.batch.console.domain.audit.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注 Controller 写操作方法,由 {@link AuditAspect} 同事务写一行 {@code batch.console_operation_audit}。
 *
 * <p>用法示例:
 *
 * <pre>{@code
 * @PostMapping("/{id}/close")
 * @AuditAction(action = "alert.close", aggregateType = "alert", aggregateId = "#id")
 * public CommonResponse<Void> close(@PathVariable Long id, ...) { ... }
 * }</pre>
 *
 * <p><b>设计:</b>
 *
 * <ul>
 *   <li>不拦读操作 — access log 和 Loki 已经覆盖,审计表只关心写
 *   <li>Aspect 成功路径**同业务事务**写 audit(强一致:业务 rollback → audit rollback)
 *   <li>**失败路径**走 {@code REQUIRES_NEW} 新事务写 result=FAILED + errorCode/Message, 不被业务回滚带走,合规取证可见
 *   <li>aggregateId 用 SpEL 表达式从入参取(支持 {@code #id} / {@code #request.alertId})
 *   <li>params 由 Aspect 收集所有非敏感入参的 JSON 摘要(< 2KB)
 * </ul>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AuditAction {

  /** 动作名,与前端埋点 data-track 对齐(alert.close / approvals.approve / jobs.terminate / ...)。 必填。 */
  String action();

  /** 聚合根类型(alert / approval / job_instance / worker / auth / ...)。必填,用于 Kafka 分区。 */
  String aggregateType();

  /**
   * 聚合根 ID 的 SpEL 表达式,运行时对入参求值。例如 {@code "#id"} 或 {@code "#request.alertId"}。
   * 留空时写「-」占位,适用于无聚合根的操作(如批量、登录)。
   */
  String aggregateId() default "";

  /**
   * 是否把方法入参序列化到 audit 的 params JSONB 列。默认 true。
   *
   * <p>带密码 / 加密载荷 / 密钥的接口必须显式设 {@code false},否则原文落到审计表(比如 login 的 password 字段、API Key 创建的
   * plaintext)。
   */
  boolean recordParams() default true;

  /**
   * 目标租户的 SpEL 表达式或参数名,运行时对入参求值得到目标租户 ID。例如 {@code "#tenantId"} 或 {@code "#request.tenantId"}。
   *
   * <p><b>用途:</b>ROLE_ADMIN 跨租操作(如 {@code ConsoleTenantController.update(tenantId, ...)})的 {@link
   * com.example.batch.console.domain.rbac.support.ConsolePrincipal#tenantId()} 为 null, 默认会兜底到
   * {@code "system"},导致审计行 tenant_id="system",取证按目标租户查询时漏掉这些行。 显式指定 {@code targetTenantParam}
   * 后,审计行的 tenant_id 取该入参值,而非 principal 的租户。
   *
   * <p><b>解析优先级:</b>targetTenantParam(非空且成功求值) → principal.tenantId() → MDC tenant → "system"。
   *
   * <p><b>留空(默认):</b>沿用 principal/MDC/system 兜底链,适合 login/logout/系统级动作。
   */
  String targetTenantParam() default "";
}
