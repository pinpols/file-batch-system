package com.example.batch.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * PostgreSQL 标识符(表名 / 列名 / schema 名)校验。
 *
 * <p>规则:小写字母开头 + 小写字母/数字/下划线;长度 ≤ 63(PG identifier 限制)。**禁止**大写 / 双引号 / 反斜杠 / 空格 / 中文 —— 防止 ddl /
 * dml 拼接 sql 时 identifier 注入(`biz_table_schema` / `forensic_export_log` 等动态 ddl 路径必须用)。
 *
 * <p>动机:外部输入(导入配置 / 业务表注册)如果直接拼到 `CREATE TABLE %s` 里,带特殊字符会 SQLi 或破坏 schema。统一校验避免散落。
 *
 * <p>空 / null 视为合法(用 {@code @NotBlank} 单独约束必填)。
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {})
@Size(max = 63, message = "identifier too long (PG max 63)")
@Pattern(
    regexp = "^$|^[a-z][a-z0-9_]{0,62}$",
    message = "identifier must start with lowercase letter and contain only [a-z0-9_], length ≤ 63")
public @interface ValidTableName {

  String message() default "table / column identifier invalid";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
