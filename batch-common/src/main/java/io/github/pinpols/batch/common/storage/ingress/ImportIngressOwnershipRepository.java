package io.github.pinpols.batch.common.storage.ingress;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** 从平台库读取 Import 与对象对账共同使用的入口归属声明。 */
public final class ImportIngressOwnershipRepository {

  private static final String QUERY = """
      SELECT s.scope_id, s.storage_id, s.bucket_name, s.object_prefix AS scope_prefix, s.state,
             b.binding_type, b.tenant_id, b.object_prefix AS binding_prefix,
             b.file_group_code, b.manifest_key_template, b.channel_code, b.enabled
      FROM batch.import_ingress_ownership s
      LEFT JOIN batch.import_ingress_ownership_binding b ON b.scope_id = s.scope_id %s
      WHERE %s
      ORDER BY s.scope_id, b.binding_type, b.tenant_id, b.object_prefix
      """;

  private final JdbcTemplate platformJdbc;

  /** 调用方必须传入平台库的 JdbcTemplate，不能使用 Worker 的业务库连接。 */
  public ImportIngressOwnershipRepository(JdbcTemplate platformJdbc) {
    this.platformJdbc = platformJdbc;
  }

  /** 只有 ACTIVE 范围可供 Import 扫描。 */
  public Optional<Scope> findActiveScope(String scopeId) {
    List<Scope> scopes = query(" AND b.enabled", "s.scope_id = ? AND s.state = 'ACTIVE'", scopeId);
    return scopes.stream().findFirst();
  }

  /** 已退休范围继续受保护，明确释放后才可交给普通对象对账。 */
  public List<Scope> listProtectedScopes(String storageId) {
    return query("", "s.storage_id = ? AND s.state IN ('ACTIVE', 'RETIRED')", storageId);
  }

  private List<Scope> query(String bindingPredicate, String predicate, String value) {
    String sql = QUERY.formatted(bindingPredicate, predicate);
    return platformJdbc.query(
        sql,
        (ResultSet resultSet) -> {
          Map<String, ScopeBuilder> scopes = new LinkedHashMap<>();
          while (resultSet.next()) {
            String scopeId = resultSet.getString("scope_id");
            ScopeBuilder scope = scopes.get(scopeId);
            if (EmptyChecks.isNull(scope)) {
              scope = new ScopeBuilder(resultSet);
              scopes.put(scopeId, scope);
            }
            String bindingType = resultSet.getString("binding_type");
            if (EmptyChecks.isNotNull(bindingType)) {
              scope.bindings.add(new Binding(
                  bindingType,
                  resultSet.getString("tenant_id"),
                  resultSet.getString("binding_prefix"),
                  resultSet.getString("file_group_code"),
                  resultSet.getString("manifest_key_template"),
                  resultSet.getString("channel_code"),
                  resultSet.getBoolean("enabled")));
            }
          }
          return scopes.values().stream().map(ScopeBuilder::build).toList();
        },
        value);
  }

  public record Binding(
      String type,
      String tenantId,
      String objectPrefix,
      String fileGroupCode,
      String manifestKeyTemplate,
      String channelCode,
      boolean enabled) {}

  public record Scope(
      String scopeId,
      String storageId,
      String bucketName,
      String objectPrefix,
      String state,
      List<Binding> bindings) {

    public Scope {
      bindings = List.copyOf(bindings);
    }

    /** 清单可能位于扫描前缀外，按已声明模板的完整对象键识别。 */
    public boolean protects(String bucket, String objectKey) {
      if (!bucketName.equals(bucket) || EmptyChecks.isNull(objectKey)) {
        return false;
      }
      if (objectKey.startsWith(objectPrefix)) {
        return true;
      }
      return bindings.stream()
          .filter(binding -> "MANIFEST".equals(binding.type()))
          .anyMatch(binding -> matchesManifestTemplate(binding.manifestKeyTemplate(), objectKey));
    }

    private static boolean matchesManifestTemplate(String template, String key) {
      if (EmptyChecks.isNull(template)) {
        return false;
      }
      String placeholder = template.contains("{bizDateCompact}") ? "{bizDateCompact}" : "{bizDate}";
      int position = template.indexOf(placeholder);
      if (position < 0 || position != template.lastIndexOf(placeholder)) {
        return false;
      }
      String before = template.substring(0, position);
      String after = template.substring(position + placeholder.length());
      int dateLength = "{bizDate}".equals(placeholder) ? 10 : 8;
      if (key.length() != before.length() + dateLength + after.length()
          || !key.startsWith(before)
          || !key.endsWith(after)) {
        return false;
      }
      String date = key.substring(before.length(), before.length() + dateLength);
      for (int index = 0; index < date.length(); index++) {
        char character = date.charAt(index);
        if (dateLength == 10 && (index == 4 || index == 7)) {
          if (character != '-') {
            return false;
          }
        } else if (character < '0' || character > '9') {
          return false;
        }
      }
      return true;
    }
  }

  private static final class ScopeBuilder {
    private final String scopeId;
    private final String storageId;
    private final String bucketName;
    private final String objectPrefix;
    private final String state;
    private final List<Binding> bindings = new ArrayList<>();

    private ScopeBuilder(ResultSet resultSet) throws SQLException {
      scopeId = resultSet.getString("scope_id");
      storageId = resultSet.getString("storage_id");
      bucketName = resultSet.getString("bucket_name");
      objectPrefix = resultSet.getString("scope_prefix");
      state = resultSet.getString("state");
    }

    private Scope build() {
      return new Scope(scopeId, storageId, bucketName, objectPrefix, state, bindings);
    }
  }
}
