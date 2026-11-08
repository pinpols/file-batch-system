package io.github.pinpols.batch.console.shared.arch;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 限界上下文依赖隔离的显式豁免。
 *
 * <p>P1-A Stage 1 收尾起由 {@code BoundedContextDependencyArchTest} 校验：带本注解的类允许保留跨
 * bounded context 的直接依赖，但必须写明原因与计划清理时间，评审可据此追踪债务。
 *
 * <p>此前的 {@code SuppressWarnings}（BoundedContext）方案为 SOURCE 级注解，字节码扫描不可见，无法生效；
 * 本注解使用运行时保留，供 ArchUnit 读取。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface BoundedContextExemption {

  /** 豁免原因与该依赖的业务必要性。 */
  String reason();

  /** 计划清理时间或清理条件（例如 "P1-B 拆分只读查询端点后移除"）。 */
  String cleanupBy();
}
