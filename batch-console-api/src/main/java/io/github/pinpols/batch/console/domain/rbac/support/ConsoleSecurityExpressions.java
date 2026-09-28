package io.github.pinpols.batch.console.domain.rbac.support;

/** 控制台 Controller 级 Spring Security 表达式常量。 */
public final class ConsoleSecurityExpressions {

  private static final String HAS_ANY_AUTHORITY = "hasAnyAuthority('";
  private static final String AUTHORITY_SEPARATOR = "', '";
  private static final String EXPRESSION_SUFFIX = "')";

  public static final String ADMIN_ONLY = "hasAuthority('" + ConsoleRoles.ADMIN + "')";
  public static final String ADMIN_OR_AUDITOR = HAS_ANY_AUTHORITY
      + ConsoleRoles.ADMIN
      + AUTHORITY_SEPARATOR
      + ConsoleRoles.AUDITOR
      + EXPRESSION_SUFFIX;
  public static final String ADMIN_OR_TENANT_ADMIN = HAS_ANY_AUTHORITY
      + ConsoleRoles.ADMIN
      + AUTHORITY_SEPARATOR
      + ConsoleRoles.TENANT_ADMIN
      + EXPRESSION_SUFFIX;
  public static final String ADMIN_OR_TENANT_USER = HAS_ANY_AUTHORITY
      + ConsoleRoles.ADMIN
      + AUTHORITY_SEPARATOR
      + ConsoleRoles.TENANT_USER
      + EXPRESSION_SUFFIX;
  public static final String ADMIN_OR_TENANT_ADMIN_OR_AUDITOR = HAS_ANY_AUTHORITY
      + ConsoleRoles.ADMIN
      + AUTHORITY_SEPARATOR
      + ConsoleRoles.TENANT_ADMIN
      + AUTHORITY_SEPARATOR
      + ConsoleRoles.AUDITOR
      + EXPRESSION_SUFFIX;
  public static final String ADMIN_OR_AUDITOR_OR_TENANT_ADMIN = HAS_ANY_AUTHORITY
      + ConsoleRoles.ADMIN
      + AUTHORITY_SEPARATOR
      + ConsoleRoles.AUDITOR
      + AUTHORITY_SEPARATOR
      + ConsoleRoles.TENANT_ADMIN
      + EXPRESSION_SUFFIX;
  public static final String ADMIN_OR_TENANT_ADMIN_OR_TENANT_USER = HAS_ANY_AUTHORITY
      + ConsoleRoles.ADMIN
      + AUTHORITY_SEPARATOR
      + ConsoleRoles.TENANT_ADMIN
      + AUTHORITY_SEPARATOR
      + ConsoleRoles.TENANT_USER
      + EXPRESSION_SUFFIX;
  public static final String ANY_CONSOLE_ROLE = HAS_ANY_AUTHORITY
      + ConsoleRoles.ADMIN
      + AUTHORITY_SEPARATOR
      + ConsoleRoles.AUDITOR
      + AUTHORITY_SEPARATOR
      + ConsoleRoles.TENANT_ADMIN
      + AUTHORITY_SEPARATOR
      + ConsoleRoles.TENANT_USER
      + EXPRESSION_SUFFIX;

  private ConsoleSecurityExpressions() {}
}
