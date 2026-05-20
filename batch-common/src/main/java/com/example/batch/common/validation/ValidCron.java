package com.example.batch.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Cron 表达式校验。支持 Spring {@code CronExpression} 的 6 字段格式(秒 分 时 日 月 周)+ 兼容 5 字段(分 时 日 月 周)自动补 {@code
 * 0} 秒位。
 *
 * <p>动机:trigger 配置入库前在 DTO 层 400 拒掉非法 cron,避免后台调度时 {@code IllegalArgumentException} 才暴露; 与 trigger
 * 模块运行时校验器(Quartz CronExpression)同源,语法兼容。
 *
 * <p>用法:
 *
 * <pre>{@code
 * public record TriggerCreateRequest(
 *     @ValidCron String cronExpression, ...) {}
 * }</pre>
 *
 * <p>空 / null 视为合法(用 {@code @NotBlank} 单独约束必填)。
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidCronValidator.class)
public @interface ValidCron {

  String message() default "cron expression invalid";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
