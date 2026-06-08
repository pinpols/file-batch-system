package com.example.batch.orchestrator.application.service.governance;

import com.example.batch.orchestrator.infrastructure.admin.AdminTestDataCleanupRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 测试数据级联清理服务。只暴露给 orchestrator 内部接口，console 通过代理触发。 */
@Service
@RequiredArgsConstructor
public class AdminTestDataCleanupService {

  private final AdminTestDataCleanupRepository repository;

  @Transactional
  public Map<String, Integer> cleanupByPrefix(String prefix) {
    return repository.cleanupByPrefix(prefix);
  }

  @Transactional
  public Map<String, Integer> cleanupByExactTenantIds(List<String> tenantIds) {
    return repository.cleanupByExactTenantIds(tenantIds);
  }
}
