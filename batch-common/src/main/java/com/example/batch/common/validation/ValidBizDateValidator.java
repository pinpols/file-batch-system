package com.example.batch.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

public class ValidBizDateValidator implements ConstraintValidator<ValidBizDate, String> {

  private static final DateTimeFormatter FMT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    // 允许空值：是否必填由 @NotBlank 决定；本校验只负责“格式/可解析性”。
    if (value == null || value.isBlank()) {
      return true;
    }
    try {
      LocalDate.parse(value.trim(), FMT);
      return true;
    } catch (DateTimeParseException ex) {
      return false;
    }
  }
}
