package com.example.batch.console.support.excel;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class InMemoryTenantConfigPackageExcelImportStore
    implements TenantConfigPackageExcelImportStore {

  // P0:原 ConcurrentHashMap 无 TTL / 无上限,大 Excel 会话(多 sheet 完整解析后驻内存)
  // 在用户放弃 preview/apply 时永久驻留,高并发可累积到 OOM。
  // 改 Caffeine:expireAfterWrite 30 分钟 + maximumSize 1000(对齐预期上传 session 寿命)。
  private final Cache<String, PackageExcelSession> sessions =
      Caffeine.newBuilder()
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
  public void remove(String uploadToken) {
    sessions.invalidate(uploadToken);
  }
}
