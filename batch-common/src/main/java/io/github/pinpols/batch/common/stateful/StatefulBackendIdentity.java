package io.github.pinpols.batch.common.stateful;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Locale;

/** Builds stable, non-secret location identities for guarded stateful backends. */
public final class StatefulBackendIdentity {

  private StatefulBackendIdentity() {}

  public static String database(String jdbcUrl) {
    return "jdbc=" + normalized(sanitizeJdbcLocation(jdbcUrl));
  }

  public static String redis(
      String host, int port, int database, String sentinelMaster, String sentinelNodes) {
    if (hasText(sentinelMaster)) {
      return "sentinel="
          + normalized(sentinelMaster)
          + "|nodes="
          + normalized(sentinelNodes)
          + "|db="
          + database;
    }
    return "host=" + normalized(host) + "|port=" + port + "|db=" + database;
  }

  public static String s3(String endpoint, String region, String bucket) {
    return "endpoint="
        + normalized(endpoint)
        + "|region="
        + normalized(region)
        + "|bucket="
        + normalized(bucket);
  }

  public static String filesystem(String root, String bucket) {
    String normalizedRoot =
        hasText(root) ? Path.of(root).toAbsolutePath().normalize().toString() : "";
    return "root=" + normalizedRoot + "|bucket=" + normalized(bucket);
  }

  public static String sqlite(Path path) {
    return "path=" + path.toAbsolutePath().normalize();
  }

  private static String sanitizeJdbcLocation(String jdbcUrl) {
    if (!hasText(jdbcUrl)) {
      return "";
    }
    String value = jdbcUrl.trim();
    int queryStart = value.indexOf('?');
    if (queryStart >= 0) {
      value = value.substring(0, queryStart);
    }
    int fragmentStart = value.indexOf('#');
    if (fragmentStart >= 0) {
      value = value.substring(0, fragmentStart);
    }
    if (!value.startsWith("jdbc:")) {
      return stripAuthorityUserInfo(value);
    }

    String uriValue = value.substring("jdbc:".length());
    try {
      URI uri = new URI(uriValue);
      if (uri.getHost() == null) {
        return stripAuthorityUserInfo(value);
      }
      URI sanitized =
          new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null);
      return "jdbc:" + sanitized;
    } catch (URISyntaxException ex) {
      return stripAuthorityUserInfo(value);
    }
  }

  private static String stripAuthorityUserInfo(String value) {
    int authorityStart = value.indexOf("//");
    if (authorityStart < 0) {
      return value;
    }
    int authorityEnd = value.indexOf('/', authorityStart + 2);
    int at = value.lastIndexOf('@', authorityEnd < 0 ? value.length() : authorityEnd);
    if (at < authorityStart) {
      return value;
    }
    return value.substring(0, authorityStart + 2) + value.substring(at + 1);
  }

  private static String normalized(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
