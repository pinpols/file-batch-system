package com.example.batch.console.domain.ops.service;

import com.example.batch.console.domain.ops.infrastructure.ConsoleAdminTestDataCleanupRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 测试数据级联清理 service，负责事务边界，SQL 下沉到 repository。 */
@Service
@RequiredArgsConstructor
public class ConsoleAdminTestDataCleanupService {

  private final ConsoleAdminTestDataCleanupRepository repository;

  @Transactional
  public Map<String, Integer> cleanupByPrefix(String prefix) {
    return repository.cleanupByPrefix(prefix);
  }

  @Transactional
  public Map<String, Integer> cleanupByExactTenantIds(List<String> tenantIds) {
    return repository.cleanupByExactTenantIds(tenantIds);
  }
}
