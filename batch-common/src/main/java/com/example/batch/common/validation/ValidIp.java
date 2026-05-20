package com.example.batch.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * IP 地址校验(IPv4 + IPv6)。
 *
 * <p>动机:worker 注册 / 客户端 IP 字段 / IP 白名单等场景手写 regex 太乱(不少地方还放过 256.x.x.x);统一委托给 JDK
 * `InetAddress.getByName`(本地解析,不会走 DNS),既能拒非法,又能识别 IPv6 缩写形态。
 *
 * <p>空 / null 视为合法(用 {@code @NotBlank} 单独约束必填)。
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidIpValidator.class)
public @interface ValidIp {

  String message() default "ip address invalid";

  /** 仅允许 IPv4(默认 false,允许 v4+v6) */
  boolean ipv4Only() default false;

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
