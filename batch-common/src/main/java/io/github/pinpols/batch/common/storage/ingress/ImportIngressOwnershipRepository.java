package io.github.pinpols.batch.common.storage.ingress;

import io.github.pinpols.batch.common.mapper.ImportIngressOwnershipMapper;
import io.github.pinpols.batch.common.mapper.ImportIngressOwnershipMapper.OwnershipRow;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 从平台库读取 Import 与对象对账共同使用的入口归属声明。 */
@Repository
@RequiredArgsConstructor
public class ImportIngressOwnershipRepository {

  private final ImportIngressOwnershipMapper mapper;

  /** 只有 ACTIVE 范围可供 Import 扫描。 */
  public Optional<Scope> findActiveScope(String scopeId) {
    List<OwnershipRow> rows = mapper.findActiveScope(scopeId);
    List<Scope> scopes = scopes(rows);
    return scopes.stream().findFirst();
  }

  /** 已退休范围继续受保护，明确释放后才可交给普通对象对账。 */
  public List<Scope> listProtectedScopes(String storageId) {
    List<OwnershipRow> rows = mapper.listProtectedScopes(storageId);
    return scopes(rows);
  }

  private List<Scope> scopes(List<OwnershipRow> rows) {
    Map<String, ScopeBuilder> scopes = new LinkedHashMap<>();
    for (OwnershipRow row : rows) {
      String scopeId = row.getScopeId();
      ScopeBuilder scope = scopes.get(scopeId);
      if (EmptyChecks.isNull(scope)) {
        scope = new ScopeBuilder(row);
        scopes.put(scopeId, scope);
      }
      if (EmptyChecks.isNotNull(row.getBindingType())) {
        scope.bindings.add(new Binding(
            row.getBindingType(),
            row.getTenantId(),
            row.getBindingPrefix(),
            row.getFileGroupCode(),
            row.getManifestKeyTemplate(),
            row.getChannelCode(),
            row.isEnabled()));
      }
    }
    return scopes.values().stream().map(ScopeBuilder::build).toList();
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

    private ScopeBuilder(OwnershipRow row) {
      scopeId = row.getScopeId();
      storageId = row.getStorageId();
      bucketName = row.getBucketName();
      objectPrefix = row.getScopePrefix();
      state = row.getState();
    }

    private Scope build() {
      return new Scope(scopeId, storageId, bucketName, objectPrefix, state, bindings);
    }
  }
}
