package io.github.pinpols.batch.console.support.excel;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class InMemoryTenantConfigExcelStore implements TenantConfigPackageExcelImportStore {

  // P0:原 ConcurrentHashMap 无 TTL / 无上限,大 Excel 会话(多 sheet 完整解析后驻内存)
  // 在用户放弃 preview/apply 时永久驻留,高并发可累积到 OOM。
  // 改 Caffeine:expireAfterWrite 30 分钟 + maximumSize 1000(对齐预期上传 session 寿命)。
  private final Cache<String, PackageExcelSession> sessions = Caffeine.newBuilder()
      .expireAfterWrite(30, TimeUnit.MINUTES)
      .maximumSize(1000)
      .build();
  private final ConcurrentHashMap<String, String> applying = new ConcurrentHashMap<>();
  private final Cache<String, String> previewTokens = Caffeine.newBuilder()
      .expireAfterWrite(30, TimeUnit.MINUTES)
      .maximumSize(1000)
      .build();
  private final Cache<String, Long> previewRevisions = Caffeine.newBuilder()
      .expireAfterWrite(30, TimeUnit.MINUTES)
      .maximumSize(1000)
      .build();

  @Override
  public String save(PackageExcelSession session) {
    String token = UUID.randomUUID().toString().replace("-", "");
    sessions.put(token, session);
    return token;
  }

  @Override
  public PackageExcelSession get(String uploadToken) {
    return sessions.getIfPresent(uploadToken);
  }

  @Override
  public PreviewSnapshot issuePreviewSnapshot(String uploadToken, long configurationRevision) {
    PackageExcelSession session = sessions.getIfPresent(uploadToken);
    if (session == null) {
      return null;
    }
    synchronized (session) {
      if (sessions.getIfPresent(uploadToken) != session || applying.contains(uploadToken)) {
        return null;
      }
      String previewToken = UUID.randomUUID().toString().replace("-", "");
      previewTokens.put(uploadToken, previewToken);
      previewRevisions.put(uploadToken, configurationRevision);
      return new PreviewSnapshot(session, previewToken, configurationRevision);
    }
  }

  @Override
  public boolean isApplyInProgress(String uploadToken) {
    return applying.containsKey(uploadToken);
  }

  @Override
  public boolean claimForApply(String uploadToken, String previewToken, String applyOwnerToken) {
    PackageExcelSession session = sessions.getIfPresent(uploadToken);
    if (session == null || previewToken == null || applyOwnerToken == null) {
      return false;
    }
    synchronized (session) {
      return sessions.getIfPresent(uploadToken) == session
          && previewToken.equals(previewTokens.getIfPresent(uploadToken))
          && applying.putIfAbsent(uploadToken, applyOwnerToken) == null;
    }
  }

  @Override
  public long configurationRevisionForApply(String uploadToken, String applyOwnerToken) {
    if (!applyOwnerToken.equals(applying.get(uploadToken))) {
      throw new IllegalStateException("config upload apply claim lost its owner");
    }
    Long revision = previewRevisions.getIfPresent(uploadToken);
    if (revision == null) {
      throw new IllegalStateException("config upload apply claim lost its preview revision");
    }
    return revision;
  }

  @Override
  public boolean replaceSession(
      String uploadToken,
      String expectedPreviewToken,
      PackageExcelSession session,
      String replacementPreviewToken,
      long configurationRevision) {
    PackageExcelSession current = sessions.getIfPresent(uploadToken);
    if (current == null || expectedPreviewToken == null || replacementPreviewToken == null) {
      return false;
    }
    synchronized (current) {
      if (sessions.getIfPresent(uploadToken) != current
          || applying.contains(uploadToken)
          || !expectedPreviewToken.equals(previewTokens.getIfPresent(uploadToken))) {
        return false;
      }
      sessions.put(uploadToken, session);
      previewTokens.put(uploadToken, replacementPreviewToken);
      previewRevisions.put(uploadToken, configurationRevision);
      return true;
    }
  }

  @Override
  public void completeApply(String uploadToken, String applyOwnerToken) {
    PackageExcelSession session = sessions.getIfPresent(uploadToken);
    if (session == null) {
      return;
    }
    synchronized (session) {
      if (sessions.getIfPresent(uploadToken) == session
          && applying.remove(uploadToken, applyOwnerToken)) {
        sessions.invalidate(uploadToken);
        previewTokens.invalidate(uploadToken);
        previewRevisions.invalidate(uploadToken);
      }
    }
  }

  @Override
  public void releaseApply(String uploadToken, String applyOwnerToken) {
    applying.remove(uploadToken, applyOwnerToken);
  }

  @Override
  public void remove(String uploadToken) {
    sessions.invalidate(uploadToken);
    previewTokens.invalidate(uploadToken);
    previewRevisions.invalidate(uploadToken);
    applying.remove(uploadToken);
  }
}
