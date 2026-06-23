package io.github.pinpols.batch.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.scheduling.support.CronExpression;

public class ValidCronValidator implements ConstraintValidator<ValidCron, String> {

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    if (value == null || value.isBlank()) {
      // 空值不校验,@NotBlank 单独约束必填
      return true;
    }
    String normalized = normalize(value.trim());
    try {
      CronExpression.parse(normalized);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** Spring CronExpression 要求 6 字段;5 字段(标准 cron)前补 {@code 0} 秒位,兼容运维习惯输入。 */
  private String normalize(String value) {
    int fields = value.split("\\s+").length;
    return fields == 5 ? "0 " + value : value;
  }
}
