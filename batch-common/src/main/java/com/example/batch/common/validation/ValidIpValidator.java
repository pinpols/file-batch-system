package com.example.batch.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

public class ValidIpValidator implements ConstraintValidator<ValidIp, String> {

  private boolean ipv4Only;

  @Override
  public void initialize(ValidIp constraintAnnotation) {
    this.ipv4Only = constraintAnnotation.ipv4Only();
  }

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    if (value == null || value.isBlank()) {
      return true;
    }
    String trimmed = value.trim();
    // 先做形态过滤:`InetAddress.getByName` 会把纯数字主机名(如 "12345")当作合法 → 必须先校验形态有点 / 冒号
    if (!trimmed.contains(".") && !trimmed.contains(":")) {
      return false;
    }
    try {
      InetAddress addr = InetAddress.getByName(trimmed);
      if (ipv4Only && !(addr instanceof Inet4Address)) {
        return false;
      }
      return true;
    } catch (UnknownHostException e) {
      return false;
    }
  }
}
