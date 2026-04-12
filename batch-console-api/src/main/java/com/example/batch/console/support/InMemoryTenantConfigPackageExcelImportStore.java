package com.example.batch.console.support;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class InMemoryTenantConfigPackageExcelImportStore
    implements TenantConfigPackageExcelImportStore {

  private final ConcurrentHashMap<String, PackageExcelSession> sessions = new ConcurrentHashMap<>();

  @Override
  public String save(PackageExcelSession session) {
    String token = UUID.randomUUID().toString().replace("-", "");
    sessions.put(token, session);
    return token;
  }

  @Override
  public PackageExcelSession get(String uploadToken) {
    return sessions.get(uploadToken);
  }

  @Override
  public void remove(String uploadToken) {
    sessions.remove(uploadToken);
  }
}
