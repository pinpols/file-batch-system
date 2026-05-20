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
    if (isStrictIpv4(trimmed)) {
      return true;
    }
    // `InetAddress.getByName` 对 IPv4 过于宽松；IPv6 字面量才交给 JDK 解析。
    if (!trimmed.contains(":")) {
      return false;
    }
    try {
      InetAddress addr = InetAddress.getByName(trimmed);
      if (ipv4Only && !(addr instanceof Inet4Address)) {
        return false;
      }
      return !(addr instanceof Inet4Address);
    } catch (UnknownHostException e) {
      return false;
    }
  }

  private static boolean isStrictIpv4(String value) {
    int start = 0;
    for (int part = 0; part < 4; part++) {
      int dot = part == 3 ? value.length() : value.indexOf('.', start);
      if (dot < 0 || dot == start || dot - start > 3) {
        return false;
      }
      int segment = 0;
      for (int i = start; i < dot; i++) {
        char c = value.charAt(i);
        if (c < '0' || c > '9') {
          return false;
        }
        segment = segment * 10 + (c - '0');
      }
      if (segment > 255) {
        return false;
      }
      start = dot + 1;
    }
    return start == value.length() + 1;
  }
}
