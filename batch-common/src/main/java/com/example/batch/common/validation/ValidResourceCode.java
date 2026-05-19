package com.example.batch.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 统一的"资源 code"校验：jobCode / workflowCode / templateCode / channelCode / calendarCode / windowCode /
 * routeCode / policyCode 等业务标识列均应使用此注解。
 *
 * <p>规则：字母开头 + 字母/数字/下划线/连字符；长度 ≤ 128。
 *
 * <p>动机：前端发现 "q q q" / 含中文 / 空字符串能入库导致路由跳转崩溃；统一在 DTO 层 400，避免散落 写 @Pattern 字面量重复（且容易漏）。
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {})
@NotBlank(message = "code is required")
@Size(max = 128, message = "code too long (max 128)")
@Pattern(
    regexp = "^[a-zA-Z][a-zA-Z0-9_-]{0,127}$",
    message =
        "code must start with a letter and contain only letters, digits, underscore or hyphen"
            + " (no spaces / Chinese / special chars), length ≤ 128")
public @interface ValidResourceCode {

  String message() default "resource code invalid";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
