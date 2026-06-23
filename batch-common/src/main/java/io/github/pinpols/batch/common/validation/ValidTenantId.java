package io.github.pinpols.batch.common.validation;

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
 * 统一的 tenantId 校验约束（console/controller DTO 层使用）。
 *
 * <p>目标：把“必填 + 长度 + 基本格式”前置到框架层 400，而不是让超长/非法值进入 DB 再报错。
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {})
@NotBlank(message = "tenantId is required")
@Size(max = 64, message = "tenantId too long (max 64)")
@Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$", message = "tenantId format invalid")
public @interface ValidTenantId {

  String message() default "tenantId invalid";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
