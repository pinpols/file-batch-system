package io.github.pinpols.batch.common.jdbc;

import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

/** JDBC 映射插件的白名单校验（仅允许标识符，禁止调用方传入 SQL 片段）。 */
public final class JdbcMappedSqlValidator {

  private static final Pattern IDENT = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

  private JdbcMappedSqlValidator() {}

  public static String requireIdentifier(String name, String role) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException(role + " is required");
    }
    String v = name.trim().toLowerCase(Locale.ROOT);
    if (!IDENT.matcher(v).matches()) {
      throw new IllegalArgumentException(role + " has invalid characters: " + name);
    }
    return v;
  }

  public static void requireInAllowlist(String schema, Collection<String> allowedSchemas) {
    String s = requireIdentifier(schema, "schema");
    if (allowedSchemas == null || allowedSchemas.isEmpty()) {
      throw new IllegalStateException("jdbc-mapped allowed schemas not configured");
    }
    if (!allowedSchemas.contains(s)) {
      throw new IllegalArgumentException("schema not allowlisted: " + s);
    }
  }

  public static String quotePg(String ident) {
    String v = requireIdentifier(ident, "identifier");
    return "\"" + v + "\"";
  }
}
