package io.github.pinpols.batch.common.stateful;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Locale;

/** 为受保护的有状态后端生成稳定且不含秘密信息的位置标识。 */
public final class StatefulBackendIdentity {

  private StatefulBackendIdentity() {}

  public static String database(String jdbcUrl) {
    String sanitizedJdbcLocation = sanitizeLocation(jdbcUrl, true);
    return "jdbc=" + sanitizedJdbcLocation;
  }

  public static String redis(
      String host, int port, int database, String sentinelMaster, String sentinelNodes) {
    return hasText(sentinelMaster)
        ? redisSentinel(sentinelMaster, sentinelNodes, database)
        : redisStandalone(host, port, database);
  }

  public static String redisStandalone(String host, int port, int database) {
    return "host=" + normalized(host) + "|port=" + port + "|db=" + database;
  }

  public static String redisSentinel(String master, String nodes, int database) {
    return "sentinel=" + trimmed(master) + "|nodes=" + normalized(nodes) + "|db=" + database;
  }

  public static String redisCluster(String nodes) {
    return "cluster=" + normalized(nodes);
  }

  public static String redisMasterReplica(String nodes) {
    return "master-replica=" + normalized(nodes);
  }

  public static String s3(String endpoint, String region, String bucket) {
    return "endpoint="
        + sanitizeLocation(endpoint, false)
        + "|region="
        + trimmed(region)
        + "|bucket="
        + trimmed(bucket);
  }

  public static String filesystem(String root, String bucket) {
    String normalizedRoot = "";
    if (hasText(root)) {
      Path rootPath = Path.of(root);
      Path absoluteRoot = rootPath.toAbsolutePath();
      Path normalizedPath = absoluteRoot.normalize();
      normalizedRoot = normalizedPath.toString();
    }
    return "root=" + normalizedRoot + "|bucket=" + trimmed(bucket);
  }

  public static String sqlite(Path path) {
    Path absolutePath = path.toAbsolutePath();
    Path normalizedPath = absolutePath.normalize();
    return "path=" + normalizedPath;
  }

  private static String sanitizeLocation(String location, boolean omitParameters) {
    if (!hasText(location)) {
      return "";
    }
    String value = location.trim();
    int queryStart = omitParameters ? value.indexOf('?') : -1;
    if (queryStart >= 0) {
      value = value.substring(0, queryStart);
    }
    int fragmentStart = omitParameters ? value.indexOf('#') : -1;
    if (fragmentStart >= 0) {
      value = value.substring(0, fragmentStart);
    }
    String prefix = value.startsWith("jdbc:") ? "jdbc:" : "";
    String uriValue = value.substring(prefix.length());
    try {
      URI uri = new URI(uriValue);
      if (uri.getHost() == null) {
        return stripAuthorityUserInfo(value);
      }
      // 协议和DNS主机名可统一大小写，资源路径及其百分号编码必须按连接位置保留。
      String scheme =
          EmptyChecks.isNull(uri.getScheme()) ? null : uri.getScheme().toLowerCase(Locale.ROOT);
      URI authority = new URI(
          scheme, null, uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(), null, null, null);
      String resourcePath = EmptyChecks.isNull(uri.getRawPath()) ? "" : uri.getRawPath();
      String parameters = EmptyChecks.isNull(uri.getRawQuery()) ? "" : "?" + uri.getRawQuery();
      String fragment = EmptyChecks.isNull(uri.getRawFragment()) ? "" : "#" + uri.getRawFragment();
      return prefix + authority + resourcePath + parameters + fragment;
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
    return trimmed(value).toLowerCase(Locale.ROOT);
  }

  private static String trimmed(String value) {
    return EmptyChecks.isNull(value) ? "" : value.trim();
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
