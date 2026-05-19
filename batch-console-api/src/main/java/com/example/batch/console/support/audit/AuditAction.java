package com.example.batch.console.support.audit;

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
 *   <li>Aspect 在方法**成功返回后**才写 audit(失败也写,result=FAILED 标记);跟业务在同 一个事务,业务回滚 → audit 也回滚,**业务 commit
 *       才有 audit**,保证强一致
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
}
