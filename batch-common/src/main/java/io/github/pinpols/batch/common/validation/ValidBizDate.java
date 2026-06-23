package io.github.pinpols.batch.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 统一的 bizDate 校验约束（字符串入参）。
 *
 * <p>格式：yyyy-MM-dd（例如 2026-03-25）。
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidBizDateValidator.class)
public @interface ValidBizDate {

  String message() default "bizDate format invalid (expected yyyy-MM-dd)";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
